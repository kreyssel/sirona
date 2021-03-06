/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sirona.collector.server;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sirona.Role;
import org.apache.sirona.SironaException;
import org.apache.sirona.collector.server.api.SSLSocketFactoryProvider;
import org.apache.sirona.collector.server.api.SecurityProvider;
import org.apache.sirona.configuration.Configuration;
import org.apache.sirona.configuration.ioc.IoCs;
import org.apache.sirona.counters.Counter;
import org.apache.sirona.counters.Unit;
import org.apache.sirona.math.M2AwareStatisticalSummary;
import org.apache.sirona.repositories.Repository;
import org.apache.sirona.status.NodeStatus;
import org.apache.sirona.status.Status;
import org.apache.sirona.status.ValidationResult;
import org.apache.sirona.store.BatchFuture;
import org.apache.sirona.store.counter.CollectorCounterStore;
import org.apache.sirona.store.gauge.CollectorGaugeDataStore;
import org.apache.sirona.store.status.CollectorNodeStatusDataStore;
import org.apache.sirona.store.status.NodeStatusDataStore;
import org.apache.sirona.util.DaemonThreadFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

// should work with cube clients, see cube module for details
// Note: for this simple need we don't need JAXRS
public class Collector extends HttpServlet {
    private static final Logger LOGGER = Logger.getLogger(Collector.class.getName());

    private static final String OK = "{}";
    private static final String GAUGE = "gauge";
    private static final String COUNTER = "counter";
    private static final String VALIDATION = "validation";
    private static final String STATUS = "status";
    private static final String REGISTRATION = "registration";

    private static final String GET = "GET";

    private final Map<String, Role> roles = new ConcurrentHashMap<String, Role>();

    private CollectorCounterStore counterDataStore = null;
    private CollectorGaugeDataStore gaugeDataStore = null;
    private CollectorNodeStatusDataStore statusDataStore;
    private ObjectMapper mapper;

    private final Collection<AgentNode> agents = new CopyOnWriteArraySet<AgentNode>();
    private volatile BatchFuture collectionFuture = null;
    private long collectionPeriod;
    private SecurityProvider securityProvider;
    private SSLSocketFactoryProvider sslSocketFactoryProvider;

    @Override
    public void init(final ServletConfig sc) throws ServletException {
        super.init(sc);

        // force init to ensure we have stores
        IoCs.findOrCreateInstance(Repository.class);

        {
            final CollectorGaugeDataStore gds = IoCs.findOrCreateInstance(CollectorGaugeDataStore.class);
            if (gds == null) {
                throw new IllegalStateException("Collector only works with " + CollectorGaugeDataStore.class.getName());
            }
            this.gaugeDataStore = CollectorGaugeDataStore.class.cast(gds);
        }

        {
            final CollectorCounterStore cds = IoCs.findOrCreateInstance(CollectorCounterStore.class);
            if (cds == null) {
                throw new IllegalStateException("Collector only works with " + CollectorCounterStore.class.getName());
            }
            this.counterDataStore = CollectorCounterStore.class.cast(cds);
        }

        {
            final NodeStatusDataStore nds = IoCs.findOrCreateInstance(CollectorNodeStatusDataStore.class);
            if (!CollectorNodeStatusDataStore.class.isInstance(nds)) {
                throw new IllegalStateException("Collector only works with " + CollectorNodeStatusDataStore.class.getName());
            }
            this.statusDataStore = CollectorNodeStatusDataStore.class.cast(nds);
        }

        this.mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true);

