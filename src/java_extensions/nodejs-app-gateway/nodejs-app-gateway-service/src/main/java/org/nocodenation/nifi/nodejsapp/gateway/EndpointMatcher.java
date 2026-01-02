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

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for matching request paths against endpoint patterns.
 *
 * <p>Supports path parameters in the format: /users/:id/posts/:postId</p>
 * <p>Converts patterns to regex and extracts parameter values from matched paths.</p>
 *
 * @since 1.0.0
 */
public class EndpointMatcher {

    private final String pattern;
    private final Pattern regex;
    private final String[] paramNames;

    /**
     * Creates a new matcher for the given endpoint pattern.
     *
     * @param pattern the endpoint pattern (e.g., "/users/:id/posts/:postId")
     */
    public EndpointMatcher(String pattern) {
        this.pattern = pattern;

        // Extract parameter names
        java.util.List<String> params = new java.util.ArrayList<>();
        String regexPattern = pattern;

        // Find all :paramName in the pattern
        Pattern paramPattern = Pattern.compile(":([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher paramMatcher = paramPattern.matcher(pattern);

        while (paramMatcher.find()) {
            params.add(paramMatcher.group(1));
        }

        this.paramNames = params.toArray(new String[0]);

        // Convert pattern to regex
        // Replace :paramName with ([^/]+) to match path segments
        regexPattern = regexPattern.replaceAll(":([a-zA-Z_][a-zA-Z0-9_]*)", "([^/]+)");

        // Escape special regex characters except what we've already replaced
        regexPattern = "^" + regexPattern + "$";

        this.regex = Pattern.compile(regexPattern);
    }

    /**
     * Matches a request path against this endpoint pattern.
     *
     * @param path the request path to match
     * @return MatchResult with match status and extracted parameters, or null if no match
     */
    public MatchResult match(String path) {
        Matcher matcher = regex.matcher(path);

        if (!matcher.matches()) {
            return null;
        }

        Map<String, String> pathParams = new HashMap<>();

        // Extract parameter values
        for (int i = 0; i < paramNames.length; i++) {
            String paramValue = matcher.group(i + 1);
            pathParams.put(paramNames[i], paramValue);
        }

        return new MatchResult(pattern, pathParams);
    }

    /**
     * Gets the original pattern.
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Result of a pattern match operation.
     */
    public static class MatchResult {
        private final String pattern;
        private final Map<String, String> pathParameters;

        public MatchResult(String pattern, Map<String, String> pathParameters) {
            this.pattern = pattern;
            this.pathParameters = pathParameters;
        }

        public String getPattern() {
            return pattern;
        }

        public Map<String, String> getPathParameters() {
            return pathParameters;
        }
    }
}