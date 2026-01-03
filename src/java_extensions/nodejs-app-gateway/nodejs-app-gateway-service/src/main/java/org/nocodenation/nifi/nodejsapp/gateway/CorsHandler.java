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

    /**
     * Creates a new CorsHandler.
     *
     * @param enabled whether CORS is enabled
     */
    public CorsHandler(boolean enabled) {
        this.enabled = enabled;
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
     * Adds CORS headers to the response.
     *
     * <p>Headers added:</p>
     * <ul>
     *   <li>Access-Control-Allow-Origin: * (allows all origins)</li>
     *   <li>Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS</li>
     *   <li>Access-Control-Allow-Headers: Standard headers plus custom X- headers</li>
     *   <li>Access-Control-Max-Age: 3600 (cache preflight for 1 hour)</li>
     * </ul>
     *
     * @param resp the HTTP response
     */
    private void addCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, X-Event-Id, X-Timestamp, X-Stage");
        resp.setHeader("Access-Control-Max-Age", "3600");
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
