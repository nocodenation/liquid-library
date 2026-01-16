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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handles Cross-Origin Resource Sharing (CORS) headers and preflight requests.
 *
 * <p>This class encapsulates all CORS-related logic, providing:</p>
 * <ul>
 *   <li>Preflight request handling (OPTIONS method)</li>
 *   <li>CORS headers for actual requests</li>
 *   <li>Enable/disable functionality</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is immutable and thread-safe.</p>
 *
 * @since 1.0.0
 */
public class CorsHandler {

    private final boolean enabled;
    private final String allowedOrigins;
    private final String allowedMethods;
    private final String allowedHeaders;
    private final String maxAge;

    /**
     * Creates a new CorsHandler with default permissive settings.
     *
     * @param enabled whether CORS is enabled
     * @deprecated Use {@link #CorsHandler(boolean, String, String, String, String)} for production deployments
     */
    @Deprecated
    public CorsHandler(boolean enabled) {
        this(enabled, "*", "GET, POST, PUT, DELETE, PATCH, OPTIONS",
                "Content-Type, Authorization, X-Requested-With, X-Event-Id, X-Timestamp, X-Stage",
                "3600");
    }

    /**
     * Creates a new CorsHandler with configurable CORS headers.
     *
     * @param enabled whether CORS is enabled
     * @param allowedOrigins comma-separated list of allowed origins (e.g., "https://example.com,https://app.example.com" or "*" for all)
     * @param allowedMethods comma-separated list of allowed HTTP methods
     * @param allowedHeaders comma-separated list of allowed request headers
     * @param maxAge preflight cache duration in seconds
     */
    public CorsHandler(boolean enabled, String allowedOrigins, String allowedMethods,
                      String allowedHeaders, String maxAge) {
        this.enabled = enabled;
        this.allowedOrigins = allowedOrigins != null ? allowedOrigins : "*";
        this.allowedMethods = allowedMethods != null ? allowedMethods : "GET, POST, PUT, DELETE, PATCH, OPTIONS";
        this.allowedHeaders = allowedHeaders != null ? allowedHeaders : "Content-Type, Authorization";
        this.maxAge = maxAge != null ? maxAge : "3600";
    }

    /**
     * Checks if this is a CORS preflight request and handles it if so.
     *
     * <p>A preflight request is an OPTIONS request sent by browsers before the actual
     * request to check if CORS is allowed.</p>
     *
     * @param req the HTTP request
     * @param resp the HTTP response
     * @return true if this was a preflight request and has been handled, false otherwise
     */
    public boolean handlePreflightIfNeeded(HttpServletRequest req, HttpServletResponse resp) {
        if (!enabled) {
            return false;
        }

        if ("OPTIONS".equals(req.getMethod())) {
            addCorsHeaders(resp);
            resp.setStatus(HttpServletResponse.SC_OK);
            return true;
        }

        return false;
    }

    /**
     * Adds CORS headers to the response if CORS is enabled.
     *
     * <p>This method should be called for all non-preflight requests when CORS is enabled.</p>
     *
     * @param resp the HTTP response to add headers to
     */
    public void addCorsHeadersIfEnabled(HttpServletResponse resp) {
        if (enabled) {
            addCorsHeaders(resp);
        }
    }

    /**
     * Adds CORS headers to the response using configured values.
     *
     * <p>Headers added:</p>
     * <ul>
     *   <li>Access-Control-Allow-Origin: configured allowed origins</li>
     *   <li>Access-Control-Allow-Methods: configured allowed methods</li>
     *   <li>Access-Control-Allow-Headers: configured allowed headers</li>
     *   <li>Access-Control-Max-Age: configured max age</li>
     * </ul>
     *
     * @param resp the HTTP response
     */
    private void addCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", allowedOrigins);
        resp.setHeader("Access-Control-Allow-Methods", allowedMethods);
        resp.setHeader("Access-Control-Allow-Headers", allowedHeaders);
        resp.setHeader("Access-Control-Max-Age", maxAge);
    }

    /**
     * Checks if CORS is enabled.
     *
     * @return true if CORS is enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
}
