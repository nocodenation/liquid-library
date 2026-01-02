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

/**
 * Functional interface for handling incoming HTTP requests at registered endpoints.
 *
 * <p>Processors implement this interface (typically as a lambda expression) to receive
 * and process requests from Node.js applications. The handler is responsible for:</p>
 * <ul>
 *   <li>Validating the request (content-type, payload structure, etc.)</li>
 *   <li>Queuing the request data for later processing by the processor's @OnTrigger method</li>
 *   <li>Returning an appropriate HTTP response to the client</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Handlers may be called concurrently from multiple threads
 * (one per incoming HTTP request). Implementations must be thread-safe.</p>
 *
 * <p><b>Performance:</b> Handlers should execute quickly (typically <10ms) as they run
 * on Jetty's HTTP server threads. Long-running operations should be queued for processing
 * in the processor's @OnTrigger method.</p>
 *
 * <p><b>Example implementation:</b></p>
 * <pre>
 * EndpointHandler handler = request -> {
 *     // Validate content type
 *     if (!request.isJson()) {
 *         return GatewayResponse.badRequest("Content-Type must be application/json");
 *     }
 *
 *     // Create data object
 *     FlowFileData data = FlowFileData.builder()
 *         .content(request.getBody())
 *         .attribute("endpoint.path", request.getPath())
 *         .attribute("http.method", request.getMethod())
 *         .build();
 *
 *     // Queue for processor
 *     boolean queued = requestQueue.offer(data);
 *     if (!queued) {
 *         return GatewayResponse.queueFull();
 *     }
 *
 *     return GatewayResponse.accepted();
 * };
 * </pre>
 *
 * @since 1.0.0
 * @see GatewayRequest
 * @see GatewayResponse
 * @see NodeJSAppAPIGateway#registerEndpoint(String, EndpointHandler)
 */
@FunctionalInterface
public interface EndpointHandler {

    /**
     * Processes an incoming HTTP request.
     *
     * <p>This method is called on a Jetty HTTP server thread when a request arrives
     * at the registered endpoint. It should:</p>
     * <ol>
     *   <li>Validate the request</li>
     *   <li>Queue the request data</li>
     *   <li>Return an appropriate response</li>
     * </ol>
     *
     * <p><b>Common response codes:</b></p>
     * <ul>
     *   <li>202 Accepted: Request queued successfully</li>
     *   <li>400 Bad Request: Invalid content-type or malformed payload</li>
     *   <li>503 Service Unavailable: Queue is full, client should retry</li>
     *   <li>500 Internal Server Error: Unexpected processing error</li>
     * </ul>
     *
     * <p><b>Exception handling:</b> If this method throws an exception, the gateway
     * will catch it, log the error, and return a 500 Internal Server Error to the client.</p>
     *
     * @param request the incoming HTTP request with headers, body, and metadata
     * @return HTTP response to send back to the client (status code + body)
     * @throws RequestProcessingException if the request cannot be processed due to a recoverable error
     */
    GatewayResponse handleRequest(GatewayRequest request) throws RequestProcessingException;
}