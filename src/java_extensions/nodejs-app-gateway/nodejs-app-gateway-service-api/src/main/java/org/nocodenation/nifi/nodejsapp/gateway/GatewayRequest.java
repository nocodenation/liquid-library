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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an incoming HTTP request at the gateway.
 *
 * <p>This immutable DTO contains all information about an HTTP request including:</p>
 * <ul>
 *   <li>HTTP method (GET, POST, PUT, DELETE, etc.)</li>
 *   <li>Request path and query parameters</li>
 *   <li>Headers (including Content-Type)</li>
 *   <li>Request body as bytes</li>
 *   <li>Client address and timestamp</li>
 *   <li>Path parameters (from patterns like {@code /api/user/{userId}})</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> This class is immutable and thread-safe.</p>
 *
 * <p><b>Example usage:</b></p>
 * <pre>
 * GatewayRequest request = ...; // Provided by gateway
 *
 * // Check content type
 * if (request.isJson()) {
 *     String json = request.getBodyAsString();
 *     // Process JSON...
 * }
 *
 * // Get header
 * String userAgent = request.getHeader("User-Agent");
 *
 * // Get path parameter (from /api/user/{userId})
 * String userId = request.getPathParameter("userId");
 * </pre>
 *
 * @since 1.0.0
 */
public final class GatewayRequest {

    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final Map<String, String> queryParameters;
    private final Map<String, String> pathParameters;
    private final String contentType;
    private final byte[] body;
    private final String clientAddress;
    private final Instant timestamp;

    private GatewayRequest(Builder builder) {
        this.method = Objects.requireNonNull(builder.method, "Method cannot be null");
        this.path = Objects.requireNonNull(builder.path, "Path cannot be null");
        this.headers = Collections.unmodifiableMap(new HashMap<>(builder.headers));
        this.queryParameters = Collections.unmodifiableMap(new HashMap<>(builder.queryParameters));
        this.pathParameters = Collections.unmodifiableMap(new HashMap<>(builder.pathParameters));
        this.contentType = builder.contentType;
        this.body = builder.body != null ? builder.body.clone() : new byte[0];
        this.clientAddress = builder.clientAddress != null ? builder.clientAddress : "unknown";
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
    }

    /**
     * Gets the HTTP method.
     *
     * @return HTTP method (e.g., "GET", "POST", "PUT", "DELETE")
     */
    public String getMethod() {
        return method;
    }

    /**
     * Gets the request path (without query string).
     *
     * @return request path (e.g., "/api/quality-event")
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets all HTTP headers as an immutable map.
     *
     * <p>Header names are typically lowercase.</p>
     *
     * @return immutable map of headers
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Gets a specific header value.
     *
     * @param name header name (case-insensitive)
     * @return header value, or null if not present
     */
    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    /**
     * Gets all query parameters as an immutable map.
     *
     * @return immutable map of query parameters
     */
    public Map<String, String> getQueryParameters() {
        return queryParameters;
    }

    /**
     * Gets a specific query parameter value.
     *
     * @param name parameter name
     * @return parameter value, or null if not present
     */
    public String getQueryParameter(String name) {
        return queryParameters.get(name);
    }

    /**
     * Gets all path parameters as an immutable map.
     *
     * <p>Path parameters are captured from patterns like {@code /api/user/{userId}}.
     * For example, a request to {@code /api/user/123} would have a path parameter
     * {@code userId=123}.</p>
     *
     * @return immutable map of path parameters
     */
    public Map<String, String> getPathParameters() {
        return pathParameters;
    }

    /**
     * Gets a specific path parameter value.
     *
     * @param name parameter name (e.g., "userId")
     * @return parameter value, or null if not present
     */
    public String getPathParameter(String name) {
        return pathParameters.get(name);
    }

    /**
     * Gets the Content-Type header value.
     *
     * @return content type (e.g., "application/json"), or null if not present
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Gets the request body as a byte array.
     *
     * @return request body bytes (defensive copy)
     */
    public byte[] getBody() {
        return body.clone();
    }

