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

import org.apache.nifi.controller.ControllerService;
import java.util.List;
import java.util.Queue;

/**
 * Controller Service providing HTTP gateway for Node.js application communication.
 *
 * <p>This service runs an embedded HTTP server that allows managed Node.js applications
 * to send data directly to NiFi processors without complex routing logic. It provides
 * a lightweight alternative to HandleHTTP patterns by offering direct endpoint-to-processor
 * mapping with automatic queuing and backpressure handling.</p>
 *
 * <p>The gateway supports two integration methods:</p>
 * <ul>
 *   <li><b>Java processors:</b> Direct queue access via {@link #registerEndpoint(String, EndpointHandler)}
 *       for zero-latency, high-performance scenarios</li>
 *   <li><b>Python processors:</b> HTTP polling via internal {@code /_internal/poll/{endpoint}}
 *       API for easier customization with acceptable latency (~100ms)</li>
 * </ul>
 *
 * <p><b>Security:</b> By default, the gateway binds to localhost (127.0.0.1) for security.
 * Cross-Origin Resource Sharing (CORS) can be configured to allow browser-based applications
 * to communicate with the gateway.</p>
 *
 * <p><b>Monitoring:</b> The gateway exposes metrics via {@code /_metrics} endpoint, providing
 * request counts, latencies, queue sizes, and success rates per endpoint.</p>
 *
 * <p><b>Example configuration:</b></p>
 * <pre>
 * Gateway Port: 5050
 * Gateway Host: 127.0.0.1
 * Max Queue Size: 1000
 * CORS Allowed Origins: http://localhost:3000
 * </pre>
 *
 * <p><b>Example Node.js usage:</b></p>
 * <pre>
 * fetch('http://localhost:5050/api/quality-event', {
 *   method: 'POST',
 *   headers: { 'Content-Type': 'application/json' },
 *   body: JSON.stringify(eventData)
 * })
 * </pre>
 *
 * @since 1.0.0
 * @see EndpointHandler
 * @see GatewayRequest
 * @see GatewayResponse
 * @see EndpointMetrics
 */
public interface NodeJSAppAPIGateway extends ControllerService {

    /**
     * Gets the port number the gateway HTTP server is listening on.
     *
     * @return configured port number (default: 5050)
     */
    int getGatewayPort();

    /**
     * Gets the full base URL of the gateway.
     *
     * <p>This URL can be passed to Node.js applications via environment variables
     * so they know where to send requests.</p>
     *
     * @return gateway base URL (e.g., "http://localhost:5050")
     */
    String getGatewayUrl();

    /**
     * Registers an endpoint pattern for direct queue access (Java processors only).
     *
     * <p>Java processors call this method during {@code @OnScheduled} to register themselves
     * as receivers for specific API endpoints. When requests arrive at the registered endpoint,
     * the provided handler is invoked to process the request and return a response.</p>
     *
     * <p><b>Endpoint patterns supported:</b></p>
     * <ul>
     *   <li>Exact match: {@code /api/quality-event}</li>
     *   <li>Wildcard: {@code /api/events/*} (matches any sub-path)</li>
     *   <li>Path parameters: {@code /api/user/{userId}} (captures userId as attribute)</li>
     * </ul>
     *
     * <p><b>Thread safety:</b> This method is synchronized and thread-safe. Multiple processors
     * can register different endpoints concurrently.</p>
     *
     * <p><b>Example usage:</b></p>
     * <pre>
     * gateway.registerEndpoint("/api/quality-event", request -> {
     *     FlowFileData data = FlowFileData.from(request);
     *     boolean queued = requestQueue.offer(data);
     *     return queued ? GatewayResponse.accepted() : GatewayResponse.queueFull();
     * });
     * </pre>
     *
     * @param pattern endpoint pattern to register (must start with '/')
     * @param handler callback to process incoming requests for this endpoint
     * @throws EndpointAlreadyRegisteredException if the pattern is already registered by another processor
     * @throws IllegalArgumentException if pattern is null, empty, or doesn't start with '/'
     * @see #unregisterEndpoint(String)
     */
    void registerEndpoint(String pattern, EndpointHandler handler)
        throws EndpointAlreadyRegisteredException;

    /**
     * Unregisters a previously registered endpoint (Java processors only).
     *
     * <p>Processors call this method during {@code @OnStopped} to clean up their endpoint
     * registrations and release resources.</p>
     *
     * <p>If the endpoint is not currently registered, this method does nothing (idempotent).</p>
     *
     * @param pattern endpoint pattern to unregister
     * @see #registerEndpoint(String, EndpointHandler)
     */
    void unregisterEndpoint(String pattern);

    /**
     * Gets a list of all currently registered endpoint patterns.
     *
     * <p>This can be used for monitoring, debugging, or by internal APIs to list
     * available endpoints.</p>
     *
     * @return immutable list of endpoint patterns currently registered (may be empty)
     */
    List<String> getRegisteredEndpoints();

    /**
     * Gets metrics for a specific endpoint.
     *
     * <p>Metrics include:</p>
     * <ul>
     *   <li>Total requests received</li>
     *   <li>Successful requests (queued/processed)</li>
     *   <li>Failed requests (errors)</li>
     *   <li>Queue-full rejections (503 responses)</li>
     *   <li>Average latency in milliseconds</li>
     *   <li>Current queue size</li>
     *   <li>Timestamp of last request</li>
     *   <li>Success rate percentage</li>
     * </ul>
     *
     * @param pattern endpoint pattern to get metrics for
     * @return metrics object with statistics, or null if endpoint doesn't exist
     * @see EndpointMetrics
     */
    EndpointMetrics getEndpointMetrics(String pattern);

    /**
     * Gets the request queue for an endpoint (for Java processors).
     *
     * <p>Java processors can poll this queue to retrieve incoming requests.
     * This method is used by Java-based processors that register with a null handler.</p>
     *
     * <p>Returns Queue interface to avoid exposing concrete implementation details.
     * The underlying implementation is a bounded blocking queue with configurable capacity.</p>
     *
     * @param pattern endpoint pattern
     * @return request queue, or null if endpoint doesn't exist or uses a handler
     */
    Queue<GatewayRequest> getEndpointQueue(String pattern);
}