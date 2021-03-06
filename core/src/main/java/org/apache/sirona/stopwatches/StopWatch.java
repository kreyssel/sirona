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

package org.apache.sirona.stopwatches;

/**
 * Instrumentation tool to compute resource consumption of some code fragment execution.
 * <p/>
 * StopWatch implementation is supposed not to be thread-safe and to be a one-shot tool. Don't
 * share it beetween threads, don't try to reuse it.
 *
 * @author <a href="mailto:nicolas@apache.org">Nicolas De Loof</a>
 */
public interface StopWatch {
    long getElapsedTime();

    StopWatch stop();
}