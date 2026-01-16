# NodeJSAppAPIGateway - Technical Specification

**Version:** 1.0.0-SNAPSHOT
**Date:** 2026-01-02
**Status:** Draft - Under Review
**Pattern:** NiFi Pattern B (Controller Service)
**Companion Service:** NodeJSApplicationManagerService

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [API Specification](#api-specification)
4. [Configuration Properties](#configuration-properties)
5. [Endpoint Registration](#endpoint-registration)
6. [Request/Response Flow](#requestresponse-flow)
7. [Security Considerations](#security-considerations)
8. [Integration with NodeJSApplicationManagerService](#integration-with-nodejsapplicationmanagerservice)
9. [Implementation Details](#implementation-details)
10. [Testing Requirements](#testing-requirements)
11. [Deployment Guide](#deployment-guide)
12. [Examples](#examples)

---

## Overview

### Purpose

The **NodeJSAppAPIGateway** is a NiFi Controller Service that provides a lightweight HTTP server specifically designed for communication between managed Node.js applications and NiFi processors. It eliminates the complexity of HandleHTTP request/response patterns by providing direct endpoint-to-processor mapping.

### Primary Use Cases

1. **Form Submission Handling**
   - Quality Event System forms → NiFi database processors
   - User registration → NiFi validation and storage flows
   - Survey responses → NiFi analytics pipelines

2. **Event Stream Processing**
   - Frontend events (clicks, analytics) → NiFi event processors
   - Real-time notifications → NiFi alert processors
   - Webhook receivers from managed apps

3. **Simplified CRUD Operations**
   - Create/Read/Update/Delete operations from frontend
   - Direct data submission without routing complexity
   - Type-safe payload validation

### Design Goals

- ✅ **Simplicity:** Declarative endpoint registration, no routing logic
- ✅ **Type Safety:** Schema validation at gateway level
- ✅ **Performance:** Lightweight embedded HTTP server (Jetty)
- ✅ **NiFi Patterns:** Processors pull data, maintaining standard NiFi architecture
- ✅ **Integration:** Works seamlessly with NodeJSApplicationManagerService
- ✅ **Testing:** Standard HTTP interface for easy testing

---

## Architecture

### Component Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                     Node.js Application                          │
│              (Managed by NodeJSApplicationManagerService)        │
│                                                                  │
│  Quality Event Form                                              │
│    ↓                                                             │
│  fetch('http://localhost:8080/api/quality-event', {             │
│    method: 'POST',                                               │
│    body: JSON.stringify(eventData)                               │
│  })                                                              │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             │ HTTP POST
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│              NodeJSAppAPIGateway Controller Service              │
│              (Embedded Jetty Server on port 8080)                │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐     │
│  │  Endpoint Registry                                     │     │
│  │  ┌──────────────────────────────────────────────┐     │     │
│  │  │ /api/quality-event → QueueA (1000 capacity)  │     │     │
│  │  │ /api/user-register → QueueB (500 capacity)   │     │     │
│  │  │ /api/analytics     → QueueC (2000 capacity)  │     │     │
│  │  └──────────────────────────────────────────────┘     │     │
│  └────────────────────────────────────────────────────────┘     │
│                                                                  │
│  Request Processing:                                             │
│  1. Match endpoint path                                          │
│  2. Validate content-type (JSON/form-data)                       │
│  3. Optional schema validation                                   │
│  4. Convert to FlowFileData                                      │
│  5. Queue for registered processor                               │
│  6. Return HTTP 202 Accepted                                     │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             │ FlowFileData in Queue
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│         ReceiveFromNodeJSApp Processor                           │
│         (endpoint=/api/quality-event)                            │
│                                                                  │
│  @OnTrigger:                                                     │
│  - Poll QueueA                                                   │
│  - Convert FlowFileData → FlowFile                               │
│  - Add attributes (endpoint.path, http.method, etc.)             │
│  - Transfer to success relationship                              │
│                                                                  │
│  FlowFile Attributes:                                            │
│    endpoint.path = /api/quality-event                            │
│    http.method = POST                                            │
│    content.type = application/json                               │
│    request.timestamp = 2026-01-02T10:30:00Z                      │
│    client.address = 127.0.0.1                                    │
│                                                                  │
│  FlowFile Content:                                               │
│    {"eventType":"quality-check", "data":{...}}                   │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│         Standard NiFi Processors                                 │
│                                                                  │
│  EvaluateJsonPath → ValidateRecord → PutDatabaseRecord           │
└──────────────────────────────────────────────────────────────────┘
```

### Class Diagram

```
┌─────────────────────────────────────────────────────┐
│ <<interface>>                                       │
│ NodeJSAppAPIGateway                                 │
│ (extends ControllerService)                         │
├─────────────────────────────────────────────────────┤
│ + getGatewayPort(): int                             │
│ + getGatewayUrl(): String                           │
│ + registerEndpoint(pattern, handler): void          │
│ + unregisterEndpoint(pattern): void                 │
│ + getRegisteredEndpoints(): List<String>            │
│ + getEndpointMetrics(pattern): EndpointMetrics      │
└─────────────────────────────────────────────────────┘
                          △
                          │ implements
                          │
┌─────────────────────────────────────────────────────┐
│ StandardNodeJSAppAPIGateway                         │
│ (extends AbstractControllerService)                 │
├─────────────────────────────────────────────────────┤
│ - server: Server (Jetty)                            │
│ - endpointRegistry: Map<String, EndpointHandler>    │
│ - requestQueues: Map<String, BlockingQueue>         │
│ - metrics: Map<String, EndpointMetrics>             │
├─────────────────────────────────────────────────────┤
│ + @OnEnabled onEnabled(ConfigurationContext)        │
│ + @OnDisabled onDisabled()                          │
│ - startServer()                                     │
│ - stopServer()                                      │
│ - handleRequest(HttpServletRequest, Response)       │
│ - validateRequest(Request): ValidationResult        │
└─────────────────────────────────────────────────────┘
```

### Data Flow: Detailed Sequence

```
Node.js App                  Gateway                    Processor
     │                         │                            │
     │ POST /api/quality-event │                            │
     ├────────────────────────>│                            │
     │                         │                            │
     │                         │ 1. Match endpoint          │
     │                         │    (/api/quality-event)    │
     │                         │                            │
     │                         │ 2. Validate request        │
     │                         │    - Content-Type: JSON    │
     │                         │    - Schema (optional)     │
     │                         │                            │
     │                         │ 3. Create FlowFileData     │
     │                         │    - Parse JSON body       │
     │                         │    - Extract headers       │
     │                         │    - Add metadata          │
     │                         │                            │
     │                         │ 4. Queue for processor     │
     │                         │    queue.offer(data)       │
     │                         │                            │
     │      202 Accepted       │                            │
     │<────────────────────────┤                            │
     │                         │                            │
     │                         │                            │
     │                         │    @OnTrigger scheduled    │
     │                         │<───────────────────────────┤
     │                         │                            │
     │                         │ 5. Poll queue              │
     │                         │    data = queue.poll()     │
     │                         ├───────────────────────────>│
     │                         │                            │
     │                         │                            │ 6. Convert to FlowFile
     │                         │                            │    session.create()
     │                         │                            │    session.write(content)
     │                         │                            │    session.putAttributes()
     │                         │                            │
     │                         │                            │ 7. Transfer to success
     │                         │                            │    session.transfer(ff, REL_SUCCESS)
     │                         │                            │
```

---

## API Specification

### Interface: NodeJSAppAPIGateway

**Package:** `org.nocodenation.nifi.nodejsapp.gateway`
**Extends:** `org.apache.nifi.controller.ControllerService`

```java
package org.nocodenation.nifi.nodejsapp.gateway;

import org.apache.nifi.controller.ControllerService;
import java.util.List;

/**
 * Controller Service providing HTTP gateway for Node.js application communication.
 *
 * <p>This service runs an embedded HTTP server that allows managed Node.js applications
 * to send data directly to NiFi processors without complex routing logic. Processors
 * register endpoint patterns and receive data as FlowFiles.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Declarative endpoint registration by processors</li>
 *   <li>Automatic request queuing and backpressure</li>
 *   <li>Schema validation (optional)</li>
 *   <li>Endpoint-level metrics tracking</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface NodeJSAppAPIGateway extends ControllerService {

    /**
     * Gets the port number the gateway is listening on.
     *
     * @return configured port number
     */
    int getGatewayPort();

    /**
     * Gets the full base URL of the gateway.
     *
     * @return gateway URL (e.g., "http://localhost:8080")
     */
    String getGatewayUrl();

    /**
     * Registers an endpoint pattern with a handler.
     *
     * <p>This is typically called by processors during @OnScheduled to register
     * themselves as receivers for specific API endpoints.</p>
     *
     * @param pattern endpoint pattern (e.g., "/api/quality-event")
     * @param handler callback to process incoming requests
     * @throws EndpointAlreadyRegisteredException if pattern is already registered
     */
    void registerEndpoint(String pattern, EndpointHandler handler)
        throws EndpointAlreadyRegisteredException;

    /**
     * Unregisters an endpoint pattern.
     *
     * <p>Called by processors during @OnStopped to clean up their endpoints.</p>
     *
     * @param pattern endpoint pattern to unregister
     */
    void unregisterEndpoint(String pattern);

    /**
     * Gets list of all registered endpoint patterns.
     *
     * @return list of endpoint patterns currently registered
     */
    List<String> getRegisteredEndpoints();

    /**
     * Gets metrics for a specific endpoint.
     *
     * @param pattern endpoint pattern
     * @return metrics object with request counts, latencies, errors
     */
    EndpointMetrics getEndpointMetrics(String pattern);
}
```

### Handler Interface: EndpointHandler

```java
package org.nocodenation.nifi.nodejsapp.gateway;

/**
 * Callback interface for handling incoming HTTP requests.
 *
 * <p>Processors implement this interface (typically as a lambda) to receive
 * requests for their registered endpoints.</p>
 */
@FunctionalInterface
public interface EndpointHandler {

    /**
     * Process an incoming HTTP request.
     *
     * @param request incoming request data
     * @return response to send back to client
     * @throws RequestProcessingException if request cannot be processed
     */
    GatewayResponse handleRequest(GatewayRequest request)
        throws RequestProcessingException;
}
```

### Data Transfer Objects

#### GatewayRequest

```java
package org.nocodenation.nifi.nodejsapp.gateway;

import java.time.Instant;
import java.util.Map;

/**
 * Represents an incoming HTTP request at the gateway.
 */
public class GatewayRequest {

    private final String method;           // GET, POST, PUT, DELETE, etc.
    private final String path;             // /api/quality-event
    private final Map<String, String> headers;
    private final Map<String, String> queryParameters;
    private final String contentType;
    private final byte[] body;
    private final String bodyAsString;
    private final String clientAddress;
    private final Instant timestamp;

    // Getters
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public Map<String, String> getHeaders() { return headers; }
    public Map<String, String> getQueryParameters() { return queryParameters; }
    public String getContentType() { return contentType; }
    public byte[] getBody() { return body; }
    public String getBodyAsString() { return bodyAsString; }
    public String getClientAddress() { return clientAddress; }
    public Instant getTimestamp() { return timestamp; }

    // Convenience methods
    public String getHeader(String name) { return headers.get(name); }
    public String getQueryParameter(String name) { return queryParameters.get(name); }
    public boolean isJson() { return contentType != null && contentType.contains("application/json"); }
    public boolean isFormData() { return contentType != null && contentType.contains("application/x-www-form-urlencoded"); }

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        // Builder implementation
    }
}
```

#### GatewayResponse

```java
package org.nocodenation.nifi.nodejsapp.gateway;

import java.util.Map;

/**
 * Represents an HTTP response to send back to the client.
 */
public class GatewayResponse {

    private final int statusCode;
    private final String body;
    private final Map<String, String> headers;

    public GatewayResponse(int statusCode) {
        this(statusCode, "", Map.of());
    }

    public GatewayResponse(int statusCode, String body) {
        this(statusCode, body, Map.of());
    }

    public GatewayResponse(int statusCode, String body, Map<String, String> headers) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
    public Map<String, String> getHeaders() { return headers; }

    // Common response factories
    public static GatewayResponse accepted() {
        return new GatewayResponse(202, "{\"status\":\"accepted\"}",
            Map.of("Content-Type", "application/json"));
    }

    public static GatewayResponse created(String location) {
        return new GatewayResponse(201, "",
            Map.of("Location", location));
    }

    public static GatewayResponse badRequest(String message) {
        return new GatewayResponse(400, "{\"error\":\"" + message + "\"}",
            Map.of("Content-Type", "application/json"));
    }

    public static GatewayResponse internalError(String message) {
        return new GatewayResponse(500, "{\"error\":\"" + message + "\"}",
            Map.of("Content-Type", "application/json"));
    }

    public static GatewayResponse queueFull() {
        return new GatewayResponse(503, "{\"error\":\"Service busy, queue full\"}",
            Map.of("Content-Type", "application/json", "Retry-After", "5"));
    }
}
```

#### EndpointMetrics

```java
package org.nocodenation.nifi.nodejsapp.gateway;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks metrics for a specific endpoint.
 */
public class EndpointMetrics {

    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicInteger queueFullRejections = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicInteger currentQueueSize = new AtomicInteger(0);
    private Instant lastRequestTime;

    public int getTotalRequests() { return totalRequests.get(); }
    public int getSuccessfulRequests() { return successfulRequests.get(); }
    public int getFailedRequests() { return failedRequests.get(); }
    public int getQueueFullRejections() { return queueFullRejections.get(); }
    public long getAverageLatencyMs() {
        int total = totalRequests.get();
        return total > 0 ? totalLatencyMs.get() / total : 0;
    }
    public int getCurrentQueueSize() { return currentQueueSize.get(); }
    public Instant getLastRequestTime() { return lastRequestTime; }

    // Package-private update methods
    void recordRequest() { totalRequests.incrementAndGet(); }
    void recordSuccess(long latencyMs) {
        successfulRequests.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        lastRequestTime = Instant.now();
    }
    void recordFailure() { failedRequests.incrementAndGet(); }
    void recordQueueFull() { queueFullRejections.incrementAndGet(); }
    void updateQueueSize(int size) { currentQueueSize.set(size); }
}
```

---

## Configuration Properties

### PropertyDescriptors

#### 1. Gateway Port

```java
public static final PropertyDescriptor GATEWAY_PORT = new PropertyDescriptor.Builder()
    .name("Gateway Port")
    .displayName("Gateway Port")
    .description("Port number for the HTTP gateway server to listen on")
    .required(true)
    .defaultValue("8080")
    .addValidator(StandardValidators.PORT_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Range:** 1-65535
**Default:** 8080
**Note:** Should be different from Node.js app port and NiFi UI port (8443)

#### 2. Gateway Host

```java
public static final PropertyDescriptor GATEWAY_HOST = new PropertyDescriptor.Builder()
    .name("Gateway Host")
    .displayName("Gateway Host")
    .description("Host address for the gateway to bind to. Use 127.0.0.1 for localhost only, " +
                 "0.0.0.0 for all interfaces")
    .required(true)
    .defaultValue("127.0.0.1")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Default:** 127.0.0.1 (localhost only, secure default)
**Alternative:** 0.0.0.0 (all interfaces, use with caution)

#### 3. Max Queue Size Per Endpoint

```java
public static final PropertyDescriptor MAX_QUEUE_SIZE = new PropertyDescriptor.Builder()
    .name("Max Queue Size Per Endpoint")
    .displayName("Max Queue Size Per Endpoint")
    .description("Maximum number of requests to queue per endpoint before rejecting with 503")
    .required(true)
    .defaultValue("1000")
    .addValidator(StandardValidators.createLongValidator(10, 100000, true))
    .build();
```

**Range:** 10 - 100,000
**Default:** 1,000

#### 4. Enable Request Logging

```java
public static final PropertyDescriptor ENABLE_REQUEST_LOGGING = new PropertyDescriptor.Builder()
    .name("Enable Request Logging")
    .displayName("Enable Request Logging")
    .description("Log all incoming requests at DEBUG level")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("false")
    .build();
```

**Default:** false (avoid log spam in production)

#### 5. Enable Schema Validation

```java
public static final PropertyDescriptor ENABLE_SCHEMA_VALIDATION = new PropertyDescriptor.Builder()
    .name("Enable Schema Validation")
    .displayName("Enable Schema Validation")
    .description("Validate incoming JSON payloads against registered schemas")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("false")
    .build();
```

**Default:** false (optional validation)
**Note:** Schemas are provided by processors during endpoint registration

#### 6. Request Timeout

```java
public static final PropertyDescriptor REQUEST_TIMEOUT = new PropertyDescriptor.Builder()
    .name("Request Timeout")
    .displayName("Request Timeout")
    .description("Maximum time to wait for request processing before returning 504 Gateway Timeout")
    .required(true)
    .defaultValue("30 sec")
    .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
    .build();
```

**Range:** 1 sec - 5 min
**Default:** 30 sec

#### 7. Max Request Body Size

```java
public static final PropertyDescriptor MAX_REQUEST_SIZE = new PropertyDescriptor.Builder()
    .name("Max Request Body Size")
    .displayName("Max Request Body Size")
    .description("Maximum allowed request body size")
    .required(true)
    .defaultValue("10 MB")
    .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
    .build();
```

**Range:** 1 KB - 100 MB
**Default:** 10 MB

#### 8. Enable CORS

```java
public static final PropertyDescriptor ENABLE_CORS = new PropertyDescriptor.Builder()
    .name("Enable CORS")
    .displayName("Enable CORS")
    .description("Enable Cross-Origin Resource Sharing (CORS) headers")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("true")
    .build();
```

**Default:** true (needed for browser-based apps)

#### 9. CORS Allowed Origins

```java
public static final PropertyDescriptor CORS_ALLOWED_ORIGINS = new PropertyDescriptor.Builder()
    .name("CORS Allowed Origins")
    .displayName("CORS Allowed Origins")
    .description("Comma-separated list of allowed origins for CORS. Use * for all origins")
    .required(false)
    .defaultValue("http://localhost:3000")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .dependsOn(ENABLE_CORS, "true")
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Default:** http://localhost:3000 (Node.js app default port)
**Format:** Comma-separated: `http://localhost:3000,http://localhost:3001`

---

## Endpoint Registration

### Registration Flow

```java
// In ReceiveFromNodeJSApp processor

@OnScheduled
public void registerEndpoint(ProcessContext context) throws ProcessException {
    NodeJSAppAPIGateway gateway = context.getProperty(GATEWAY_SERVICE)
        .asControllerService(NodeJSAppAPIGateway.class);

    String endpointPath = context.getProperty(ENDPOINT_PATH).getValue();

    // Register endpoint with handler
    gateway.registerEndpoint(endpointPath, request -> {
        // Validate request
        if (!request.isJson()) {
            return GatewayResponse.badRequest("Content-Type must be application/json");
        }

        // Create FlowFileData from request
        FlowFileData data = FlowFileData.builder()
            .content(request.getBody())
            .attribute("endpoint.path", request.getPath())
            .attribute("http.method", request.getMethod())
            .attribute("content.type", request.getContentType())
            .attribute("request.timestamp", request.getTimestamp().toString())
            .attribute("client.address", request.getClientAddress())
            .build();

        // Queue for processor to pick up
        boolean queued = requestQueue.offer(data);

        if (!queued) {
            return GatewayResponse.queueFull();
        }

        return GatewayResponse.accepted();
    });

    getLogger().info("Registered endpoint: {}", endpointPath);
}

@OnStopped
public void unregisterEndpoint(ProcessContext context) {
    NodeJSAppAPIGateway gateway = context.getProperty(GATEWAY_SERVICE)
        .asControllerService(NodeJSAppAPIGateway.class);

    String endpointPath = context.getProperty(ENDPOINT_PATH).getValue();
    gateway.unregisterEndpoint(endpointPath);

    getLogger().info("Unregistered endpoint: {}", endpointPath);
}
```

### Endpoint Patterns

**Supported:**
- Exact match: `/api/quality-event`
- Wildcard suffix: `/api/events/*` (matches `/api/events/123`, `/api/events/foo/bar`)
- Path parameters: `/api/user/{userId}` (captures userId as attribute)

**Not Supported (for simplicity):**
- Regular expressions
- Multiple wildcards
- Query parameter routing

### Concurrent Registration Handling

```java
// Thread-safe registration with ConcurrentHashMap
private final Map<String, EndpointHandler> endpointRegistry =
    new ConcurrentHashMap<>();

@Override
public synchronized void registerEndpoint(String pattern, EndpointHandler handler)
        throws EndpointAlreadyRegisteredException {

    if (endpointRegistry.containsKey(pattern)) {
        throw new EndpointAlreadyRegisteredException(
            "Endpoint already registered: " + pattern);
    }

    endpointRegistry.put(pattern, handler);
    requestQueues.put(pattern, new LinkedBlockingQueue<>(maxQueueSize));
    metrics.put(pattern, new EndpointMetrics());

    getLogger().info("Registered endpoint: {} (total: {})",
        pattern, endpointRegistry.size());
}
```

---

## Request/Response Flow

### Happy Path: Successful Request

```
1. Client → Gateway: POST /api/quality-event
   Body: {"eventType":"quality-check","severity":"high"}

2. Gateway: Match endpoint → Found handler

3. Gateway: Validate request
   - Content-Type: application/json ✓
   - Body size: 58 bytes < 10MB ✓
   - Schema validation: (if enabled) ✓

4. Gateway: Call handler.handleRequest(request)

5. Handler (Processor): Create FlowFileData + Queue
   - Convert request → FlowFileData
   - requestQueue.offer(data) → true ✓
   - Return GatewayResponse.accepted()

6. Gateway → Client: 202 Accepted
   Body: {"status":"accepted"}

7. Processor @OnTrigger (later):
   - data = requestQueue.poll()
   - Convert FlowFileData → FlowFile
   - Transfer to success
```

### Error Path: Queue Full

```
1. Client → Gateway: POST /api/quality-event

2. Gateway: Match endpoint → Found handler

3. Handler (Processor): Queue at capacity
   - requestQueue.offer(data) → false ✗
   - Return GatewayResponse.queueFull()

4. Gateway → Client: 503 Service Unavailable
   Headers: Retry-After: 5
   Body: {"error":"Service busy, queue full"}

5. Client: Retry after 5 seconds
```

### Error Path: No Handler Registered

```
1. Client → Gateway: POST /api/unknown-endpoint

2. Gateway: Match endpoint → Not found ✗

3. Gateway → Client: 404 Not Found
   Body: {"error":"Endpoint not found: /api/unknown-endpoint"}
```

### Error Path: Invalid Content-Type

```
1. Client → Gateway: POST /api/quality-event
   Content-Type: text/plain

2. Gateway: Match endpoint → Found handler

3. Handler: Validate request
   - request.isJson() → false ✗
   - Return GatewayResponse.badRequest("Content-Type must be application/json")

4. Gateway → Client: 400 Bad Request
   Body: {"error":"Content-Type must be application/json"}
```

### Error Path: Handler Exception

```
1. Client → Gateway: POST /api/quality-event

2. Gateway: Match endpoint → Found handler

3. Handler: Throws RequestProcessingException

4. Gateway: Catch exception
   - Log error with full stack trace
   - Record metrics (failedRequests++)

5. Gateway → Client: 500 Internal Server Error
   Body: {"error":"Request processing failed"}
```

---

## Security Considerations

### 1. Network Exposure

**Risk:** Gateway exposed to external networks

**Mitigation:**
```java
// Default: bind to localhost only
environment.put("GATEWAY_HOST", "127.0.0.1");

// Only allow access from Node.js app (same host)
// Use firewall rules for additional protection
```

**Best Practice:**
- Use `127.0.0.1` (localhost) for same-host communication
- Use reverse proxy (nginx) for external access
- Never use `0.0.0.0` in production without firewall

### 2. Request Size Limits

**Risk:** Large payloads causing memory exhaustion

**Mitigation:**
```java
// Enforce max request size (default 10MB)
if (request.getContentLength() > maxRequestSize) {
    return new GatewayResponse(413, "Payload too large");
}
```

### 3. Queue Exhaustion (DoS Protection)

**Risk:** Too many requests filling queues

**Mitigation:**
```java
// Bounded queues per endpoint
LinkedBlockingQueue<FlowFileData> queue =
    new LinkedBlockingQueue<>(maxQueueSize);

// Reject with 503 when full
if (!queue.offer(data)) {
    metrics.recordQueueFull();
    return GatewayResponse.queueFull();
}
```

### 4. CORS Security

**Risk:** Unauthorized cross-origin access

**Mitigation:**
```java
// Whitelist specific origins (not *)
if (enableCors) {
    response.setHeader("Access-Control-Allow-Origin",
        "http://localhost:3000"); // Specific origin
    response.setHeader("Access-Control-Allow-Methods", "POST, GET");
    response.setHeader("Access-Control-Allow-Headers", "Content-Type");
}
```

### 5. Input Validation

**Risk:** Malicious payloads, injection attacks

**Mitigation:**
```java
// Validate Content-Type
if (!request.isJson() && !request.isFormData()) {
    return GatewayResponse.badRequest("Unsupported Content-Type");
}

// Optional: Schema validation
if (enableSchemaValidation) {
    ValidationResult result = schemaValidator.validate(request.getBody(), schema);
    if (!result.isValid()) {
        return GatewayResponse.badRequest(result.getErrors());
    }
}
```

### 6. Request Timeout

**Risk:** Slow requests blocking threads

**Mitigation:**
```java
// Jetty connector configuration
ServerConnector connector = new ServerConnector(server);
connector.setIdleTimeout(requestTimeoutMs);
```

### 7. Authentication/Authorization

**Current Implementation:** None (relies on network isolation)

**Future Enhancement:**
```java
// Optional: API key validation
String apiKey = request.getHeader("X-API-Key");
if (!validateApiKey(apiKey)) {
    return new GatewayResponse(401, "Unauthorized");
}
```

**Recommendation:** Use network-level security (localhost binding) for v1.0.0, add authentication in v1.1.0 if needed.

---

## Integration with NodeJSApplicationManagerService

### Complementary Services

These two services work together but are independent:

| Feature | NodeJSApplicationManagerService | NodeJSAppAPIGateway |
|---------|--------------------------------|---------------------|
| **Purpose** | Manage Node.js app lifecycle | Receive data from Node.js app |
| **Direction** | NiFi → Node.js | Node.js → NiFi |
| **Protocol** | Process management, HTTP health checks | HTTP API server |
| **Port** | Manages app on port 3000 | Gateway on port 8080 |
| **Lifecycle** | Start/stop Node.js process | Start/stop HTTP server |

### Typical Configuration

```
NodeJSApplicationManagerService:
  - Application Directory: /opt/nifi/apps/quality-event-system
  - Application Port: 3000
  - Start Command: start
  - Health Check URL: http://localhost:3000/health

NodeJSAppAPIGateway:
  - Gateway Port: 8080
  - Gateway Host: 127.0.0.1
  - CORS Allowed Origins: http://localhost:3000
  - Max Queue Size: 1000

ReceiveFromNodeJSApp Processor:
  - Gateway Service: NodeJSAppAPIGateway
  - Endpoint Path: /api/quality-event
```

### Environment Variables for Node.js App

The Node.js application needs to know the gateway URL:

```javascript
// In Node.js application (managed by NodeJSApplicationManagerService)

const NIFI_GATEWAY_URL = process.env.NIFI_GATEWAY_URL || 'http://localhost:8080';

// Submit quality event
async function submitQualityEvent(eventData) {
  const response = await fetch(`${NIFI_GATEWAY_URL}/api/quality-event`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(eventData)
  });

  if (!response.ok) {
    throw new Error(`Failed to submit event: ${response.status}`);
  }

  return response.json();
}
```

### Configuration in NodeJSApplicationManagerService

```
Environment Variables (JSON):
{
  "NODE_ENV": "production",
  "PORT": "3000",
  "NIFI_GATEWAY_URL": "http://localhost:8080"
}
```

### Complete Integration Example

```
┌────────────────────────────────────────────────────────┐
│        NiFi Environment                                │
│                                                         │
│  ┌──────────────────────────────────────────────┐     │
│  │ NodeJSApplicationManagerService              │     │
│  │ - Manages: quality-event-system              │     │
│  │ - Port: 3000                                 │     │
│  │ - Env: NIFI_GATEWAY_URL=http://localhost:8080│     │
│  └────────────────┬─────────────────────────────┘     │
│                   │                                     │
│                   │ Starts/Monitors                     │
│                   ▼                                     │
│  ┌──────────────────────────────────────────────┐     │
│  │ Node.js App (port 3000)                      │     │
│  │ - Form Submit → POST to NIFI_GATEWAY_URL     │     │
│  └────────────────┬─────────────────────────────┘     │
│                   │                                     │
│                   │ HTTP POST                           │
│                   ▼                                     │
│  ┌──────────────────────────────────────────────┐     │
│  │ NodeJSAppAPIGateway (port 8080)              │     │
│  │ - Endpoint: /api/quality-event → QueueA      │     │
│  └────────────────┬─────────────────────────────┘     │
│                   │                                     │
│                   │ FlowFileData                        │
│                   ▼                                     │
│  ┌──────────────────────────────────────────────┐     │
│  │ ReceiveFromNodeJSApp                         │     │
│  │ - Endpoint: /api/quality-event               │     │
│  │ - Polls QueueA                               │     │
│  └────────────────┬─────────────────────────────┘     │
│                   │                                     │
│                   ▼                                     │
│  ┌──────────────────────────────────────────────┐     │
│  │ PutDatabaseRecord                            │     │
│  │ - Inserts quality events to database         │     │
│  └──────────────────────────────────────────────┘     │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## Implementation Details

### Module Structure

Following NiFi Pattern B (Controller Service):

```
nodejs-app-gateway/
├── pom.xml (parent)
│
├── nodejs-app-gateway-service-api/          # API Module (JAR)
│   └── src/main/java/.../gateway/
│       ├── NodeJSAppAPIGateway.java         # Interface
│       ├── EndpointHandler.java             # Functional interface
│       ├── GatewayRequest.java              # DTO
│       ├── GatewayResponse.java             # DTO
│       ├── EndpointMetrics.java             # Metrics
│       ├── EndpointAlreadyRegisteredException.java
│       └── RequestProcessingException.java
│
├── nodejs-app-gateway-service-api-nar/      # API NAR
│
├── nodejs-app-gateway-service/              # Implementation Module (JAR)
│   └── src/main/java/.../gateway/
│       ├── StandardNodeJSAppAPIGateway.java  # Main implementation
│       ├── GatewayServlet.java               # Jetty servlet
│       ├── EndpointMatcher.java              # Pattern matching
│       └── FlowFileData.java                 # Internal data structure
│
└── nodejs-app-gateway-service-nar/          # Implementation NAR
```

### Key Dependencies

```xml
<!-- Jetty for embedded HTTP server -->
<dependency>
    <groupId>org.eclipse.jetty</groupId>
    <artifactId>jetty-server</artifactId>
    <version>11.0.15</version>
</dependency>

<!-- JSON validation (optional) -->
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.0.87</version>
</dependency>
```

### Service Descriptor

**File:** `nodejs-app-gateway-service/src/main/resources/META-INF/services/org.apache.nifi.controller.ControllerService`

```
org.nocodenation.nifi.nodejsapp.gateway.StandardNodeJSAppAPIGateway
```

---

## Testing Requirements

### Unit Tests

1. **StandardNodeJSAppAPIGatewayTest**
   - Test endpoint registration/unregistration
   - Test concurrent registration
   - Test duplicate endpoint handling
   - Test metrics tracking

2. **GatewayServletTest**
   - Test request routing
   - Test 404 for unknown endpoints
   - Test 503 for full queues
   - Test request size limits
   - Test CORS headers

3. **EndpointMatcherTest**
   - Test exact matching
   - Test wildcard patterns
   - Test path parameters
   - Test precedence rules

### Integration Tests

```java
@Test
public void testEndToEndFlow() throws Exception {
    // Start gateway
    TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);
    StandardNodeJSAppAPIGateway gateway = new StandardNodeJSAppAPIGateway();
    runner.addControllerService("gateway", gateway);
    runner.setProperty(gateway, GATEWAY_PORT, "8080");
    runner.enableControllerService(gateway);

    // Register endpoint
    BlockingQueue<FlowFileData> queue = new LinkedBlockingQueue<>();
    gateway.registerEndpoint("/api/test", request -> {
        FlowFileData data = FlowFileData.from(request);
        queue.offer(data);
        return GatewayResponse.accepted();
    });

    // Send HTTP request
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:8080/api/test"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"test\":\"data\"}"))
        .build();

    HttpResponse<String> response = client.send(request,
        HttpResponse.BodyHandlers.ofString());

    // Verify
    assertEquals(202, response.statusCode());
    FlowFileData data = queue.poll(1, TimeUnit.SECONDS);
    assertNotNull(data);
    assertEquals("/api/test", data.getAttribute("endpoint.path"));
}
```

---

## Deployment Guide

### Prerequisites

- NiFi 2.6.0+
- NodeJSApplicationManagerService (optional but recommended)

### Build and Install

```bash
cd liquid-library/src/java_extensions/nodejs-app-gateway
mvn clean install

# Copy NARs to NiFi
cp nodejs-app-gateway-service-api-nar/target/*.nar /opt/nifi/lib/
cp nodejs-app-gateway-service-nar/target/*.nar /opt/nifi/lib/

# Restart NiFi
/opt/nifi/bin/nifi.sh restart
```

### Configuration Steps

1. Add controller service: `StandardNodeJSAppAPIGateway`
2. Configure gateway port (e.g., 8080)
3. Set CORS allowed origins (e.g., http://localhost:3000)
4. Enable the service
5. Add `ReceiveFromNodeJSApp` processor (see next section)
6. Configure processor with endpoint path
7. Connect processor to downstream flow

---

## Examples

### Example 1: Quality Event Submission

**Node.js Frontend:**
```javascript
// quality-event-system/src/api/quality.js
export async function submitQualityEvent(event) {
  const response = await fetch('http://localhost:8080/api/quality-event', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      eventType: event.type,
      severity: event.severity,
      description: event.description,
      timestamp: new Date().toISOString()
    })
  });

  if (response.status === 503) {
    throw new Error('System busy, please try again');
  }

  return response.json();
}
```

**NiFi Flow:**
```
ReceiveFromNodeJSApp (endpoint=/api/quality-event)
  ↓
EvaluateJsonPath (extract eventType, severity, description)
  ↓
RouteOnAttribute (route by severity: high/medium/low)
  ↓ [high]
PutDatabaseRecord + SendEmail (alert)
  ↓ [medium/low]
PutDatabaseRecord
```

### Example 2: User Registration

**Node.js Frontend:**
```javascript
// Register user form submission
async function registerUser(formData) {
  const response = await fetch('http://localhost:8080/api/user-register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: formData.email,
      name: formData.name,
      role: formData.role
    })
  });

  return response.json();
}
```

**NiFi Flow:**
```
ReceiveFromNodeJSApp (endpoint=/api/user-register)
  ↓
ValidateRecord (schema: user-registration-schema)
  ↓ [valid]
LookupRecord (check if email exists)
  ↓ [not found]
PutDatabaseRecord (insert user)
  ↓
InvokeHTTP (send welcome email via API)
```

### Example 3: Real-time Analytics Events

**Node.js Frontend:**
```javascript
// Track button clicks
function trackButtonClick(buttonId) {
  fetch('http://localhost:8080/api/analytics', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      eventType: 'button_click',
      buttonId: buttonId,
      timestamp: Date.now(),
      userId: getCurrentUserId()
    })
  }).catch(err => console.error('Analytics failed:', err));
}
```

**NiFi Flow:**
```
ReceiveFromNodeJSApp (endpoint=/api/analytics)
  ↓
MergeContent (batch 100 events or 10 seconds)
  ↓
PutS3Object (store in data lake)
  +
PublishKafka (real-time stream)
```

---

## Open Questions for Review

### 1. Schema Validation

**Question:** Should schema validation be built into the gateway, or should processors handle it?

**Option A:** Gateway validates before queueing
- **Pro:** Fail fast, immediate 400 response
- **Con:** Requires schema registration API, adds complexity

**Option B:** Processors validate after receiving FlowFile
- **Pro:** Simpler gateway, uses existing ValidateRecord processor
- **Con:** Wasted queue space for invalid requests

**Recommendation:** Option B for v1.0.0, Option A for v1.1.0

### 2. Response Handling

**Question:** Should processors be able to send custom responses back to clients?

**Current:** All responses are synchronous (202 Accepted, 400 Bad Request, etc.)

**Alternative:** Async response pattern
```java
// Processor sends response later
gateway.sendResponse(requestId, customResponse);
```

**Complexity:** Requires request correlation, timeout handling

**Recommendation:** Synchronous only for v1.0.0 (simpler)

### 3. Path Parameters

**Question:** Should we support path parameters like `/api/user/{userId}`?

**Implementation:**
```java
// Match /api/user/123
request.getPathParameter("userId") → "123"
```

**Complexity:** Pattern matching, parameter extraction

**Recommendation:** Yes, include in v1.0.0 (common use case)

### 4. Multiple HTTP Methods

**Question:** Should endpoints support multiple HTTP methods (GET, POST, PUT, DELETE)?

**Current:** Handler receives all methods, must check `request.getMethod()`

**Alternative:** Method-specific registration
```java
gateway.registerEndpoint("/api/user", Method.POST, postHandler);
gateway.registerEndpoint("/api/user", Method.GET, getHandler);
```

**Recommendation:** Current approach (single handler) for v1.0.0

### 5. Authentication

**Question:** Should the gateway support API key authentication?

**Implementation:**
```java
// Validate API key before routing
String apiKey = request.getHeader("X-API-Key");
if (!apiKeyValidator.isValid(apiKey)) {
    return new GatewayResponse(401, "Unauthorized");
}
```

**Recommendation:** No for v1.0.0 (rely on network isolation), add in v1.1.0 if needed

### 6. Metrics Endpoint

**Question:** Should the gateway expose a metrics endpoint?

**Example:** `GET http://localhost:8080/_metrics` → JSON with all endpoint metrics

**Recommendation:** Yes, useful for monitoring (include in v1.0.0)

---

## Next Steps

1. **Review this specification** - Identify gaps or concerns
2. **Address open questions** - Make decisions on optional features
3. **Create feature branch** - `feat/nodejs-app-gateway-v1.0.0`
4. **Implement API module** - Interfaces and DTOs
5. **Implement service** - StandardNodeJSAppAPIGateway with Jetty
6. **Implement processor** - ReceiveFromNodeJSApp (separate spec)
7. **Write tests** - Unit and integration tests
8. **Deploy and test** - Integration with quality-event-system
9. **Documentation** - User guide and examples

---

*End of Specification Document*