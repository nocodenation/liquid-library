# NodeJSAppAPIGateway - Technical Specification (v2)

**Version:** 1.0.0-SNAPSHOT
**Date:** 2026-01-02
**Status:** Approved - Ready for Implementation
**Pattern:** NiFi Pattern B (Controller Service)
**Companion Service:** NodeJSApplicationManagerService

---

## Document Updates

**v2 Changes (2026-01-02):**
- Changed default port from 8080 to **5050**
- Added **Internal Polling API** (`/_internal/poll/*`) for processor integration
- Documented **both Java and Python processor options**
- Added Python processor implementation guide
- Clarified schema validation approach (processors handle it)
- Confirmed synchronous-only response model
- Added path parameter support
- Confirmed metrics endpoint (`/_metrics`)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [API Specification](#api-specification)
4. [Processor Options](#processor-options)
5. [Configuration Properties](#configuration-properties)
6. [Internal APIs](#internal-apis)
7. [Request/Response Flow](#requestresponse-flow)
8. [Security Considerations](#security-considerations)
9. [Integration with NodeJSApplicationManagerService](#integration-with-nodejsapplicationmanagerservice)
10. [Implementation Details](#implementation-details)
11. [Testing Requirements](#testing-requirements)
12. [Deployment Guide](#deployment-guide)
13. [Examples](#examples)

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
   - Optional schema validation in processors

### Design Goals

- ✅ **Simplicity:** Declarative endpoint registration, no routing logic
- ✅ **Performance:** Lightweight embedded HTTP server (Jetty) on port 5050
- ✅ **NiFi Patterns:** Processors pull data, maintaining standard NiFi architecture
- ✅ **Flexibility:** Both Java and Python processor options available
- ✅ **Integration:** Works seamlessly with NodeJSApplicationManagerService
- ✅ **Testing:** Standard HTTP interface for easy testing

### Design Decisions (Approved)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| **Port** | 5050 | Non-standard port, avoids conflicts |
| **Schema Validation** | Processor-level | Use existing ValidateRecord processors |
| **Response Model** | Synchronous only | Simpler implementation, adequate for use case |
| **Path Parameters** | Supported | Common pattern (`/api/user/{userId}`) |
| **HTTP Methods** | Single handler | Handler receives all methods, checks `request.method` |
| **Authentication** | Not in v1.0.0 | Rely on network isolation, add in v1.1.0 |
| **Metrics Endpoint** | Yes (`/_metrics`) | Essential for monitoring |
| **Processor Options** | Java + Python | Java for performance, Python for maintainability |

---

## Architecture

### Component Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                     Node.js Application                          │
│              (Managed by NodeJSApplicationManagerService)        │
│                   Running on port 3000                           │
│                                                                  │
│  Quality Event Form Submit                                       │
│    ↓                                                             │
│  fetch('http://localhost:5050/api/quality-event', {             │
│    method: 'POST',                                               │
│    headers: { 'Content-Type': 'application/json' },              │
│    body: JSON.stringify(eventData)                               │
│  })                                                              │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             │ HTTP POST
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│              NodeJSAppAPIGateway Controller Service              │
│              (Embedded Jetty Server on port 5050)                │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐     │
│  │  Endpoint Queues (In-Memory)                           │     │
│  │  ┌──────────────────────────────────────────────┐     │     │
│  │  │ /api/quality-event → Queue (1000 capacity)   │     │     │
│  │  │ /api/user-register → Queue (1000 capacity)   │     │     │
│  │  │ /api/analytics     → Queue (2000 capacity)   │     │     │
│  │  └──────────────────────────────────────────────┘     │     │
│  └────────────────────────────────────────────────────────┘     │
│                                                                  │
│  Public API (for Node.js apps):                                  │
│    POST /api/quality-event → Queue request → 202 Accepted       │
│                                                                  │
│  Internal API (for processors):                                  │
│    GET /_internal/poll/api/quality-event → Dequeue request      │
│                                                                  │
│  Metrics API:                                                    │
│    GET /_metrics → JSON with all endpoint metrics                │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             │ HTTP GET (polling)
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│         Processor Options (Choose One):                          │
│                                                                  │
│  ┌────────────────────────────────────────────────────┐         │
│  │ Option 1: ReceiveFromNodeJSApp (Java)              │         │
│  │ - Direct queue access via Java API                 │         │
│  │ - Zero latency                                     │         │
│  │ - Best performance                                 │         │
│  └────────────────────────────────────────────────────┘         │
│                                                                  │
│  ┌────────────────────────────────────────────────────┐         │
│  │ Option 2: ReceiveFromNodeJSApp (Python)            │         │
│  │ - Polls /_internal/poll/* every 100ms              │         │
│  │ - Easier to customize                              │         │
│  │ - Adequate performance for most use cases          │         │
│  └────────────────────────────────────────────────────┘         │
│                                                                  │
│  Both create FlowFiles with attributes:                          │
│    - endpoint.path = /api/quality-event                          │
│    - http.method = POST                                          │
│    - content.type = application/json                             │
│    - request.timestamp = 2026-01-02T10:30:00Z                    │
│    - client.address = 127.0.0.1                                  │
│                                                                  │
│  FlowFile Content: {"eventType":"quality-check",...}             │
└────────────────────────────┬─────────────────────────────────────┘
                             │
                             ▼
┌──────────────────────────────────────────────────────────────────┐
│         Standard NiFi Processors                                 │
│                                                                  │
│  EvaluateJsonPath → ValidateRecord → PutDatabaseRecord           │
└──────────────────────────────────────────────────────────────────┘
```

### Data Flow Comparison: Java vs Python Processor

#### Java Processor (Direct Access)
```
Node.js App
  ↓ POST /api/quality-event
NodeJSAppAPIGateway
  ↓ queue.offer(data) [Java method call]
ReceiveFromNodeJSApp.java
  ↓ @OnTrigger → queue.poll() [0ms latency]
FlowFile created
```

#### Python Processor (HTTP Polling)
```
Node.js App
  ↓ POST /api/quality-event
NodeJSAppAPIGateway
  ↓ queue.offer(data)
  ↓ (sits in queue)
ReceiveFromNodeJSApp.py
  ↓ @onTrigger → GET /_internal/poll/api/quality-event [~100ms latency]
  ↓ HTTP response with queued data
FlowFile created
```

---

## API Specification

### Controller Service Interface

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
 * to send data directly to NiFi processors without complex routing logic.</p>
 *
 * <p>Processors can integrate via two methods:</p>
 * <ul>
 *   <li>Java processors: Direct queue access via registerEndpoint()</li>
 *   <li>Python processors: HTTP polling via /_internal/poll/{endpoint}</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface NodeJSAppAPIGateway extends ControllerService {

    /**
     * Gets the port number the gateway is listening on.
     *
     * @return configured port number (default: 5050)
     */
    int getGatewayPort();

    /**
     * Gets the full base URL of the gateway.
     *
     * @return gateway URL (e.g., "http://localhost:5050")
     */
    String getGatewayUrl();

    /**
     * Registers an endpoint for direct queue access (Java processors only).
     *
     * <p>Java processors call this during @OnScheduled to register themselves
     * as receivers for specific API endpoints.</p>
     *
     * @param pattern endpoint pattern (e.g., "/api/quality-event")
     * @param handler callback to process incoming requests
     * @throws EndpointAlreadyRegisteredException if pattern is already registered
     */
    void registerEndpoint(String pattern, EndpointHandler handler)
        throws EndpointAlreadyRegisteredException;

    /**
     * Unregisters an endpoint (Java processors only).
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

---

## Processor Options

### Option 1: Java Processor (High Performance)

**Class:** `ReceiveFromNodeJSApp`
**Package:** `org.nocodenation.nifi.nodejsapp.gateway.processors`
**Type:** Source Processor (creates FlowFiles)

**Use When:**
- High throughput required (>100 requests/sec)
- Sub-millisecond latency needed
- Running Java-heavy NiFi flows

**Implementation:**
```java
@Tags({"nodejs", "api", "gateway", "receive", "http"})
@CapabilityDescription("Receives HTTP requests from Node.js applications via NodeJSAppAPIGateway")
public class ReceiveFromNodeJSApp extends AbstractProcessor {

    public static final PropertyDescriptor GATEWAY_SERVICE =
        new PropertyDescriptor.Builder()
            .name("Gateway Service")
            .identifiesControllerService(NodeJSAppAPIGateway.class)
            .required(true)
            .build();

    public static final PropertyDescriptor ENDPOINT_PATH =
        new PropertyDescriptor.Builder()
            .name("Endpoint Path")
            .description("API endpoint path to listen on (e.g., /api/quality-event)")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    private final BlockingQueue<GatewayRequest> requestQueue =
        new LinkedBlockingQueue<>(10000);

    @OnScheduled
    public void registerEndpoint(ProcessContext context) {
        NodeJSAppAPIGateway gateway = context.getProperty(GATEWAY_SERVICE)
            .asControllerService(NodeJSAppAPIGateway.class);

        String endpoint = context.getProperty(ENDPOINT_PATH).getValue();

        gateway.registerEndpoint(endpoint, request -> {
            boolean queued = requestQueue.offer(request);
            return queued ? GatewayResponse.accepted() : GatewayResponse.queueFull();
        });
    }

    @OnTrigger
    public void onTrigger(ProcessContext context, ProcessSession session) {
        GatewayRequest request = requestQueue.poll();
        if (request == null) {
            return; // No data available
        }

        FlowFile flowFile = session.create();
        flowFile = session.write(flowFile, out -> out.write(request.getBody()));
        flowFile = session.putAllAttributes(flowFile, Map.of(
            "endpoint.path", request.getPath(),
            "http.method", request.getMethod(),
            "content.type", request.getContentType(),
            "request.timestamp", request.getTimestamp().toString(),
            "client.address", request.getClientAddress()
        ));

        session.transfer(flowFile, REL_SUCCESS);
    }

    @OnStopped
    public void unregisterEndpoint(ProcessContext context) {
        NodeJSAppAPIGateway gateway = context.getProperty(GATEWAY_SERVICE)
            .asControllerService(NodeJSAppAPIGateway.class);
        gateway.unregisterEndpoint(context.getProperty(ENDPOINT_PATH).getValue());
    }
}
```

**Pros:**
- ✅ Zero latency - direct queue access
- ✅ Highest performance
- ✅ Type-safe Java API

**Cons:**
- ⚠️ Requires Java knowledge for customization
- ⚠️ Requires recompilation for changes

---

### Option 2: Python Processor (Easy Maintenance)

**File:** `liquid-library/src/native_python_processors/ReceiveFromNodeJSApp/ReceiveFromNodeJSApp.py`
**Type:** FlowFileSource (creates FlowFiles)

**Use When:**
- Easy customization needed
- Python team members maintaining flows
- Throughput under 100 requests/sec
- ~100ms latency acceptable

**Implementation:**
```python
import json
import requests
from nifiapi.flowfilesource import FlowFileSource, FlowFileSourceResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

class ReceiveFromNodeJSApp(FlowFileSource):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileSource']

    class ProcessorDetails:
        version = '1.0.0-SNAPSHOT'
        description = '''Receives HTTP requests from Node.js applications via NodeJSAppAPIGateway.

        This processor polls the gateway's internal API to retrieve queued requests and creates
        FlowFiles with the request data and metadata as attributes.

        Use this processor as an alternative to the Java ReceiveFromNodeJSApp when you need
        easier customization or prefer Python-based flows.'''
        tags = ['nodejs', 'api', 'gateway', 'receive', 'http', 'python']

    def __init__(self, **kwargs):
        self.gateway_url = None
        self.endpoint_path = None
        self.poll_timeout = None

    GATEWAY_URL = PropertyDescriptor(
        name="Gateway URL",
        description="Base URL of the NodeJSAppAPIGateway (e.g., http://localhost:5050)",
        required=True,
        default_value="http://localhost:5050",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    ENDPOINT_PATH = PropertyDescriptor(
        name="Endpoint Path",
        description="API endpoint path to receive requests from (e.g., /api/quality-event). Must match the path used by the Node.js application.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    POLL_TIMEOUT = PropertyDescriptor(
        name="Poll Timeout",
        description="HTTP timeout when polling the gateway for requests (in seconds)",
        required=True,
        default_value="1",
        validators=[StandardValidators.POSITIVE_INTEGER_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [self.GATEWAY_URL, self.ENDPOINT_PATH, self.POLL_TIMEOUT]

    def onScheduled(self, context):
        """Initialize configuration when processor is scheduled"""
        self.gateway_url = context.getProperty(self.GATEWAY_URL).evaluateAttributeExpressions().getValue()
        self.endpoint_path = context.getProperty(self.ENDPOINT_PATH).evaluateAttributeExpressions().getValue()
        self.poll_timeout = int(context.getProperty(self.POLL_TIMEOUT).getValue())

        self.logger.info(f"Configured to poll {self.gateway_url}/_internal/poll{self.endpoint_path}")

    def create(self, context):
        """Poll gateway for next request and create FlowFile if available"""

        if self.gateway_url is None:
            # Not yet scheduled
            return FlowFileSourceResult(relationship="success")

        # Poll the gateway's internal API
        poll_url = f"{self.gateway_url}/_internal/poll{self.endpoint_path}"

        try:
            response = requests.get(poll_url, timeout=self.poll_timeout)

            if response.status_code == 204:
                # No data available (queue empty)
                return FlowFileSourceResult(relationship="success")

            if response.status_code != 200:
                self.logger.error(f"Gateway returned error: {response.status_code} - {response.text}")
                return FlowFileSourceResult(relationship="success")

            # Got a request!
            data = response.json()

            # Extract request components
            body = data.get('body', '')
            method = data.get('method', 'UNKNOWN')
            path = data.get('path', '')
            content_type = data.get('contentType', 'application/octet-stream')
            timestamp = data.get('timestamp', '')
            client_address = data.get('clientAddress', 'unknown')

            # Build attributes
            attributes = {
                'endpoint.path': path,
                'http.method': method,
                'content.type': content_type,
                'request.timestamp': timestamp,
                'client.address': client_address
            }

            # Add any custom headers as attributes with 'http.header.' prefix
            headers = data.get('headers', {})
            for header_name, header_value in headers.items():
                attributes[f'http.header.{header_name.lower()}'] = header_value

            # Add path parameters if present (from {userId} style patterns)
            path_params = data.get('pathParameters', {})
            for param_name, param_value in path_params.items():
                attributes[f'path.param.{param_name}'] = param_value

            # Create FlowFile with body as content
            return FlowFileSourceResult(
                relationship="success",
                contents=body.encode('utf-8') if isinstance(body, str) else body,
                attributes=attributes
            )

        except requests.exceptions.Timeout:
            # Timeout is expected when no data available - don't log as error
            return FlowFileSourceResult(relationship="success")

        except requests.exceptions.RequestException as e:
            self.logger.error(f"Failed to poll gateway: {str(e)}")
            return FlowFileSourceResult(relationship="success")

        except Exception as e:
            self.logger.error(f"Unexpected error: {str(e)}")
            return FlowFileSourceResult(relationship="success")
```

**Pros:**
- ✅ Easy to customize (Python)
- ✅ No compilation needed
- ✅ Familiar to Python developers
- ✅ Can add custom logic easily

**Cons:**
- ⚠️ ~100ms polling latency
- ⚠️ HTTP overhead per poll
- ⚠️ Lower throughput than Java

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
    .defaultValue("5050")
    .addValidator(StandardValidators.PORT_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Default:** 5050 (non-standard port)
**Range:** 1-65535

#### 2. Gateway Host

```java
public static final PropertyDescriptor GATEWAY_HOST = new PropertyDescriptor.Builder()
    .name("Gateway Host")
    .displayName("Gateway Host")
    .description("Host address for the gateway to bind to. Use 127.0.0.1 for localhost only (recommended), 0.0.0.0 for all interfaces")
    .required(true)
    .defaultValue("127.0.0.1")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Default:** 127.0.0.1 (localhost only, secure)
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

#### 4. Enable CORS

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

#### 5. CORS Allowed Origins

```java
public static final PropertyDescriptor CORS_ALLOWED_ORIGINS = new PropertyDescriptor.Builder()
    .name("CORS Allowed Origins")
    .displayName("CORS Allowed Origins")
    .description("Comma-separated list of allowed origins for CORS. Use * for all origins (not recommended)")
    .required(false)
    .defaultValue("http://localhost:3000")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .dependsOn(ENABLE_CORS, "true")
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Default:** http://localhost:3000 (Node.js app default)
**Format:** `http://localhost:3000,http://localhost:3001`

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

---

## Internal APIs

### For Processors to Poll (Python Processors)

#### GET /_internal/poll/{endpoint}

**Purpose:** Retrieve the next queued request for a specific endpoint.

**Example:**
```
GET http://localhost:5050/_internal/poll/api/quality-event
```

**Response (200 OK) - Data available:**
```json
{
  "method": "POST",
  "path": "/api/quality-event",
  "contentType": "application/json",
  "body": "{\"eventType\":\"quality-check\",\"severity\":\"high\"}",
  "timestamp": "2026-01-02T10:30:00.123Z",
  "clientAddress": "127.0.0.1",
  "headers": {
    "content-type": "application/json",
    "user-agent": "Mozilla/5.0",
    "accept": "*/*"
  },
  "pathParameters": {}
}
```

**Response (204 No Content) - Queue empty:**
```
(Empty body)
```

**Response (404 Not Found) - Endpoint not registered:**
```json
{
  "error": "Endpoint not found: /api/unknown"
}
```

#### GET /_internal/endpoints

**Purpose:** List all registered endpoints.

**Response (200 OK):**
```json
{
  "endpoints": [
    "/api/quality-event",
    "/api/user-register",
    "/api/analytics"
  ],
  "count": 3
}
```

---

### Metrics API (For Monitoring)

#### GET /_metrics

**Purpose:** Get metrics for all endpoints.

**Response (200 OK):**
```json
{
  "gateway": {
    "port": 5050,
    "host": "127.0.0.1",
    "uptime": "2h 15m 30s",
    "totalEndpoints": 3
  },
  "endpoints": {
    "/api/quality-event": {
      "totalRequests": 1250,
      "successfulRequests": 1248,
      "failedRequests": 2,
      "queueFullRejections": 0,
      "averageLatencyMs": 12,
      "currentQueueSize": 3,
      "lastRequestTime": "2026-01-02T10:35:22.456Z"
    },
    "/api/user-register": {
      "totalRequests": 45,
      "successfulRequests": 45,
      "failedRequests": 0,
      "queueFullRejections": 0,
      "averageLatencyMs": 8,
      "currentQueueSize": 0,
      "lastRequestTime": "2026-01-02T10:30:15.789Z"
    }
  }
}
```

#### GET /_metrics/{endpoint}

**Purpose:** Get metrics for a specific endpoint.

**Example:**
```
GET http://localhost:5050/_metrics/api/quality-event
```

**Response (200 OK):**
```json
{
  "endpoint": "/api/quality-event",
  "totalRequests": 1250,
  "successfulRequests": 1248,
  "failedRequests": 2,
  "queueFullRejections": 0,
  "averageLatencyMs": 12,
  "currentQueueSize": 3,
  "lastRequestTime": "2026-01-02T10:35:22.456Z",
  "successRate": 99.84
}
```

---

## Request/Response Flow

### Happy Path: Quality Event Submission

```
1. User submits quality event form in Next.js app

2. Frontend JavaScript:
   fetch('http://localhost:5050/api/quality-event', {
     method: 'POST',
     headers: { 'Content-Type': 'application/json' },
     body: JSON.stringify({ eventType: 'quality-check', severity: 'high' })
   })

3. Gateway receives request:
   - Matches endpoint: /api/quality-event
   - Validates: Content-Type, size
   - Queues request data

4. Gateway responds immediately:
   HTTP 202 Accepted
   {"status":"accepted"}

5. Python Processor (100ms later):
   - Polls: GET http://localhost:5050/_internal/poll/api/quality-event
   - Receives: JSON with request data
   - Creates FlowFile with attributes

6. FlowFile flows through NiFi:
   ReceiveFromNodeJSApp → EvaluateJsonPath → PutDatabaseRecord

7. User sees success message in UI
```

---

## Security Considerations

### 1. Network Isolation (Primary Security)

**Default Configuration:**
```
Gateway Host: 127.0.0.1 (localhost only)
Gateway Port: 5050
```

**Security Model:**
- Gateway only accessible from same host
- Node.js app and NiFi both run on localhost
- No external network exposure

**Best Practice:**
```
NodeJSApplicationManagerService environment:
{
  "NODE_ENV": "production",
  "PORT": "3000",
  "NIFI_GATEWAY_URL": "http://127.0.0.1:5050"
}
```

### 2. Request Size Limits

**Protection against DoS:**
```java
if (request.getContentLength() > maxRequestSize) {
    return new GatewayResponse(413, "Payload too large");
}
```

**Default:** 10MB maximum

### 3. Queue Capacity Limits

**Protection against memory exhaustion:**
```java
if (!queue.offer(request)) {
    metrics.recordQueueFull();
    return new GatewayResponse(503, "Service busy, queue full");
}
```

**Default:** 1,000 requests per endpoint

### 4. CORS Security

**Whitelist specific origins:**
```
CORS Allowed Origins: http://localhost:3000
```

**Never use `*` in production**

---

## Integration with NodeJSApplicationManagerService

### Complete Setup Example

**1. NodeJSApplicationManagerService Configuration:**
```
Service Name: quality-event-manager
Application Directory: /opt/nifi/apps/quality-event-system
Application Port: 3000
Start Command: start
Environment Variables:
{
  "NODE_ENV": "production",
  "PORT": "3000",
  "NIFI_GATEWAY_URL": "http://localhost:5050"
}
```

**2. NodeJSAppAPIGateway Configuration:**
```
Service Name: nodejs-gateway
Gateway Port: 5050
Gateway Host: 127.0.0.1
CORS Allowed Origins: http://localhost:3000
Max Queue Size: 1000
```

**3. ReceiveFromNodeJSApp (Python) Configuration:**
```
Processor Name: Receive Quality Events
Gateway URL: http://localhost:5050
Endpoint Path: /api/quality-event
Poll Timeout: 1 sec
```

**4. Node.js Application Code:**
```javascript
// src/lib/nifi.js
const NIFI_GATEWAY_URL = process.env.NIFI_GATEWAY_URL || 'http://localhost:5050';

export async function submitQualityEvent(eventData) {
  const response = await fetch(`${NIFI_GATEWAY_URL}/api/quality-event`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      eventType: eventData.type,
      severity: eventData.severity,
      description: eventData.description,
      timestamp: new Date().toISOString()
    })
  });

  if (response.status === 503) {
    throw new Error('System busy, please try again in a moment');
  }

  if (!response.ok) {
    throw new Error(`Failed to submit event: ${response.status}`);
  }

  return response.json();
}
```

---

## Implementation Details

### Module Structure

```
nodejs-app-gateway/
├── pom.xml (parent)
│
├── nodejs-app-gateway-service-api/          # API Module (JAR)
│   └── src/main/java/.../gateway/
│       ├── NodeJSAppAPIGateway.java         # Interface
│       ├── EndpointHandler.java             # Functional interface
│       ├── GatewayRequest.java              # Request DTO
│       ├── GatewayResponse.java             # Response DTO
│       ├── EndpointMetrics.java             # Metrics DTO
│       ├── EndpointAlreadyRegisteredException.java
│       └── RequestProcessingException.java
│
├── nodejs-app-gateway-service-api-nar/      # API NAR
│
├── nodejs-app-gateway-service/              # Implementation Module (JAR)
│   └── src/main/java/.../gateway/
│       ├── StandardNodeJSAppAPIGateway.java  # Main implementation
│       ├── GatewayServlet.java               # Jetty servlet
│       ├── InternalApiServlet.java           # /_internal/* endpoints
│       ├── MetricsServlet.java               # /_metrics endpoint
│       └── EndpointMatcher.java              # Pattern matching
│
├── nodejs-app-gateway-service-nar/          # Implementation NAR
│
└── processors/
    ├── nodejs-app-gateway-processors/       # Java Processor Module
    │   └── src/main/java/.../processors/
    │       └── ReceiveFromNodeJSApp.java    # Java processor
    │
    └── nodejs-app-gateway-processors-nar/   # Java Processor NAR
```

### Python Processor Location

```
liquid-library/src/native_python_processors/
└── ReceiveFromNodeJSApp/
    ├── __init__.py
    ├── ReceiveFromNodeJSApp.py
    └── requirements.txt              # requests>=2.28.0
```

---

## Testing Requirements

### Unit Tests (Java)

1. **StandardNodeJSAppAPIGatewayTest**
   - Test server start/stop
   - Test endpoint registration
   - Test queue management
   - Test metrics tracking

2. **InternalApiServletTest**
   - Test /_internal/poll/* endpoints
   - Test 204 response when queue empty
   - Test 200 response with data

3. **GatewayServletTest**
   - Test request routing
   - Test CORS headers
   - Test 404 for unknown endpoints
   - Test 503 for full queues

### Integration Tests (Python)

```python
# test_receive_from_nodejs_app.py
import unittest
import requests
import time

class TestReceiveFromNodeJSApp(unittest.TestCase):

    def test_end_to_end_flow(self):
        # 1. Submit request to gateway
        response = requests.post(
            'http://localhost:5050/api/test',
            json={'test': 'data'},
            headers={'Content-Type': 'application/json'}
        )
        self.assertEqual(response.status_code, 202)

        # 2. Poll internal API
        poll_response = requests.get(
            'http://localhost:5050/_internal/poll/api/test',
            timeout=2
        )
        self.assertEqual(poll_response.status_code, 200)

        # 3. Verify data
        data = poll_response.json()
        self.assertEqual(data['method'], 'POST')
        self.assertEqual(data['path'], '/api/test')
        self.assertEqual(data['body'], '{"test":"data"}')
```

---

## Deployment Guide

### Prerequisites

- NiFi 2.6.0+
- Java 21
- Python 3.8+ (for Python processor)
- NodeJSApplicationManagerService (optional but recommended)

### Build

```bash
cd liquid-library/src/java_extensions/nodejs-app-gateway
mvn clean install
```

### Install NARs

```bash
# Copy to NiFi lib directory
cp nodejs-app-gateway-service-api-nar/target/*.nar /opt/nifi/lib/
cp nodejs-app-gateway-service-nar/target/*.nar /opt/nifi/lib/
cp nodejs-app-gateway-processors-nar/target/*.nar /opt/nifi/lib/

# Restart NiFi
/opt/nifi/bin/nifi.sh restart
```

### Install Python Processor

```bash
# Copy to Python processors directory
cp -r liquid-library/src/native_python_processors/ReceiveFromNodeJSApp \
      /opt/nifi/python_extensions/

# NiFi will auto-discover on next scan
```

### Configuration

1. Add `StandardNodeJSAppAPIGateway` controller service
2. Set Gateway Port: 5050
3. Set CORS Allowed Origins: http://localhost:3000
4. Enable the service
5. Add `ReceiveFromNodeJSApp` processor (Java or Python)
6. Connect to downstream processors

---

## Examples

### Example 1: Quality Event System

**Frontend (Next.js):**
```typescript
// app/api/submit-event.ts
export async function POST(request: Request) {
  const event = await request.json();

  const response = await fetch('http://localhost:5050/api/quality-event', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(event)
  });

  return Response.json({ success: true });
}
```

**NiFi Flow:**
```
ReceiveFromNodeJSApp (Python)
  endpoint: /api/quality-event
  ↓
EvaluateJsonPath
  eventType: $.eventType
  severity: $.severity
  ↓
RouteOnAttribute
  ${severity} == 'high'
  ↓ [high]
PutDatabaseRecord + SendEmail
  ↓ [medium/low]
PutDatabaseRecord
```

---

## Next Steps

1. ✅ **Specification approved** - Ready for implementation
2. **Create feature branch:** `feat/nodejs-app-gateway-v1.0.0`
3. **Implement API module** - Interfaces and DTOs
4. **Implement gateway service** - Jetty server with all APIs
5. **Implement Java processor** - ReceiveFromNodeJSApp.java
6. **Implement Python processor** - ReceiveFromNodeJSApp.py
7. **Write tests** - Unit and integration
8. **Deploy to liquid-playground** - Test with quality-event-system
9. **Documentation** - User guide

---

*End of Specification Document*
