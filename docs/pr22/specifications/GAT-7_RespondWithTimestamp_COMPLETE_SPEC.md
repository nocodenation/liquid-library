# GAT-7: RespondWithTimestamp Example Processor - COMPLETE SPECIFICATION

**Generated:** 2026-01-06
**Purpose:** Demonstrate synchronous EndpointHandler usage
**File Location:** `liquid-library/src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/RespondWithTimestamp.java`

---

## Complete Implementation

```java
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

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Example processor demonstrating synchronous HTTP response using EndpointHandler.
 *
 * <p>This processor shows how to use the {@link EndpointHandler} interface to provide
 * immediate, synchronous responses to HTTP requests without queueing. Unlike
 * {@link ReceiveFromNodeJSApp} which queues requests for asynchronous processing,
 * this processor handles requests directly and returns a response immediately.</p>
 *
 * <p><b>Use Case:</b> Simple GET endpoints that return computed data without FlowFile
 * processing, such as health checks, status endpoints, or timestamp services.</p>
 *
 * <p><b>Example Response:</b></p>
 * <pre>{
 *   "timestamp": "2026-01-06T10:30:45.123Z",
 *   "epochMillis": 1704534645123,
 *   "formatted": "2026-01-06 10:30:45 UTC",
 *   "endpoint": "/api/time"
 * }</pre>
 *
 * <p><b>Comparison with ReceiveFromNodeJSApp:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>Feature</th>
 *     <th>RespondWithTimestamp</th>
 *     <th>ReceiveFromNodeJSApp</th>
 *   </tr>
 *   <tr>
 *     <td>Response Time</td>
 *     <td>Immediate (synchronous)</td>
 *     <td>After FlowFile processing (asynchronous)</td>
 *   </tr>
 *   <tr>
 *     <td>FlowFile Creation</td>
 *     <td>None</td>
 *     <td>One per request</td>
 *   </tr>
 *   <tr>
 *     <td>Use Case</td>
 *     <td>Simple computed responses</td>
 *     <td>Complex request processing</td>
 *   </tr>
 *   <tr>
 *     <td>Handler Type</td>
 *     <td>EndpointHandler (Java only)</td>
 *     <td>Queue-based (Java/Python)</td>
 *   </tr>
 * </table>
 *
 * @since 1.0.0
 * @see ReceiveFromNodeJSApp
 * @see EndpointHandler
 */
@Tags({"nocodenation", "nodejs", "http", "gateway", "example", "timestamp", "synchronous"})
@CapabilityDescription("Example processor that demonstrates synchronous HTTP response via EndpointHandler. " +
        "Returns current timestamp in JSON format with 200 OK status. Useful as a reference implementation " +
        "for building custom synchronous endpoints.")
@SeeAlso({ReceiveFromNodeJSApp.class})
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
public class RespondWithTimestamp extends AbstractProcessor {

    // ========== PROPERTY DESCRIPTORS ==========

    public static final PropertyDescriptor GATEWAY_SERVICE = new PropertyDescriptor.Builder()
            .name("Gateway Service")
            .description("The NodeJSAppAPIGateway controller service to register the endpoint with")
            .required(true)
            .identifiesControllerService(NodeJSAppAPIGateway.class)
            .build();

    public static final PropertyDescriptor ENDPOINT_PATTERN = new PropertyDescriptor.Builder()
            .name("Endpoint Pattern")
            .description("The endpoint pattern to register (e.g., /api/time, /health/timestamp). " +
                    "This endpoint will respond with the current timestamp in JSON format.")
            .required(true)
            .defaultValue("/api/time")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor INCLUDE_FORMATTED = new PropertyDescriptor.Builder()
            .name("Include Formatted Timestamp")
            .description("Include a human-readable formatted timestamp in the response (in addition to ISO-8601)")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    public static final PropertyDescriptor TIME_ZONE = new PropertyDescriptor.Builder()
            .name("Time Zone")
            .description("Time zone to use for formatted timestamp (e.g., UTC, America/New_York, Europe/London)")
            .required(true)
            .defaultValue("UTC")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    // ========== RELATIONSHIPS ==========

    // This processor has no relationships since it doesn't produce FlowFiles

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;
    private static final Set<Relationship> RELATIONSHIPS;

    static {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(GATEWAY_SERVICE);
        props.add(ENDPOINT_PATTERN);
        props.add(INCLUDE_FORMATTED);
        props.add(TIME_ZONE);
        PROPERTY_DESCRIPTORS = Collections.unmodifiableList(props);

        RELATIONSHIPS = Collections.emptySet();
    }

    // ========== INSTANCE VARIABLES ==========

    private NodeJSAppAPIGateway gateway;
    private String endpointPattern;
    private boolean includeFormatted;
    private ZoneId timeZone;
    private DateTimeFormatter formatter;

    // ========== LIFECYCLE METHODS ==========

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @OnScheduled
    public void onScheduled(ProcessContext context) throws EndpointAlreadyRegisteredException {
        this.gateway = context.getProperty(GATEWAY_SERVICE)
                .asControllerService(NodeJSAppAPIGateway.class);

        this.endpointPattern = context.getProperty(ENDPOINT_PATTERN)
                .evaluateAttributeExpressions()
                .getValue();

        this.includeFormatted = context.getProperty(INCLUDE_FORMATTED).asBoolean();

        String timeZoneId = context.getProperty(TIME_ZONE).getValue();
        try {
            this.timeZone = ZoneId.of(timeZoneId);
            this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                    .withZone(timeZone);
        } catch (Exception e) {
            getLogger().error("Invalid time zone '{}', falling back to UTC: {}", timeZoneId, e.getMessage());
            this.timeZone = ZoneId.of("UTC");
            this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                    .withZone(ZoneId.of("UTC"));
        }

        // Register endpoint with synchronous handler
        try {
            gateway.registerEndpoint(endpointPattern, this::handleTimestampRequest);
            getLogger().info("Registered synchronous timestamp endpoint '{}' with gateway on port {}",
                    endpointPattern, gateway.getGatewayPort());
        } catch (EndpointAlreadyRegisteredException e) {
            getLogger().error("Failed to register endpoint '{}': {}", endpointPattern, e.getMessage());
            throw e;
        }
    }

    @OnStopped
    public void onStopped() {
        if (gateway != null && endpointPattern != null) {
            gateway.unregisterEndpoint(endpointPattern);
            getLogger().info("Unregistered timestamp endpoint '{}'", endpointPattern);
        }
    }

    // ========== REQUEST HANDLER ==========

    /**
     * Handles incoming timestamp requests and returns JSON response.
     *
     * <p>This method is called directly by the gateway servlet when a request
     * arrives. It executes synchronously in the servlet thread, so it should
     * complete quickly to avoid blocking the HTTP server.</p>
     *
     * @param request the incoming HTTP request
     * @return GatewayResponse with timestamp data
     */
    private GatewayResponse handleTimestampRequest(GatewayRequest request) {
        try {
            Instant now = Instant.now();

            // Build JSON response
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"timestamp\": \"").append(now.toString()).append("\",\n");
            json.append("  \"epochMillis\": ").append(now.toEpochMilli()).append(",\n");

            if (includeFormatted) {
                String formattedTime = formatter.format(now);
                json.append("  \"formatted\": \"").append(formattedTime).append("\",\n");
            }

            json.append("  \"endpoint\": \"").append(request.getPath()).append("\",\n");
            json.append("  \"method\": \"").append(request.getMethod()).append("\"\n");
            json.append("}");

            // Return successful response with JSON content type
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            getLogger().debug("Processed timestamp request for endpoint '{}' from {}",
                    request.getPath(), request.getClientAddress());

            return new GatewayResponse(200, json.toString(), headers);

        } catch (Exception e) {
            getLogger().error("Error generating timestamp response: {}", e.getMessage(), e);

            // Return error response
            String errorJson = String.format(
                    "{\"error\": \"Failed to generate timestamp\", \"message\": \"%s\"}",
                    e.getMessage().replace("\"", "\\\"")
            );

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            return new GatewayResponse(500, errorJson, headers);
        }
    }

    // ========== PROCESS METHOD ==========

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        // This processor doesn't produce FlowFiles, so onTrigger does nothing.
        // All work happens in the EndpointHandler callback (handleTimestampRequest).
        // We yield to avoid spinning in a tight loop.
        context.yield();
    }
}
```

