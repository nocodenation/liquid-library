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
import java.util.HashMap;
import java.util.Map;

/**
 * Metrics endpoint servlet exposing gateway statistics.
 *
 * <p>GET /_metrics returns JSON with metrics for all registered endpoints:</p>
 * <pre>
 * {
 *   "endpoints": {
 *     "/api/events": {
 *       "totalRequests": 1250,
 *       "successfulRequests": 1200,
 *       "failedRequests": 30,
 *       "queueFullRejections": 20,
 *       "averageLatencyMs": 15,
 *       "currentQueueSize": 5,
 *       "successRate": 96.0,
 *       "lastRequestTime": "2024-01-15T10:30:45Z"
 *     }
 *   }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public class MetricsServlet extends HttpServlet {

    private final StandardNodeJSAppAPIGateway gateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MetricsServlet(StandardNodeJSAppAPIGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Map<String, Object> metricsJson = new HashMap<>();
        Map<String, Object> endpointsMetrics = new HashMap<>();

        for (String pattern : gateway.getRegisteredEndpoints()) {
            EndpointMetrics metrics = gateway.getEndpointMetrics(pattern);

            if (metrics != null) {
                Map<String, Object> endpointData = new HashMap<>();
                endpointData.put("totalRequests", metrics.getTotalRequests());
                endpointData.put("successfulRequests", metrics.getSuccessfulRequests());
                endpointData.put("failedRequests", metrics.getFailedRequests());
                endpointData.put("queueFullRejections", metrics.getQueueFullRejections());
                endpointData.put("averageLatencyMs", metrics.getAverageLatencyMs());
                endpointData.put("currentQueueSize", metrics.getCurrentQueueSize());
                endpointData.put("successRate", metrics.getSuccessRate());

                if (metrics.getLastRequestTime() != null) {
                    endpointData.put("lastRequestTime", metrics.getLastRequestTime().toString());
                } else {
                    endpointData.put("lastRequestTime", null);
                }

                endpointsMetrics.put(pattern, endpointData);
            }
        }

        metricsJson.put("endpoints", endpointsMetrics);

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        objectMapper.writeValue(resp.getWriter(), metricsJson);
    }
}