        { // pulling
            {
                final String periodKey = Configuration.CONFIG_PROPERTY_PREFIX + "collector.collection.period";
                final String collectionPeriodStr = sc.getInitParameter(periodKey);
                if (collectionPeriodStr != null) {
                    collectionPeriod = Integer.parseInt(collectionPeriodStr);
                } else {
                    collectionPeriod = Configuration.getInteger(periodKey, 60000);
                }
            }

            {
                final String agentUrlsKey = Configuration.CONFIG_PROPERTY_PREFIX + "collector.collection.agent-urls";
                for (final String agents : new String[]{
                    Configuration.getProperty(agentUrlsKey, null),
                    sc.getInitParameter(agentUrlsKey)
                }) {
                    if (agents != null) {
                        for (final String url : agents.split(",")) {
                            try {
                                registerNode(url.trim());
                            } catch (final MalformedURLException e) {
                                throw new SironaException(e);
                            }
                        }
                    }
                }
            }

            try {
                securityProvider = IoCs.findOrCreateInstance(SecurityProvider.class);
            } catch (final Exception e) {
                securityProvider = null;
            }

            try {
                sslSocketFactoryProvider = IoCs.findOrCreateInstance(SSLSocketFactoryProvider.class);
            } catch (final Exception e) {
                sslSocketFactoryProvider = null;
            }
        }
    }

    @Override
    public void destroy() {
        if (collectionFuture != null) {
            collectionFuture.done();
        }
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        final ServletInputStream inputStream = req.getInputStream();
        try {
            slurpEvents(inputStream);
        } catch (final SironaException me) {
            resp.setStatus(HttpURLConnection.HTTP_BAD_REQUEST);
            resp.getWriter().write("{\"error\":\"" + me.getCause().getMessage().replace('\"', ' ') + "\"}");
            return;
        }

        resp.setStatus(HttpURLConnection.HTTP_OK);
        resp.getWriter().write(OK);
    }

    private void slurpEvents(final InputStream inputStream) throws IOException {
        final Event[] events = mapper.readValue(inputStream, Event[].class);
        if (events != null && events.length > 0) {
            try {
                final Collection<Event> validations = new LinkedList<Event>();
                long date = -1;
                for (final Event event : events) {
                    final String type = event.getType();
                    if (VALIDATION.equals(type)) {
                        validations.add(event);
                    } else if (STATUS.equals(type)) {
                        date = Number.class.cast(event.getData().get("date")).longValue();
                    } else if (COUNTER.equals(type)) {
                        updateCounter(event);
                    } else if (GAUGE.equals(type)) {
                        updateGauge(event);
                    } else if (REGISTRATION.equals(type)) {
                        registerNode(event);
                    } else {
                        LOGGER.info("Unexpected type '" + type + "', skipping");
                    }
                }

                if (validations.size() > 0) {
                    final Collection<ValidationResult> results = new ArrayList<ValidationResult>(validations.size());
                    for (final Event event : validations) {
                        final Map<String, Object> data = event.getData();
                        results.add(new ValidationResult(
                            (String) data.get("name"),
                            Status.valueOf((String) data.get("status")),
                            (String) data.get("message")));
                    }

                    final Date statusDate;
                    if (date == -1) {
                        statusDate = new Date();
                    } else {
                        statusDate = new Date(date);
                    }
                    final NodeStatus status = new NodeStatus(results.toArray(new ValidationResult[results.size()]), statusDate);
                    statusDataStore.store((String) events[0].getData().get("marker"), status);
                }
            } catch (final Exception e) {
                throw new SironaException(e);
            }
        }
    }

    private void registerNode(final Event event) throws MalformedURLException {
        registerNode(String.class.cast(event.getData().get("url")));
    }

    private void registerNode(final String url) throws MalformedURLException {
        if (url == null) {
            return;
        }

        final AgentNode node = new AgentNode(url);
        if (agents.add(node)) {
            if (collectionFuture == null) {
                synchronized (this) {
                    if (collectionFuture == null) {
                        final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory("collector-pull-schedule"));
                        final ScheduledFuture<?> future = ses.scheduleAtFixedRate(new CollectTask(), collectionPeriod, collectionPeriod, TimeUnit.MILLISECONDS);
                        collectionFuture = new BatchFuture(ses, future);
                    }
                }
            }
        }
    }

    private void updateGauge(final Event event) {
        final Map<String, Object> data = event.getData();

        final long time = event.getTime().getTime();
        final double value = Number.class.cast(data.get("value")).doubleValue();

        gaugeDataStore.addToGauge(role(data), time, value, String.class.cast(data.get("marker")));
    }

    private void updateCounter(final Event event) {
        final Map<String, Object> data = event.getData();

        counterDataStore.update(
            new Counter.Key(role(data), String.class.cast(data.get("name"))),
            String.class.cast(data.get("marker")),
            new M2AwareStatisticalSummary(data),
            Number.class.cast(data.get("concurrency")).intValue());
    }

    private Role role(final Map<String, Object> data) {
        final String name = String.class.cast(data.get("role"));
        final Role existing = roles.get(name);
        if (existing != null) {
            return existing;
        }

        final Role created = new Role(name, Unit.get(String.class.cast(data.get("unit"))));
        roles.put(name, created);
        return created;
    }

    private class CollectTask implements Runnable {
        @Override
        public void run() {
            final Iterator<AgentNode> nodes = agents.iterator();
            while (nodes.hasNext()) {
                final AgentNode agent = nodes.next();
                try {
                    final URL url = agent.getUrl();
                    final HttpURLConnection connection = HttpURLConnection.class.cast(url.openConnection());

                    if (sslSocketFactoryProvider != null) {
                        final SSLSocketFactory sf = sslSocketFactoryProvider.sslSocketFactory(url.toExternalForm());
                        if (sf != null && "https".equals(agent.getUrl().getProtocol())) {
                            HttpsURLConnection.class.cast(connection).setSSLSocketFactory(sf);
                        }
                    }

                    if (securityProvider != null) {
                        final String auth = securityProvider.basicHeader(url.toExternalForm());
                        if (auth != null) {
                            connection.setRequestProperty("Authorization", auth);
                        }
                    }

                    connection.setRequestMethod(GET);

                    InputStream inputStream = null;
                    try {
                        inputStream = connection.getInputStream();
                        slurpEvents(inputStream);
                    } finally {
                        connection.disconnect();
                        if (inputStream != null) {
                            try {
                                inputStream.close();
                            } catch (final IOException ioe) {
                                // no-op
                            }
                        }
                    }

                    final int status = connection.getResponseCode();
                    if (status / 100 == 2) {
                        agent.ok();
                    } else {
                        agent.ko();
                    }
                } catch (final IOException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    agent.ko();
                }

                if (agent.isDead()) {
                    nodes.remove();
                }
            }
        }
    }
}
