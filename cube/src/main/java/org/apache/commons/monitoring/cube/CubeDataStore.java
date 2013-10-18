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
package org.apache.commons.monitoring.cube;

import org.apache.commons.monitoring.Role;
import org.apache.commons.monitoring.configuration.Configuration;
import org.apache.commons.monitoring.counters.Counter;
import org.apache.commons.monitoring.counters.MetricData;
import org.apache.commons.monitoring.gauges.Gauge;
import org.apache.commons.monitoring.repositories.Repository;
import org.apache.commons.monitoring.store.BatchCounterDataStore;
import org.apache.commons.monitoring.store.DefaultDataStore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

public class CubeDataStore extends BatchCounterDataStore {
    private static final String GAUGE_TYPE = "gauge";
    private static final String COUNTER_TYPE = "counter";

    private final Cube cube = Configuration.findOrCreateInstance(CubeBuilder.class).build();

    @Override
    protected Counter newCounter(final Counter.Key key) {
        return new CubeCounter(key, this);
    }

    @Override
    public void addToCounter(final Counter counter, final double delta) {
        if (!CubeCounter.class.isInstance(counter)) {
            throw new IllegalArgumentException(DefaultDataStore.class.getName() + " only supports " + CubeCounter.class.getName());
        }

        final CubeCounter cubeCounter = CubeCounter.class.cast(counter);
        final Lock lock = cubeCounter.getLock();
        lock.lock();
        try {
            cubeCounter.addInternal(delta);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected synchronized void pushCountersByBatch(final Repository instance) {
        final long ts = System.currentTimeMillis();
        final StringBuilder events = cube.newEventStream();
        for (final Counter counter : instance) {
            cube.buildEvent(events, COUNTER_TYPE, ts, new MapBuilder()
                .add("name", counter.getKey().getName())
                .add("role", counter.getKey().getRole().getName())
                // other metrics are not handled by CubeCounter and useless since cube re-aggregate
                // so to reduce overhead we just store it locally
                .add("concurrency", MetricData.Concurrency.value(counter))
                .add("sum", MetricData.Sum.value(counter))
                .add("hits", MetricData.Hits.value(counter))
                .map());
        }
        cube.post(events);
    }

    @Override
    public void addToGauge(final Gauge gauge, final long time, final double value) {
        final Role role = gauge.role();

        cube.post(
            cube.buildEvent(new StringBuilder(), GAUGE_TYPE, time,
                new MapBuilder()
                    .add("value", value)
                    .add("role", role.getName())
                    .add("unit", role.getUnit().getName())
                    .map()));
    }

    private static class MapBuilder {
        private final Map<String, Object> map = new HashMap<String, Object>();

        public MapBuilder add(final String key, final Object value) {
            map.put(key, value);
            return this;
        }

        public Map<String, Object> map() {
            return map;
        }
    }
}
