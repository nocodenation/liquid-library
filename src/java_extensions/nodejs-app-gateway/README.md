# NodeJS Application API Gateway

A NiFi controller service and processors for receiving HTTP requests from Node.js applications with minimal configuration.

## Overview

The NodeJS Application API Gateway provides a lightweight HTTP server that Node.js applications can send requests to, eliminating the need for complex HandleHTTP configurations. It supports both Java and Python processors with automatic queuing, backpressure handling, and comprehensive metrics.

### Key Features

- **Simple HTTP Gateway**: Embedded Jetty server on configurable port (default: 5050)
- **Path Parameters**: Support for patterns like `/users/:userId/posts/:postId`
- **Dual Integration**: Both Java (zero-latency) and Python (polling) processors
- **Automatic Queuing**: Built-in request queuing with configurable size limits
- **Metrics Endpoint**: Real-time statistics at `/_metrics`
- **CORS Support**: Configurable cross-origin resource sharing for browser apps
- **Backpressure Handling**: Automatic 503 responses when queues are full

## Architecture

```
Node.js App (port 3000)
        ↓
  HTTP POST /api/events
        ↓
NodeJSAppAPIGateway (port 5050)
        ↓
   ┌────┴────┐
   │         │
Java        Python
Processor   Processor
(direct)    (polling)
   │         │
   └────┬────┘
        ↓
   NiFi Flows
```

### Components

1. **NodeJSAppAPIGateway** (Controller Service)
   - Runs embedded HTTP server
   - Manages endpoint registrations
   - Handles request queuing
   - Provides metrics

2. **ReceiveFromNodeJSApp** (Java Processor)
   - Direct queue access (zero latency)
   - Registers endpoints on start
   - Creates FlowFiles from requests

3. **ReceiveFromNodeJSApp** (Python Processor)
   - Polls internal API (~100ms latency)
   - Simpler to customize
   - Same FlowFile output as Java processor

## Installation

### Build from Source

```bash
cd nodejs-app-gateway
mvn clean package
```

### Deploy to NiFi

Copy the NAR files to NiFi's `lib` directory:

```bash
cp nodejs-app-gateway-service-nar/target/*.nar $NIFI_HOME/lib/
cp nodejs-app-gateway-processors-nar/target/*.nar $NIFI_HOME/lib/
```

For Python processor, ensure the Python directory is in NiFi's python extensions path:

```bash
# In nifi.properties
nifi.python.extensions.source.directory.default=/path/to/liquid-library/src/native_python_processors
```

Restart NiFi after deployment.

## Configuration

### 1. Configure the Gateway Service

Add a NodeJSAppAPIGateway controller service:

| Property | Default | Description |
|----------|---------|-------------|
| Gateway Port | 5050 | HTTP server port |
| Maximum Queue Size | 100 | Max requests per endpoint |
| Maximum Request Size | 10485760 | Max body size (10 MB) |
| Enable CORS | true | Allow cross-origin requests |

### 2. Add a Processor

#### Java Processor (Recommended for Performance)

Add **ReceiveFromNodeJSApp** processor:

| Property | Example | Description |
|----------|---------|-------------|
| Gateway Service | NodeJSAppAPIGateway | Controller service reference |
| Endpoint Pattern | /api/events | URL pattern to handle |
| Response Status Code | 202 | HTTP status to return |
| Response Body | (optional) | Optional response body |

#### Python Processor (Recommended for Customization)

Add **ReceiveFromNodeJSApp** (Python) processor:

| Property | Example | Description |
|----------|---------|-------------|
| Gateway URL | http://localhost:5050 | Gateway base URL |
| Endpoint Pattern | /api/events | URL pattern to poll for |
| Poll Timeout | 30 | Long-poll timeout (seconds) |

### 3. Configure Node.js Application

Set the gateway URL as an environment variable:

```javascript
// In your Node.js app
const GATEWAY_URL = process.env.NIFI_GATEWAY_URL || 'http://localhost:5050';

// Send events
async function sendEvent(eventData) {
  const response = await fetch(`${GATEWAY_URL}/api/events`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(eventData)
  });

  if (response.status === 202) {
    console.log('Event accepted by NiFi');
  } else if (response.status === 503) {
    console.warn('NiFi queue full, retry later');
  }
}
```

## Endpoint Patterns

The gateway supports path parameters in URL patterns:

| Pattern | Matches | Path Parameters |
|---------|---------|-----------------|
| `/api/events` | Exact match only | None |
| `/users/:userId` | `/users/123` | `userId=123` |
| `/users/:userId/posts/:postId` | `/users/123/posts/456` | `userId=123, postId=456` |

Path parameters are available as FlowFile attributes: `http.path.userId`, `http.path.postId`

## FlowFile Attributes

All processors create FlowFiles with these attributes:

| Attribute | Example | Description |
|-----------|---------|-------------|
| `http.method` | POST | HTTP method |
| `http.path` | /api/events | Request path |
| `http.query.*` | `http.query.id=123` | Query parameters |
| `http.path.*` | `http.path.userId=456` | Path parameters |
| `http.header.*` | `http.header.content-type` | HTTP headers (lowercase) |
| `http.client.address` | 127.0.0.1 | Client IP address |
| `http.timestamp` | 2024-01-15T10:30:45Z | Request timestamp (ISO-8601) |

FlowFile content contains the request body (if any).

## Monitoring

### Metrics Endpoint