    /**
     * Gets the request body as a UTF-8 string.
     *
     * @return request body as string
     */
    public String getBodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Gets the client IP address.
     *
     * @return client address (e.g., "127.0.0.1")
     */
    public String getClientAddress() {
        return clientAddress;
    }

    /**
     * Gets the timestamp when this request was received.
     *
     * @return request timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Checks if the Content-Type is JSON.
     *
     * @return true if Content-Type contains "application/json"
     */
    public boolean isJson() {
        return contentType != null && contentType.contains("application/json");
    }

    /**
     * Checks if the Content-Type is form data.
     *
     * @return true if Content-Type contains "application/x-www-form-urlencoded"
     */
    public boolean isFormData() {
        return contentType != null && contentType.contains("application/x-www-form-urlencoded");
    }

    /**
     * Creates a new Builder for constructing GatewayRequest instances.
     *
     * @return new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for GatewayRequest instances.
     */
    public static class Builder {
        private String method;
        private String path;
        private final Map<String, String> headers = new HashMap<>();
        private final Map<String, String> queryParameters = new HashMap<>();
        private final Map<String, String> pathParameters = new HashMap<>();
        private String contentType;
        private byte[] body;
        private String clientAddress;
        private Instant timestamp;

        /**
         * Sets the HTTP method.
         *
         * @param method HTTP method (e.g., "POST")
         * @return this builder
         */
        public Builder method(String method) {
            this.method = method;
            return this;
        }

        /**
         * Sets the request path.
         *
         * @param path request path (e.g., "/api/quality-event")
         * @return this builder
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Adds a header.
         *
         * @param name header name
         * @param value header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            this.headers.put(name.toLowerCase(), value);
            return this;
        }

        /**
         * Adds multiple headers.
         *
         * @param headers map of headers
         * @return this builder
         */
        public Builder headers(Map<String, String> headers) {
            headers.forEach((name, value) -> this.headers.put(name.toLowerCase(), value));
            return this;
        }

        /**
         * Adds a query parameter.
         *
         * @param name parameter name
         * @param value parameter value
         * @return this builder
         */
        public Builder queryParameter(String name, String value) {
            this.queryParameters.put(name, value);
            return this;
        }

        /**
         * Adds multiple query parameters.
         *
         * @param parameters map of parameters
         * @return this builder
         */
        public Builder queryParameters(Map<String, String> parameters) {
            this.queryParameters.putAll(parameters);
            return this;
        }

        /**
         * Adds a path parameter.
         *
         * @param name parameter name (e.g., "userId")
         * @param value parameter value (e.g., "123")
         * @return this builder
         */
        public Builder pathParameter(String name, String value) {
            this.pathParameters.put(name, value);
            return this;
        }

        /**
         * Adds multiple path parameters.
         *
         * @param parameters map of parameters
         * @return this builder
         */
        public Builder pathParameters(Map<String, String> parameters) {
            this.pathParameters.putAll(parameters);
            return this;
        }

        /**
         * Sets the Content-Type.
         *
         * @param contentType content type (e.g., "application/json")
         * @return this builder
         */
        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        /**
         * Sets the request body.
         *
         * @param body request body bytes
         * @return this builder
         */
        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        /**
         * Sets the request body from a string.
         *
         * @param body request body as UTF-8 string
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        /**
         * Sets the client address.
         *
         * @param clientAddress client IP address
         * @return this builder
         */
        public Builder clientAddress(String clientAddress) {
            this.clientAddress = clientAddress;
            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param timestamp request timestamp
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Builds the GatewayRequest instance.
         *
         * @return new GatewayRequest
         * @throws NullPointerException if method or path is null
         */
        public GatewayRequest build() {
            return new GatewayRequest(this);
        }
    }

    @Override
    public String toString() {
        return "GatewayRequest{" +
                "method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", contentType='" + contentType + '\'' +
                ", bodySize=" + body.length +
                ", clientAddress='" + clientAddress + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}