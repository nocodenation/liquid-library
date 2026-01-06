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
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Processor that receives HTTP requests from Node.js applications via the NodeJSAppAPIGateway.
 *
 * <p>This processor registers one or more endpoint patterns with the gateway and handles incoming
 * requests directly (zero-latency Java handler). Each request becomes a FlowFile with
 * HTTP details as attributes and body as content.</p>
 *
 * <p><b>Endpoint Pattern Examples:</b></p>
 * <ul>
 *   <li><code>/api/events</code> - Single static path</li>
 *   <li><code>/users/:userId</code> - Single path with parameter</li>
 *   <li><code>/api/users/:userId, /api/products/:productId</code> - Multiple comma-separated patterns</li>
 * </ul>
 *
 * <p><b>FlowFile Attributes:</b></p>
 * <ul>
 *   <li><code>http.method</code> - HTTP method (GET, POST, etc.)</li>
 *   <li><code>http.path</code> - Actual request path (e.g., /api/users/123)</li>
 *   <li><code>http.query.*</code> - Query parameters</li>
 *   <li><code>http.path.*</code> - Path parameters extracted from pattern (e.g., http.path.userId=123)</li>
 *   <li><code>http.header.*</code> - HTTP headers</li>
 *   <li><code>http.client.address</code> - Client IP address</li>
 *   <li><code>http.timestamp</code> - Request timestamp</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Tags({"nodejs", "http", "gateway", "api", "rest"})
@CapabilityDescription("Receives HTTP requests from Node.js applications via the NodeJSAppAPIGateway controller service. " +
        "Registers an endpoint pattern and creates FlowFiles for incoming requests with HTTP details as attributes.")
@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@WritesAttributes({
        @WritesAttribute(attribute = "http.method", description = "HTTP method (GET, POST, PUT, DELETE, etc.)"),
        @WritesAttribute(attribute = "http.path", description = "Request path"),
        @WritesAttribute(attribute = "http.query.*", description = "Query parameters (e.g., http.query.id)"),
        @WritesAttribute(attribute = "http.path.*", description = "Path parameters (e.g., http.path.userId)"),
        @WritesAttribute(attribute = "http.header.*", description = "HTTP headers (e.g., http.header.content-type)"),
        @WritesAttribute(attribute = "http.client.address", description = "Client IP address"),
        @WritesAttribute(attribute = "http.timestamp", description = "Request timestamp (ISO-8601)")
})
public class ReceiveFromNodeJSApp extends AbstractProcessor {

    public static final PropertyDescriptor GATEWAY_SERVICE = new PropertyDescriptor.Builder()
            .name("Gateway Service")
            .description("The NodeJSAppAPIGateway controller service to use")
            .required(true)
            .identifiesControllerService(NodeJSAppAPIGateway.class)
            .build();

    public static final PropertyDescriptor ENDPOINT_PATTERN = new PropertyDescriptor.Builder()
            .name("Endpoint Pattern")
            .description("One or more endpoint patterns to register, comma-separated (e.g., /api/events or /api/users/:userId, /api/products/:productId)")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor RESPONSE_STATUS_CODE = new PropertyDescriptor.Builder()
            .name("Response Status Code")
            .description("HTTP status code to return (default: 202 Accepted)")
            .required(true)
            .defaultValue("202")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor RESPONSE_BODY = new PropertyDescriptor.Builder()
            .name("Response Body")
            .description("Optional response body to send back to the client")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Successfully received requests")
            .build();

    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Failed to process requests")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;
    private static final Set<Relationship> RELATIONSHIPS;

    static {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(GATEWAY_SERVICE);
        props.add(ENDPOINT_PATTERN);
        props.add(RESPONSE_STATUS_CODE);
        props.add(RESPONSE_BODY);
        PROPERTY_DESCRIPTORS = Collections.unmodifiableList(props);

        Set<Relationship> rels = new HashSet<>();
        rels.add(REL_SUCCESS);
        rels.add(REL_FAILURE);
        RELATIONSHIPS = Collections.unmodifiableSet(rels);
    }