Access real-time metrics at: `http://localhost:5050/_metrics`

```json
{
  "endpoints": {
    "/api/events": {
      "totalRequests": 1250,
      "successfulRequests": 1200,
      "failedRequests": 30,
      "queueFullRejections": 20,
      "averageLatencyMs": 15,
      "currentQueueSize": 5,
      "successRate": 96.0,
      "lastRequestTime": "2024-01-15T10:30:45Z"
    }
  }
}
```

### Response Codes

| Code | Meaning | Action |
|------|---------|--------|
| 202 | Accepted | Request queued successfully |
| 400 | Bad Request | Invalid request format |
| 404 | Not Found | Endpoint not registered |
| 413 | Payload Too Large | Request body exceeds limit |
| 503 | Service Unavailable | Queue full, retry later |

## Examples

### Quality Event System

```xml
<!-- Controller Service -->
<controllerService>
  <id>nodejs-gateway</id>
  <class>org.nocodenation.nifi.nodejsapp.gateway.StandardNodeJSAppAPIGateway</class>
  <property name="Gateway Port">5050</property>
  <property name="Maximum Queue Size">1000</property>
</controllerService>

<!-- Processor -->
<processor>
  <class>org.nocodenation.nifi.nodejsapp.gateway.ReceiveFromNodeJSApp</class>
  <property name="Gateway Service">nodejs-gateway</property>
  <property name="Endpoint Pattern">/api/quality-event</property>
</processor>
```

Node.js code:

```javascript
fetch('http://localhost:5050/api/quality-event', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    eventType: 'DEFECT_DETECTED',
    severity: 'HIGH',
    productId: 'PROD-12345',
    timestamp: new Date().toISOString()
  })
});
```

### User Management with Path Parameters

```xml
<processor>
  <property name="Endpoint Pattern">/users/:userId/profile</property>
</processor>
```

Node.js code:

```javascript
// PUT /users/123/profile
fetch('http://localhost:5050/users/123/profile', {
  method: 'PUT',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ name: 'John Doe', email: 'john@example.com' })
});

// In NiFi, FlowFile will have:
// http.path.userId = "123"
// http.method = "PUT"
```

## Performance

### Java Processor
- **Latency**: Sub-millisecond (direct queue access)
- **Throughput**: 10,000+ requests/second
- **Use Case**: High-performance, production workloads

### Python Processor
- **Latency**: ~100ms (HTTP polling with 100ms interval)
- **Throughput**: 500-1,000 requests/second
- **Use Case**: Rapid development, custom logic, lower volume

## Troubleshooting

### Queue Full (503) Responses

Increase queue size or add more processor threads:

```xml
<property name="Maximum Queue Size">5000</property>
```

### Endpoint Not Found (404)

Verify:
1. Processor is running
2. Gateway service is enabled
3. Endpoint pattern matches exactly

### Python Processor Not Polling

Check:
1. Gateway URL is correct
2. Endpoint pattern is URL-encoded properly
3. Gateway service is accessible from NiFi

### Port Already in Use

Change the gateway port:

```xml
<property name="Gateway Port">5051</property>
```

## API Reference

### Public API

All endpoints except `/_internal/*` and `/_metrics`:

```
POST /api/events
GET  /users/:userId
PUT  /users/:userId/profile
```

### Internal API (Python Processors)

```
GET /_internal/poll/:pattern    # Long-poll for requests
POST /_internal/respond/:id     # Submit response (future)
```

### Metrics API

```
GET /_metrics                   # All endpoint metrics
```

## Development

### Project Structure

```
nodejs-app-gateway/
├── nodejs-app-gateway-service-api/          # Public interfaces
│   └── src/main/java/.../gateway/
│       ├── NodeJSAppAPIGateway.java         # Controller service interface
│       ├── EndpointHandler.java             # Handler functional interface
│       ├── GatewayRequest.java              # Request DTO
│       ├── GatewayResponse.java             # Response DTO
│       ├── EndpointMetrics.java             # Metrics DTO
│       └── *Exception.java                  # Exception classes
│
├── nodejs-app-gateway-service/              # Implementation
│   └── src/main/java/.../gateway/
│       ├── StandardNodeJSAppAPIGateway.java # Service implementation
│       ├── GatewayServlet.java              # Public API handler
│       ├── InternalApiServlet.java          # Python polling API
│       ├── MetricsServlet.java              # Metrics endpoint
│       └── EndpointMatcher.java             # Pattern matching utility
│
├── nodejs-app-gateway-processors/           # Java processor
│   └── src/main/java/.../gateway/
│       └── ReceiveFromNodeJSApp.java        # Java processor
│
└── [Python processor location]
    └── native_python_processors/
        └── ReceiveFromNodeJSApp/
            └── ReceiveFromNodeJSApp.py      # Python processor
```

### Build Commands

```bash
# Clean build
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Build specific module
cd nodejs-app-gateway-service
mvn clean install
```

## License

Licensed under the Apache License, Version 2.0.

## See Also

- [SPECIFICATION_NodeJSAppAPIGateway.md](SPECIFICATION_NodeJSAppAPIGateway.md) - Detailed specification
- [IMPLEMENTATION_PLAN_NodeJSAppAPIGateway.md](IMPLEMENTATION_PLAN_NodeJSAppAPIGateway.md) - Implementation plan
- [BACKLOG_NodeJSAppAPIGateway.md](BACKLOG_NodeJSAppAPIGateway.md) - Future enhancements