---

## Implementation Notes

### Key Features Demonstrated:

1. **EndpointHandler Pattern**: Shows how to register a lambda function as a synchronous handler
2. **No FlowFile Creation**: Demonstrates processors that respond directly without NiFi flow processing
3. **Error Handling**: Proper try-catch with JSON error responses
4. **Configuration**: Multiple properties for customization
5. **Lifecycle Management**: Proper registration/unregistration in @OnScheduled/@OnStopped
6. **Logging**: Appropriate info/debug/error logging
7. **Time Zone Handling**: Shows working with Java 8+ time APIs

### Testing Instructions:

Once deployed, test with:
```bash
# Basic request
curl http://localhost:5050/api/time

# Expected response:
{
  "timestamp": "2026-01-06T10:30:45.123Z",
  "epochMillis": 1704534645123,
  "formatted": "2026-01-06 10:30:45 UTC",
  "endpoint": "/api/time",
  "method": "GET"
}
```

### Educational Value:

This processor serves as a **reference implementation** for developers who want to:
- Build custom synchronous endpoints without FlowFile processing
- Understand the difference between EndpointHandler (Java) and queue-based (Python) approaches
- Learn proper endpoint registration and lifecycle management
- See JSON response generation patterns

---

## File Location

**Path:** `liquid-library/src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/RespondWithTimestamp.java`

**Same directory as:** `ReceiveFromNodeJSApp.java`

---

*Complete specification ready for implementation*