    private NodeJSAppAPIGateway gateway;
    private List<String> endpointPatterns;
    private int responseStatusCode;
    private String responseBody;
    private final AtomicLong requestCounter = new AtomicLong(0);

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
        this.gateway = context.getProperty(GATEWAY_SERVICE).asControllerService(NodeJSAppAPIGateway.class);
        String patternsValue = context.getProperty(ENDPOINT_PATTERN).evaluateAttributeExpressions().getValue();
        this.responseStatusCode = context.getProperty(RESPONSE_STATUS_CODE).asInteger();
        this.responseBody = context.getProperty(RESPONSE_BODY).getValue();

        // Parse comma-separated patterns
        this.endpointPatterns = new ArrayList<>();
        for (String pattern : patternsValue.split(",")) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) {
                endpointPatterns.add(trimmed);
            }
        }

        // Register all endpoint patterns with null handler and custom response configuration
        for (String pattern : endpointPatterns) {
            gateway.registerEndpoint(pattern, null, responseStatusCode, responseBody);
            getLogger().info("Registered endpoint pattern '{}' with gateway on port {} (status: {}, body: {})",
                    pattern, gateway.getGatewayPort(), responseStatusCode,
                    responseBody != null ? "configured" : "default");
        }
    }

    @OnStopped
    public void onStopped() {
        if (gateway != null && endpointPatterns != null) {
            for (String pattern : endpointPatterns) {
                gateway.unregisterEndpoint(pattern);
                getLogger().info("Unregistered endpoint pattern '{}'", pattern);
            }
        }
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        // Poll all registered endpoint queues round-robin
        GatewayRequest request = null;
        String matchedPattern = null;

        for (String pattern : endpointPatterns) {
            java.util.Queue<GatewayRequest> queue = gateway.getEndpointQueue(pattern);

            if (queue == null) {
                getLogger().error("No queue found for endpoint pattern '{}'", pattern);
                continue;
            }

            // Cast to BlockingQueue for poll with timeout support
            if (!(queue instanceof java.util.concurrent.BlockingQueue)) {
                getLogger().error("Queue for pattern '{}' does not support blocking operations", pattern);
                continue;
            }

            java.util.concurrent.BlockingQueue<GatewayRequest> blockingQueue =
                (java.util.concurrent.BlockingQueue<GatewayRequest>) queue;

            // Poll with short timeout
            try {
                request = blockingQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (request != null) {
                    matchedPattern = pattern;
                    break; // Found a request, process it
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                getLogger().warn("Interrupted while polling for requests");
                return;
            }
        }

        if (request == null) {
            // No request available from any queue, yield
            context.yield();
            return;
        }

        try {
            long requestId = requestCounter.incrementAndGet();

            getLogger().debug("Processing request #{} for pattern '{}': {} {}",
                    requestId, matchedPattern, request.getMethod(), request.getPath());

            // Create FlowFile from request
            FlowFile flowFile = createFlowFileFromRequest(session, request);

            // Transfer to success
            session.transfer(flowFile, REL_SUCCESS);
            session.commitAsync();

            getLogger().info("Successfully processed request #{} for pattern '{}': {} {}",
                    requestId, matchedPattern, request.getMethod(), request.getPath());

        } catch (Exception e) {
            getLogger().error("Failed to process request: {}", e.getMessage(), e);
            session.rollback();
        }
    }

    /**
     * Creates a FlowFile from a gateway request.
     * This should be called from a proper NiFi session context.
     */
    private FlowFile createFlowFileFromRequest(ProcessSession session, GatewayRequest request) {
        FlowFile flowFile = session.create();

        // Write request body as FlowFile content
        if (request.getBody() != null && request.getBody().length > 0) {
            flowFile = session.write(flowFile, out -> out.write(request.getBody()));
        }

        // Add HTTP attributes
        Map<String, String> attributes = new HashMap<>();
        attributes.put("http.method", request.getMethod());
        attributes.put("http.path", request.getPath());
        attributes.put("http.client.address", request.getClientAddress());
        attributes.put("http.timestamp", request.getTimestamp().toString());

        // Add query parameters
        for (Map.Entry<String, String> param : request.getQueryParameters().entrySet()) {
            attributes.put("http.query." + param.getKey(), param.getValue());
        }

        // Add path parameters
        for (Map.Entry<String, String> param : request.getPathParameters().entrySet()) {
            attributes.put("http.path." + param.getKey(), param.getValue());
        }

        // Add headers
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            attributes.put("http.header." + header.getKey().toLowerCase(), header.getValue());
        }

        flowFile = session.putAllAttributes(flowFile, attributes);

        return flowFile;
    }
}