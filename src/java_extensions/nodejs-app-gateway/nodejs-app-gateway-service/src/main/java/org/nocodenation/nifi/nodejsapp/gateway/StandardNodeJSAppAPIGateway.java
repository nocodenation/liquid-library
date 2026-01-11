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

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextService;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import javax.net.ssl.SSLContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Standard implementation of NodeJSAppAPIGateway controller service.
 *
 * <p>This service provides an HTTP gateway for Node.js applications to send requests
 * into NiFi flows. It manages an embedded Jetty server with three servlet endpoints:</p>
 * <ul>
 *   <li>Public API - Handles incoming requests from Node.js apps</li>
 *   <li>Internal Polling API - Allows Python processors to poll for requests</li>
 *   <li>Metrics API - Exposes endpoint statistics</li>
 * </ul>
 *
 * @since 1.0.0
 */
@Tags({"nocodenation", "nodejs", "http", "gateway", "api"})
@CapabilityDescription("Provides HTTP gateway for Node.js applications to send requests into NiFi flows")
public class StandardNodeJSAppAPIGateway extends AbstractControllerService implements NodeJSAppAPIGateway {

    public static final PropertyDescriptor GATEWAY_HOST = new PropertyDescriptor.Builder()
            .name("Gateway Host")
            .description("Host address to bind the HTTP gateway server to. Use 0.0.0.0 to listen on all interfaces, or 127.0.0.1 for localhost only.")
            .required(true)
            .defaultValue("0.0.0.0")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor GATEWAY_PORT = new PropertyDescriptor.Builder()
            .name("Gateway Port")
            .description("Port number for the HTTP gateway server")
            .required(true)
            .defaultValue("5050")
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("SSL Context Service to use for HTTPS. If not specified, HTTP will be used. " +
                    "IMPORTANT: Using HTTP (without SSL) is NOT recommended for production deployments as data will be transmitted in plaintext.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    public static final PropertyDescriptor MAX_QUEUE_SIZE = new PropertyDescriptor.Builder()
            .name("Maximum Queue Size")
            .description("Maximum number of requests that can be queued per endpoint before rejecting with 503")
            .required(true)
            .defaultValue("100")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor MAX_REQUEST_SIZE = new PropertyDescriptor.Builder()
            .name("Maximum Request Size")
            .description("Maximum size of request body in bytes")
            .required(true)
            .defaultValue("10485760")  // 10 MB
            .addValidator(StandardValidators.POSITIVE_LONG_VALIDATOR)
            .build();

    public static final PropertyDescriptor ENABLE_CORS = new PropertyDescriptor.Builder()
            .name("Enable CORS")
            .description("Enable Cross-Origin Resource Sharing (CORS) for browser-based clients")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    public static final PropertyDescriptor CORS_ALLOWED_ORIGINS = new PropertyDescriptor.Builder()
            .name("CORS Allowed Origins")
            .description("Comma-separated list of allowed origins for CORS requests. Use '*' to allow all origins (NOT recommended for production). " +
                    "Examples: 'https://example.com', 'https://app.example.com,https://admin.example.com'")
            .required(false)
            .defaultValue("*")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .dependsOn(ENABLE_CORS, "true")
            .build();

    public static final PropertyDescriptor CORS_ALLOWED_METHODS = new PropertyDescriptor.Builder()
            .name("CORS Allowed Methods")
            .description("Comma-separated list of HTTP methods allowed for CORS requests")
            .required(false)
            .defaultValue("GET, POST, PUT, DELETE, PATCH, OPTIONS")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .dependsOn(ENABLE_CORS, "true")
            .build();

    public static final PropertyDescriptor CORS_ALLOWED_HEADERS = new PropertyDescriptor.Builder()
            .name("CORS Allowed Headers")
            .description("Comma-separated list of HTTP headers allowed in CORS requests")
            .required(false)
            .defaultValue("Content-Type, Authorization, X-Requested-With, X-Event-Id, X-Timestamp, X-Stage")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .dependsOn(ENABLE_CORS, "true")
            .build();

    public static final PropertyDescriptor CORS_MAX_AGE = new PropertyDescriptor.Builder()
            .name("CORS Max Age")
            .description("How long (in seconds) browsers should cache CORS preflight responses")
            .required(false)
            .defaultValue("3600")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .dependsOn(ENABLE_CORS, "true")
            .build();

    public static final PropertyDescriptor SWAGGER_ENABLED = new PropertyDescriptor.Builder()
            .name("Enable Swagger UI")
            .description("Enable auto-generated API documentation via Swagger UI")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    public static final PropertyDescriptor SWAGGER_PATH = new PropertyDescriptor.Builder()
            .name("Swagger UI Path")
            .description("URL path for accessing Swagger UI (e.g., /swagger)")
            .required(true)
            .defaultValue("/swagger")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .dependsOn(SWAGGER_ENABLED, "true")
            .build();

    public static final PropertyDescriptor OPENAPI_PATH = new PropertyDescriptor.Builder()
            .name("OpenAPI Spec Path")
            .description("URL path for accessing OpenAPI JSON specification (e.g., /openapi.json)")
            .required(true)
            .defaultValue("/openapi.json")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .dependsOn(SWAGGER_ENABLED, "true")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;

    static {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(GATEWAY_HOST);
        props.add(GATEWAY_PORT);
        props.add(SSL_CONTEXT_SERVICE);
        props.add(MAX_QUEUE_SIZE);
        props.add(MAX_REQUEST_SIZE);
        props.add(ENABLE_CORS);
        props.add(CORS_ALLOWED_ORIGINS);
        props.add(CORS_ALLOWED_METHODS);
        props.add(CORS_ALLOWED_HEADERS);
        props.add(CORS_MAX_AGE);
        props.add(SWAGGER_ENABLED);
        props.add(SWAGGER_PATH);
        props.add(OPENAPI_PATH);
        PROPERTY_DESCRIPTORS = Collections.unmodifiableList(props);
    }

    // Jetty server
    private Server server;
    private String gatewayHost;
    private int gatewayPort;

    // Configuration
    private int maxQueueSize;
    private long maxRequestSize;
    private boolean enableCors;
    private String corsAllowedOrigins;
    private String corsAllowedMethods;
    private String corsAllowedHeaders;
    private String corsMaxAge;
    private SSLContextService sslContextService;

    // Endpoint registry - maps pattern to handler and queue
    private final Map<String, EndpointRegistration> endpointRegistry = new ConcurrentHashMap<>();

    // Metrics tracking per endpoint
    private final Map<String, EndpointMetrics> metricsRegistry = new ConcurrentHashMap<>();

    // Matcher cache - avoids recreating EndpointMatcher on every request
    private final Map<String, EndpointMatcher> matcherCache = new ConcurrentHashMap<>();

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    // Swagger configuration
    private boolean swaggerEnabled;
    private String swaggerPath;
    private String openapiPath;
    private OpenAPIGenerator openapiGenerator;

    @OnEnabled
    public void onEnabled(ConfigurationContext context) throws Exception {
        this.gatewayHost = context.getProperty(GATEWAY_HOST).evaluateAttributeExpressions().getValue();
        this.gatewayPort = context.getProperty(GATEWAY_PORT).evaluateAttributeExpressions().asInteger();
        this.maxQueueSize = context.getProperty(MAX_QUEUE_SIZE).asInteger();
        this.maxRequestSize = context.getProperty(MAX_REQUEST_SIZE).asLong();
        this.enableCors = context.getProperty(ENABLE_CORS).asBoolean();
        this.corsAllowedOrigins = context.getProperty(CORS_ALLOWED_ORIGINS).getValue();
        this.corsAllowedMethods = context.getProperty(CORS_ALLOWED_METHODS).getValue();
        this.corsAllowedHeaders = context.getProperty(CORS_ALLOWED_HEADERS).getValue();
        this.corsMaxAge = context.getProperty(CORS_MAX_AGE).getValue();
        this.swaggerEnabled = context.getProperty(SWAGGER_ENABLED).asBoolean();
        this.swaggerPath = context.getProperty(SWAGGER_PATH).getValue();
        this.openapiPath = context.getProperty(OPENAPI_PATH).getValue();
        this.sslContextService = context.getProperty(SSL_CONTEXT_SERVICE)
                .asControllerService(SSLContextService.class);

        startServer();
        getLogger().info("NodeJS App API Gateway started on {}:{}", gatewayHost, gatewayPort);
        if (swaggerEnabled) {
            getLogger().info("Swagger UI enabled at {}{}", getGatewayUrl(), swaggerPath);
            getLogger().info("OpenAPI spec available at {}{}", getGatewayUrl(), openapiPath);
        }
    }

    @OnDisabled
    public void onDisabled() throws Exception {
        stopServer();
        endpointRegistry.clear();
        metricsRegistry.clear();
        matcherCache.clear();
        getLogger().info("NodeJS App API Gateway stopped");
    }

    @Override
    public int getGatewayPort() {
        return gatewayPort;
    }

    @Override
    public String getGatewayUrl() {
        // Return localhost if bound to 0.0.0.0, otherwise use the configured host
        String host = "0.0.0.0".equals(gatewayHost) ? "localhost" : gatewayHost;
        String protocol = (sslContextService != null) ? "https" : "http";
        return protocol + "://" + host + ":" + gatewayPort;
    }

    @Override
    public void registerEndpoint(String pattern, EndpointHandler handler) throws EndpointAlreadyRegisteredException {
        registerEndpoint(pattern, handler, 202, null);
    }

    /**
     * Registers an endpoint with custom response configuration.
     *
     * @param pattern endpoint pattern (e.g., /api/users/:userId)
     * @param handler handler function (null for queue-based processing)
     * @param responseStatusCode HTTP status code to return when queuing (default: 202)
     * @param responseBody response body to return when queuing (null for default)
     * @throws EndpointAlreadyRegisteredException if pattern already registered
     */
    public void registerEndpoint(String pattern, EndpointHandler handler, int responseStatusCode, String responseBody)
            throws EndpointAlreadyRegisteredException {
        if (endpointRegistry.containsKey(pattern)) {
            throw new EndpointAlreadyRegisteredException("Endpoint pattern already registered: " + pattern);
        }

        LinkedBlockingQueue<GatewayRequest> queue = new LinkedBlockingQueue<>(maxQueueSize);
        EndpointRegistration registration = new EndpointRegistration(handler, queue, responseStatusCode, responseBody);
        endpointRegistry.put(pattern, registration);
        metricsRegistry.put(pattern, new EndpointMetrics());

        // Create and cache matcher for this pattern
        matcherCache.put(pattern, new EndpointMatcher(pattern));

        // Invalidate OpenAPI cache so new endpoint appears immediately in Swagger UI
        if (swaggerEnabled && openapiGenerator != null) {
            openapiGenerator.invalidateCache();
        }

        getLogger().info("Registered endpoint: {}", pattern);
    }

    @Override
    public void unregisterEndpoint(String pattern) {
        EndpointRegistration registration = endpointRegistry.remove(pattern);
        if (registration != null) {
            registration.queue.clear();
            metricsRegistry.remove(pattern);
            matcherCache.remove(pattern);

            // Invalidate OpenAPI cache so removed endpoint disappears immediately from Swagger UI
            if (swaggerEnabled && openapiGenerator != null) {
                openapiGenerator.invalidateCache();
            }

            getLogger().info("Unregistered endpoint: {}", pattern);
        }
    }

    @Override
    public List<String> getRegisteredEndpoints() {
        return new ArrayList<>(endpointRegistry.keySet());
    }

    @Override
    public EndpointMetrics getEndpointMetrics(String pattern) {
        return metricsRegistry.get(pattern);
    }

    /**
     * Records a request for the given endpoint pattern (for internal use by servlets).
     * This method encapsulates metrics updates to maintain proper ownership.
     */
    public void recordRequest(String pattern) {
        EndpointMetrics metrics = metricsRegistry.get(pattern);
        if (metrics != null) {
            metrics.recordRequest();
        }
    }

    /**
     * Records a successful request with latency (for internal use by servlets).
     */
    public void recordSuccess(String pattern, long latencyMs) {
        EndpointMetrics metrics = metricsRegistry.get(pattern);
        if (metrics != null) {
            metrics.recordSuccess(latencyMs);
        }
    }

    /**
     * Records a failed request (for internal use by servlets).
     */
    public void recordFailure(String pattern) {
        EndpointMetrics metrics = metricsRegistry.get(pattern);
        if (metrics != null) {
            metrics.recordFailure();
        }
    }

    /**
     * Records a queue full rejection (for internal use by servlets).
     */
    public void recordQueueFull(String pattern) {
        EndpointMetrics metrics = metricsRegistry.get(pattern);
        if (metrics != null) {
            metrics.recordQueueFull();
        }
    }

    /**
     * Updates the current queue size for an endpoint (for internal use by servlets).
     */
    public void updateQueueSize(String pattern, int size) {
        EndpointMetrics metrics = metricsRegistry.get(pattern);
        if (metrics != null) {
            metrics.updateQueueSize(size);
        }
    }

    /**
     * Gets the endpoint handler for a pattern (for internal use by servlets).
     */
    public EndpointHandler getEndpointHandler(String pattern) {
        EndpointRegistration registration = endpointRegistry.get(pattern);
        return registration != null ? registration.handler : null;
    }

    /**
     * Gets the request queue for a pattern (for internal use by servlets).
     * Returns Queue interface to avoid exposing concrete implementation.
     */
    public Queue<GatewayRequest> getEndpointQueue(String pattern) {
        EndpointRegistration registration = endpointRegistry.get(pattern);
        return registration != null ? registration.queue : null;
    }

    /**
     * Gets the cached EndpointMatcher for a pattern (for internal use by servlets).
     * This avoids recreating matchers and recompiling regex on every request.
     */
    public EndpointMatcher getEndpointMatcher(String pattern) {
        return matcherCache.get(pattern);
    }

    /**
     * Gets the endpoint registration for a pattern (for internal use by servlets).
     * This allows access to response configuration.
     */
    public EndpointRegistration getEndpointRegistration(String pattern) {
        return endpointRegistry.get(pattern);
    }

    /**
     * Gets configuration values for servlets.
     */
    public long getMaxRequestSize() {
        return maxRequestSize;
    }

    public boolean isEnableCors() {
        return enableCors;
    }

    public String getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public String getCorsAllowedMethods() {
        return corsAllowedMethods;
    }

    public String getCorsAllowedHeaders() {
        return corsAllowedHeaders;
    }

    public String getCorsMaxAge() {
        return corsMaxAge;
    }

    public Map<String, EndpointMetrics> getMetricsRegistry() {
        return Collections.unmodifiableMap(metricsRegistry);
    }

    public Map<String, EndpointRegistration> getEndpointRegistry() {
        return Collections.unmodifiableMap(endpointRegistry);
    }

    private void startServer() throws Exception {
        // Create server
        server = new Server();

        // Configure connector (HTTP or HTTPS based on SSL configuration)
        ServerConnector connector;

        if (sslContextService != null) {
            // HTTPS mode
            try {
                SSLContext sslContext = sslContextService.createContext();
                SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                sslContextFactory.setSslContext(sslContext);

                connector = new ServerConnector(server, sslContextFactory);
                getLogger().info("Gateway configured with HTTPS using SSL Context Service");
            } catch (Exception e) {
                getLogger().error("Failed to create SSL context, falling back to HTTP: {}", e.getMessage());
                throw new RuntimeException("SSL configuration failed: " + e.getMessage(), e);
            }
        } else {
            // HTTP mode
            connector = new ServerConnector(server);
            getLogger().warn("Gateway configured with HTTP only - SSL Context Service not specified. " +
                    "This is NOT recommended for production deployments as data will be transmitted in plaintext.");
        }

        // Configure connector host and port
        connector.setHost(gatewayHost);
        connector.setPort(gatewayPort);
        server.addConnector(connector);

        // Create servlet context
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        // Register core servlets
        // Note: InternalApiServlet removed - Python processors can access ControllerServices directly
        context.addServlet(new ServletHolder(new MetricsServlet(this)), "/_metrics");

        // Register Swagger UI servlets if enabled
        if (swaggerEnabled) {
            openapiGenerator = new OpenAPIGenerator(getGatewayUrl());
            context.addServlet(new ServletHolder(new OpenAPIServlet(this, openapiGenerator)), openapiPath);
            context.addServlet(new ServletHolder(new SwaggerServlet(this, openapiPath)), swaggerPath + "/*");
            getLogger().debug("Registered Swagger UI servlets: {} and {}", swaggerPath, openapiPath);
        }

        // Register Gateway servlet last to avoid catching Swagger paths
        context.addServlet(new ServletHolder(new GatewayServlet(this)), "/*");

        server.setHandler(context);
        server.start();
    }

    private void stopServer() throws Exception {
        if (server != null && server.isRunning()) {
            server.stop();
            server.join();
        }
    }

    /**
     * Internal class to hold endpoint registration data.
     */
    public static class EndpointRegistration {
        public final EndpointHandler handler;
        public final LinkedBlockingQueue<GatewayRequest> queue;
        public final int responseStatusCode;
        public final String responseBody;

        public EndpointRegistration(EndpointHandler handler, LinkedBlockingQueue<GatewayRequest> queue) {
            this(handler, queue, 202, null);
        }

        public EndpointRegistration(EndpointHandler handler, LinkedBlockingQueue<GatewayRequest> queue,
                                   int responseStatusCode, String responseBody) {
            this.handler = handler;
            this.queue = queue;
            this.responseStatusCode = responseStatusCode;
            this.responseBody = responseBody;
        }
    }
}