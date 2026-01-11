/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nocodenation.nifi.nodejsapp.gateway;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks metrics for a specific endpoint.
 *
 * <p>This class provides thread-safe tracking of request statistics including:</p>
 * <ul>
 *   <li>Total requests received</li>
 *   <li>Successful requests (accepted)</li>
 *   <li>Failed requests (errors)</li>
 *   <li>Queue-full rejections (503 responses)</li>
 *   <li>Average request latency</li>
 *   <li>Current queue size</li>
 *   <li>Last request timestamp</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class EndpointMetrics {

    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicInteger queueFullRejections = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicInteger currentQueueSize = new AtomicInteger(0);
    private volatile Instant lastRequestTime;

    public int getTotalRequests() {
        return totalRequests.get();
    }

    public int getSuccessfulRequests() {
        return successfulRequests.get();
    }

    public int getFailedRequests() {
        return failedRequests.get();
    }

    public int getQueueFullRejections() {
        return queueFullRejections.get();
    }

    public long getAverageLatencyMs() {
        int total = totalRequests.get();
        return total > 0 ? totalLatencyMs.get() / total : 0;
    }

    public int getCurrentQueueSize() {
        return currentQueueSize.get();
    }

    public Instant getLastRequestTime() {
        return lastRequestTime;
    }

    public double getSuccessRate() {
        int total = totalRequests.get();
        return total > 0 ? (double) successfulRequests.get() / total * 100.0 : 100.0;
    }

    // Package-private update methods
    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void recordSuccess(long latencyMs) {
        successfulRequests.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        lastRequestTime = Instant.now();
    }

    public void recordFailure() {
        failedRequests.incrementAndGet();
        lastRequestTime = Instant.now();
    }

    public void recordQueueFull() {
        queueFullRejections.incrementAndGet();
        lastRequestTime = Instant.now();
    }

    public void updateQueueSize(int size) {
        currentQueueSize.set(size);
    }
}