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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents an HTTP response to send back to the client.
 *
 * <p>This immutable class encapsulates the HTTP status code, response body, and headers
 * that will be sent to the Node.js application. The gateway provides factory methods
 * for common response types.</p>
 *
 * <p><b>Common responses:</b></p>
 * <ul>
 *   <li>{@link #accepted()} - 202 Accepted (request queued successfully)</li>
 *   <li>{@link #badRequest(String)} - 400 Bad Request (invalid request)</li>
 *   <li>{@link #queueFull()} - 503 Service Unavailable (queue at capacity)</li>
 *   <li>{@link #internalError(String)} - 500 Internal Server Error</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> This class is immutable and thread-safe.</p>
 *
 * @since 1.0.0
 */
public final class GatewayResponse {

    private static final String CONTENT_TYPE_JSON = "application/json";

    private final int statusCode;
    private final String body;
    private final Map<String, String> headers;

    /**
     * Constructs a response with status code only.
     *
     * @param statusCode HTTP status code
     */
    public GatewayResponse(int statusCode) {
        this(statusCode, "", Collections.emptyMap());
    }

    /**
     * Constructs a response with status code and body.
     *
     * @param statusCode HTTP status code
     * @param body response body
     */
    public GatewayResponse(int statusCode, String body) {
        this(statusCode, body, Collections.emptyMap());
    }

    /**
     * Constructs a response with status code, body, and headers.
     *
     * @param statusCode HTTP status code
     * @param body response body
     * @param headers HTTP headers
     */
    public GatewayResponse(int statusCode, String body, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.body = body != null ? body : "";
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
    }

    /**
     * Gets the HTTP status code.
     *
     * @return status code (e.g., 200, 400, 503)
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Gets the response body.
     *
     * @return response body (may be empty)
     */
    public String getBody() {
        return body;
    }

    /**
     * Gets the response headers.
     *
     * @return immutable map of headers
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    // Factory methods for common responses

    /**
     * Creates a 202 Accepted response.
     *
     * <p>This indicates the request has been queued for processing. This is the
     * most common response for successful requests to the gateway.</p>
     *
     * @return 202 Accepted response with JSON body
     */
    public static GatewayResponse accepted() {
        return new GatewayResponse(202, "{\"status\":\"accepted\"}",
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Creates a 201 Created response with Location header.
     *
     * <p>Used when a resource is created and the client should know its URL.</p>
     *
     * @param location URL of the created resource
     * @return 201 Created response
     */
    public static GatewayResponse created(String location) {
        return new GatewayResponse(201, "",
            Map.of("Location", location));
    }

    /**
     * Creates a 200 OK response with custom body.
     *
     * @param body response body
     * @return 200 OK response
     */
    public static GatewayResponse ok(String body) {
        return new GatewayResponse(200, body,
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Creates a 400 Bad Request response.
     *
     * <p>Used when the request is malformed or contains invalid data.</p>
     *
     * @param message error message to include in response
     * @return 400 Bad Request response with JSON error
     */
    public static GatewayResponse badRequest(String message) {
        String json = String.format("{\"error\":\"%s\"}", escapeJson(message));
        return new GatewayResponse(400, json,
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Creates a 404 Not Found response.
     *
     * <p>Used when the requested endpoint doesn't exist.</p>
     *
     * @param endpoint the endpoint that was not found
     * @return 404 Not Found response with JSON error
     */
    public static GatewayResponse notFound(String endpoint) {
        String json = String.format("{\"error\":\"Endpoint not found: %s\"}", escapeJson(endpoint));
        return new GatewayResponse(404, json,
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Creates a 413 Payload Too Large response.
     *
     * <p>Used when the request body exceeds the maximum allowed size.</p>
     *
     * @return 413 Payload Too Large response
     */
    public static GatewayResponse payloadTooLarge() {
        return new GatewayResponse(413, "{\"error\":\"Payload too large\"}",
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Creates a 500 Internal Server Error response.
     *
     * <p>Used when an unexpected error occurs during request processing.</p>
     *
     * @param message error message
     * @return 500 Internal Server Error response with JSON error
     */
    public static GatewayResponse internalError(String message) {
        String json = String.format("{\"error\":\"%s\"}", escapeJson(message));
        return new GatewayResponse(500, json,
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Creates a 503 Service Unavailable response (queue full).
     *
     * <p>Used when the endpoint's queue is at capacity and cannot accept more requests.
     * The response includes a Retry-After header suggesting when to retry.</p>
     *
     * @return 503 Service Unavailable response with JSON error and Retry-After header
     */
    public static GatewayResponse queueFull() {
        return new GatewayResponse(503, "{\"error\":\"Service busy, queue full\"}",
            Map.of("Content-Type", CONTENT_TYPE_JSON, "Retry-After", "5"));
    }

    /**
     * Creates a 504 Gateway Timeout response.
     *
     * <p>Used when request processing takes longer than the configured timeout.</p>
     *
     * @return 504 Gateway Timeout response
     */
    public static GatewayResponse timeout() {
        return new GatewayResponse(504, "{\"error\":\"Gateway timeout\"}",
            Map.of("Content-Type", CONTENT_TYPE_JSON));
    }

    /**
     * Escapes special characters for JSON strings.
     *
     * @param str string to escape
     * @return escaped string
     */
    private static String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    @Override
    public String toString() {
        return "GatewayResponse{" +
                "statusCode=" + statusCode +
                ", bodyLength=" + body.length() +
                ", headers=" + headers.keySet() +
                '}';
    }
}