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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing and converting path patterns.
 *
 * <p>Centralizes the logic for handling NiFi-style path patterns (:paramName)
 * and converting them to various formats:</p>
 * <ul>
 *   <li>Regex patterns for matching ({@link #convertToRegex(String)})</li>
 *   <li>OpenAPI format for documentation ({@link #convertToOpenAPI(String)})</li>
 *   <li>Parameter extraction ({@link #extractParameterNames(String)})</li>
 * </ul>
 *
 * <p>This eliminates duplicate pattern parsing logic across EndpointMatcher
 * and OpenAPIGenerator.</p>
 *
 * @since 1.0.0
 */
public class PathPatternParser {

    /**
     * Pattern to match path parameters in NiFi format: :paramName
     * Matches a colon followed by a valid identifier (letter/underscore, then alphanumeric/underscore)
     */
    private static final Pattern PARAM_PATTERN = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");

    /**
     * Private constructor - this is a utility class with only static methods.
     */
    private PathPatternParser() {
        // Utility class
    }

    /**
     * Extracts parameter names from a NiFi path pattern.
     *
     * <p>Example:</p>
     * <pre>
     * extractParameterNames("/users/:userId/posts/:postId")
     * // Returns: ["userId", "postId"]
     * </pre>
     *
     * @param pattern the NiFi path pattern (e.g., "/users/:userId")
     * @return list of parameter names in order of appearance (empty list if no parameters)
     * @throws NullPointerException if pattern is null
     */
    public static List<String> extractParameterNames(String pattern) {
        if (pattern == null) {
            throw new NullPointerException("Pattern cannot be null");
        }

        List<String> params = new ArrayList<>();
        Matcher matcher = PARAM_PATTERN.matcher(pattern);

        while (matcher.find()) {
            params.add(matcher.group(1));
        }

        return params;
    }

    /**
     * Converts a NiFi path pattern to a regex pattern for matching.
     *
     * <p>Converts :paramName to ([^/]+) to match any non-slash characters.</p>
     *
     * <p>Example:</p>
     * <pre>
     * convertToRegex("/users/:userId/posts/:postId")
     * // Returns: "^/users/([^/]+)/posts/([^/]+)$"
     * </pre>
     *
     * @param pattern the NiFi path pattern
     * @return regex pattern string with anchors (^ and $)
     * @throws NullPointerException if pattern is null
     */
    public static String convertToRegex(String pattern) {
        if (pattern == null) {
            throw new NullPointerException("Pattern cannot be null");
        }

        // Replace :paramName with ([^/]+) to match path segments
        String regexPattern = pattern.replaceAll(":([a-zA-Z_][a-zA-Z0-9_]*)", "([^/]+)");

        // Add anchors to ensure exact match
        return "^" + regexPattern + "$";
    }

    /**
     * Converts a NiFi path pattern to OpenAPI path format.
     *
     * <p>Converts :paramName to {paramName} for OpenAPI 3.0 specification.</p>
     *
     * <p>Example:</p>
     * <pre>
     * convertToOpenAPI("/users/:userId/posts/:postId")
     * // Returns: "/users/{userId}/posts/{postId}"
     * </pre>
     *
     * @param pattern the NiFi path pattern
     * @return OpenAPI path format string
     * @throws NullPointerException if pattern is null
     */
    public static String convertToOpenAPI(String pattern) {
        if (pattern == null) {
            throw new NullPointerException("Pattern cannot be null");
        }

        Matcher matcher = PARAM_PATTERN.matcher(pattern);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1);
            matcher.appendReplacement(result, "{" + paramName + "}");
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Validates whether a string is a valid NiFi path pattern.
     *
     * <p>A valid pattern:</p>
     * <ul>
     *   <li>Must start with /</li>
     *   <li>Parameter names must be valid Java identifiers</li>
     *   <li>Cannot have consecutive path separators (//)</li>
     * </ul>
     *
     * @param pattern the pattern to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValid(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }

        // Must start with /
        if (!pattern.startsWith("/")) {
            return false;
        }

        // Cannot have consecutive slashes
        if (pattern.contains("//")) {
            return false;
        }

        // All parameters must match the pattern
        Matcher matcher = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)").matcher(pattern);
        while (matcher.find()) {
            String paramName = matcher.group(1);
            // Additional validation could go here
            if (paramName.isEmpty()) {
                return false;
            }
        }

        return true;
    }
}
