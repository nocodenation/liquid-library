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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * Servlet handling public API requests from Node.js applications.
 *
 * <p>This servlet:</p>
 * <ul>
 *   <li>Matches incoming requests to registered endpoint patterns</li>
 *   <li>Extracts request details (headers, body, query params, path params)</li>
 *   <li>For Java processors: calls handler directly</li>
 *   <li>For Python processors: queues request for polling</li>
 *   <li>Returns appropriate HTTP responses</li>
 *   <li>Handles CORS if enabled</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class GatewayServlet extends HttpServlet {

    private final StandardNodeJSAppAPIGateway gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CorsHandler corsHandler;

    public GatewayServlet(StandardNodeJSAppAPIGateway gateway) {
        this.gateway = gateway;
        this.corsHandler = new CorsHandler(gateway.isEnableCors());
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Handle CORS preflight
        if (corsHandler.handlePreflightIfNeeded(req, resp)) {
            return;
        }

        // Add CORS headers if enabled
        corsHandler.addCorsHeadersIfEnabled(resp);

        // Skip internal endpoints
        String path = req.getPathInfo() != null ? req.getPathInfo() : req.getServletPath();
        if (path.startsWith("/_internal") || path.startsWith("/_metrics")) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Find matching endpoint
        EndpointMatcher.MatchResult matchResult = findMatchingEndpoint(path);

        if (matchResult == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "No endpoint registered for path: " + path);
            return;
        }

        String pattern = matchResult.getPattern();

        // Record request via gateway method delegation (maintains encapsulation)
        gateway.recordRequest(pattern);

        try {
            handleRequest(req, resp, pattern, matchResult.getPathParameters());
        } catch (Exception e) {
            gateway.recordFailure(pattern);
            log("Error processing request for pattern " + pattern + ": " + e.getMessage(), e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

    private void handleRequest(HttpServletRequest req, HttpServletResponse resp, String pattern,
                                Map<String, String> pathParams) throws IOException {

        long startTime = System.currentTimeMillis();

        // Read request body
        byte[] body = readRequestBody(req);

        if (body.length > gateway.getMaxRequestSize()) {
            resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
            gateway.recordFailure(pattern);
            return;
        }

        // Build GatewayRequest
        GatewayRequest request = buildGatewayRequest(req, body, pathParams);

        // Get handler (for Java processors) or queue (for Python processors)
        EndpointHandler handler = gateway.getEndpointHandler(pattern);
        Queue<GatewayRequest> queue = gateway.getEndpointQueue(pattern);

        GatewayResponse response;

        if (handler != null) {
            // Java processor - synchronous handling
            try {
                response = handler.handleRequest(request);
                long latency = System.currentTimeMillis() - startTime;
                gateway.recordSuccess(pattern, latency);
            } catch (RequestProcessingException e) {
                // Log warning using servlet context
                log("Request processing failed for " + pattern + ": " + e.getMessage());
                response = GatewayResponse.internalError(e.getMessage());
                gateway.recordFailure(pattern);
            }
        } else if (queue != null) {
            // Python processor - queue for polling
            if (!queue.offer(request)) {
                response = GatewayResponse.queueFull();
                gateway.recordQueueFull(pattern);
            } else {
                response = GatewayResponse.accepted();
                gateway.updateQueueSize(pattern, queue.size());
                long latency = System.currentTimeMillis() - startTime;
                gateway.recordSuccess(pattern, latency);
            }
        } else {
            response = GatewayResponse.internalError("No handler or queue for endpoint");
            gateway.recordFailure(pattern);
        }

        // Send response
        sendGatewayResponse(resp, response);
    }

    private GatewayRequest buildGatewayRequest(HttpServletRequest req, byte[] body, Map<String, String> pathParams) {
        // Extract headers
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = req.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, req.getHeader(headerName));
        }

        // Extract query parameters
        Map<String, String> queryParams = new HashMap<>();
        Map<String, String[]> paramMap = req.getParameterMap();
        for (Map.Entry<String, String[]> entry : paramMap.entrySet()) {
            if (entry.getValue().length > 0) {
                queryParams.put(entry.getKey(), entry.getValue()[0]);
            }
        }

        return GatewayRequest.builder()
                .method(req.getMethod())
                .path(req.getPathInfo() != null ? req.getPathInfo() : req.getServletPath())
                .headers(headers)
                .queryParameters(queryParams)
                .pathParameters(pathParams)
                .contentType(req.getContentType())
                .body(body)
                .clientAddress(req.getRemoteAddr())
                .timestamp(Instant.now())
                .build();
    }

    private byte[] readRequestBody(HttpServletRequest req) throws IOException {
        try (InputStream in = req.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return out.toByteArray();
        }
    }

    private void sendGatewayResponse(HttpServletResponse resp, GatewayResponse gatewayResponse) throws IOException {
        resp.setStatus(gatewayResponse.getStatusCode());

        // Set headers (includes Content-Type if present)
        for (Map.Entry<String, String> header : gatewayResponse.getHeaders().entrySet()) {
            resp.setHeader(header.getKey(), header.getValue());
        }

        // Write body
        String body = gatewayResponse.getBody();
        if (body != null && !body.isEmpty()) {
            resp.getOutputStream().write(body.getBytes("UTF-8"));
        }
    }

    private EndpointMatcher.MatchResult findMatchingEndpoint(String path) {
        for (String pattern : gateway.getRegisteredEndpoints()) {
            // Use cached matcher to avoid recreating EndpointMatcher and recompiling regex on every request
            EndpointMatcher matcher = gateway.getEndpointMatcher(pattern);
            if (matcher != null) {
                EndpointMatcher.MatchResult result = matcher.match(path);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}