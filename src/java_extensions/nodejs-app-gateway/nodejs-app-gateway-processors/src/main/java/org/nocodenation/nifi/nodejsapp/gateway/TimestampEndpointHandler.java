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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.nifi.logging.ComponentLog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * EndpointHandler implementation that returns current timestamp in JSON format.
 * 
 * <p>This class implements the EndpointHandler interface to provide synchronous
 * timestamp responses. It uses Jackson ObjectMapper for proper JSON serialization
 * instead of manual StringBuilder approach.</p>
 * 
 * @since 1.0.0
 * @see EndpointHandler
 */
public class TimestampEndpointHandler implements EndpointHandler {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final boolean includeFormatted;
    private final DateTimeFormatter formatter;
    private final ComponentLog logger;

    /**
     * Creates a new TimestampEndpointHandler.
     * 
     * @param includeFormatted whether to include human-readable formatted timestamp
     * @param timeZone the time zone for formatting
     * @param logger the component logger for debug messages
     */
    public TimestampEndpointHandler(boolean includeFormatted, ZoneId timeZone, ComponentLog logger) {
        this.includeFormatted = includeFormatted;
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(timeZone);
        this.logger = logger;
    }

    @Override
    public GatewayResponse handleRequest(GatewayRequest request) throws RequestProcessingException {
        try {
            Instant now = Instant.now();

            // Build JSON response using Jackson ObjectMapper
            ObjectNode json = objectMapper.createObjectNode();
            json.put("timestamp", now.toString());
            json.put("epochMillis", now.toEpochMilli());

            if (includeFormatted) {
                json.put("formatted", formatter.format(now));
            }

            json.put("endpoint", request.getPath());
            json.put("method", request.getMethod());

            // Serialize to string
            String jsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);

            // Return successful response with JSON content type
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            if (logger != null) {
                logger.debug("Processed timestamp request for endpoint '{}' from {}",
                        request.getPath(), request.getClientAddress());
            }

            return new GatewayResponse(200, jsonString, headers);

        } catch (Exception e) {
            if (logger != null) {
                logger.error("Error generating timestamp response: {}", e.getMessage(), e);
            }

            // Build error response using Jackson
            try {
                ObjectNode errorJson = objectMapper.createObjectNode();
                errorJson.put("error", "Failed to generate timestamp");
                errorJson.put("message", e.getMessage());

                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");

                return new GatewayResponse(500, objectMapper.writeValueAsString(errorJson), headers);
            } catch (Exception jsonError) {
                // Fallback to simple string if Jackson fails
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");
                return new GatewayResponse(500, "{\"error\":\"Internal server error\"}", headers);
            }
        }
    }
}
