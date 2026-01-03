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

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Internal API servlet for Python processors to poll for requests.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /_internal/poll/:pattern - Poll for next request matching pattern (long-polling)</li>
 *   <li>POST /_internal/respond/:pattern - Submit response for a request</li>
 * </ul>
 *
 * <p>Request/response format is JSON with base64-encoded binary body.</p>
 *
 * @since 1.0.0
 */
public class InternalApiServlet extends HttpServlet {

    private final StandardNodeJSAppAPIGateway gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InternalApiServlet(StandardNodeJSAppAPIGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String pathInfo = req.getPathInfo();

        if (pathInfo == null || !pathInfo.startsWith("/poll/")) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Extract pattern from path: /_internal/poll/:pattern
        String pattern = pathInfo.substring(6); // Remove "/poll/"

        // URL decode the pattern
        pattern = java.net.URLDecoder.decode(pattern, "UTF-8");

        handlePoll(pattern, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Response handling not yet implemented - Python processors return 202 Accepted
        // Future enhancement: implement async response correlation via WebSocket or SSE
        resp.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"Response handling not implemented\",\"message\":\"Python processors currently return 202 Accepted. Async response handling is a future enhancement.\"}");
    }

    private void handlePoll(String pattern, HttpServletResponse resp) throws IOException {
        Queue<GatewayRequest> queue = gateway.getEndpointQueue(pattern);

        if (queue == null) {
            resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"Endpoint not registered\"}");
            return;
        }

        // Cast to BlockingQueue for poll with timeout support
        if (!(queue instanceof BlockingQueue)) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Queue does not support blocking operations");
            return;
        }

        BlockingQueue<GatewayRequest> blockingQueue = (BlockingQueue<GatewayRequest>) queue;

        try {
            // Poll with timeout (long-polling)
            GatewayRequest request = blockingQueue.poll(30, TimeUnit.SECONDS);

            if (request == null) {
                // Timeout - return 204 No Content
                resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }

            // Update queue size metric via method delegation
            gateway.updateQueueSize(pattern, blockingQueue.size());

            // Serialize request to JSON
            Map<String, Object> requestJson = new HashMap<>();
            requestJson.put("method", request.getMethod());
            requestJson.put("path", request.getPath());
            requestJson.put("headers", request.getHeaders());
            requestJson.put("queryParameters", request.getQueryParameters());
            requestJson.put("pathParameters", request.getPathParameters());
            requestJson.put("contentType", request.getContentType());

            // Base64 encode binary body
            if (request.getBody() != null && request.getBody().length > 0) {
                requestJson.put("body", Base64.getEncoder().encodeToString(request.getBody()));
            } else {
                requestJson.put("body", "");
            }

            requestJson.put("bodyText", request.getBodyAsString());
            requestJson.put("clientAddress", request.getClientAddress());
            requestJson.put("timestamp", request.getTimestamp().toString());

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            objectMapper.writeValue(resp.getWriter(), requestJson);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Interrupted while polling");
        }
    }

}