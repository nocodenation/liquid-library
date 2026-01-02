# NodeJSAppAPIGateway - Implementation Plan

**Version:** 1.0.0-SNAPSHOT
**Date:** 2026-01-02
**Status:** Approved - Ready to Start
**Estimated Effort:** 2-3 weeks

---

## Overview

Implementation plan for the NodeJSAppAPIGateway controller service, which provides HTTP gateway functionality for Node.js ↔ NiFi communication. This replaces complex HandleHTTP patterns with simple declarative endpoint registration.

**Key Decision:** Both Java and Python processor options will be implemented to provide flexibility.

---

## Approved Specifications

| Item | Decision |
|------|----------|
| Gateway Port | 5050 (non-standard) |
| Gateway Host | 127.0.0.1 (localhost only) |
| Schema Validation | Processor-level (use ValidateRecord) |
| Response Model | Synchronous only (202 Accepted) |
| Path Parameters | Supported (`/api/user/{userId}`) |
| HTTP Methods | Single handler for all methods |
| Authentication | Not in v1.0.0 (add in v1.1.0) |
| Metrics Endpoint | Yes (`/_metrics`) |
| Processor Options | Both Java and Python |
| Polling Interval | 100ms (Python processor) |
| Queue Size | 1000 requests per endpoint |
| Max Request Size | 10 MB |

---

## Architecture Summary

```
Node.js App (port 3000)
  ↓ HTTP POST
NodeJSAppAPIGateway (Java, port 5050)
  ├─ Public API: /api/* (for Node.js)
  ├─ Internal API: /_internal/poll/* (for Python processors)
  └─ Metrics API: /_metrics (for monitoring)
  ↓
Processor Options:
  ├─ Java: Direct queue access (0ms latency)
  └─ Python: HTTP polling (100ms latency)
  ↓
FlowFile → Standard NiFi processors
```

---

## Phase 1: API Module (Java) - Week 1

**Goal:** Define all interfaces and DTOs that both the service and processors will use.

### Tasks

#### 1.1 Create Module Structure
```bash
cd liquid-library/src/java_extensions
mkdir -p nodejs-app-gateway/nodejs-app-gateway-service-api/src/main/java/org/nocodenation/nifi/nodejsapp/gateway
mkdir -p nodejs-app-gateway/nodejs-app-gateway-service-api-nar
```

#### 1.2 Implement Core Interfaces

**Files to create:**
- `NodeJSAppAPIGateway.java` - Main controller service interface
- `EndpointHandler.java` - Functional interface for Java processors
- `GatewayRequest.java` - Request DTO with builder
- `GatewayResponse.java` - Response DTO with factory methods
- `EndpointMetrics.java` - Metrics DTO
- `EndpointAlreadyRegisteredException.java` - Custom exception
- `RequestProcessingException.java` - Custom exception

**Key APIs:**
```java
public interface NodeJSAppAPIGateway extends ControllerService {
    int getGatewayPort();
    String getGatewayUrl();
    void registerEndpoint(String pattern, EndpointHandler handler);
    void unregisterEndpoint(String pattern);
    List<String> getRegisteredEndpoints();
    EndpointMetrics getEndpointMetrics(String pattern);
}

@FunctionalInterface
public interface EndpointHandler {
    GatewayResponse handleRequest(GatewayRequest request);
}
```

#### 1.3 Create POM Files

**Parent POM:**
```xml
<groupId>org.nocodenation.nifi</groupId>
<artifactId>nodejs-app-gateway</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>pom</packaging>

<modules>
    <module>nodejs-app-gateway-service-api</module>
    <module>nodejs-app-gateway-service-api-nar</module>
    <module>nodejs-app-gateway-service</module>
    <module>nodejs-app-gateway-service-nar</module>
    <module>nodejs-app-gateway-processors</module>
    <module>nodejs-app-gateway-processors-nar</module>
</modules>
```

#### 1.4 Test Compilation

```bash
cd nodejs-app-gateway
mvn clean compile
# Should compile successfully with no implementation yet
```

**Deliverable:** API JAR and NAR compile successfully.

---

## Phase 2: Gateway Service (Java) - Week 1-2

**Goal:** Implement the gateway service with embedded Jetty HTTP server.

### Tasks

#### 2.1 Create Service Implementation

**File:** `StandardNodeJSAppAPIGateway.java`

**Key components:**
- Jetty server initialization
- Endpoint registry (ConcurrentHashMap)
- Per-endpoint queues (LinkedBlockingQueue)
- Per-endpoint metrics (EndpointMetrics)
- @OnEnabled / @OnDisabled lifecycle

#### 2.2 Create Servlet for Public API

**File:** `GatewayServlet.java`

**Responsibilities:**
- Handle requests to `/api/*`
- Match endpoint patterns
- Validate Content-Type, size
- Queue requests
- Return 202 Accepted or 503 Queue Full
- Add CORS headers if enabled

#### 2.3 Create Servlet for Internal API

**File:** `InternalApiServlet.java`

**Endpoints:**
```
GET /_internal/poll/{endpoint}
  → 200 OK with JSON (if data available)
  → 204 No Content (if queue empty)
  → 404 Not Found (if endpoint not registered)

GET /_internal/endpoints
  → 200 OK with list of registered endpoints
```

#### 2.4 Create Servlet for Metrics

**File:** `MetricsServlet.java`

**Endpoints:**
```
GET /_metrics
  → 200 OK with all endpoint metrics

GET /_metrics/{endpoint}
  → 200 OK with specific endpoint metrics
```

#### 2.5 Create Endpoint Matcher

**File:** `EndpointMatcher.java`

**Supports:**
- Exact match: `/api/quality-event`
- Wildcard: `/api/events/*`
- Path parameters: `/api/user/{userId}`

#### 2.6 Configuration Properties

Implement all PropertyDescriptors:
- GATEWAY_PORT (default: 5050)
- GATEWAY_HOST (default: 127.0.0.1)
- MAX_QUEUE_SIZE (default: 1000)
- ENABLE_CORS (default: true)
- CORS_ALLOWED_ORIGINS (default: http://localhost:3000)
- REQUEST_TIMEOUT (default: 30 sec)
- MAX_REQUEST_SIZE (default: 10 MB)

#### 2.7 Unit Tests

**Tests to write:**
- `StandardNodeJSAppAPIGatewayTest`
  - Test server start/stop
  - Test endpoint registration/unregistration
  - Test duplicate endpoint handling
  - Test metrics tracking

- `GatewayServletTest`
  - Test request routing
  - Test 404 for unknown endpoints
  - Test 503 for full queues
  - Test CORS headers

- `InternalApiServletTest`
  - Test polling with data
  - Test polling with empty queue
  - Test endpoint listing

- `MetricsServletTest`
  - Test metrics retrieval
  - Test metrics accuracy

**Deliverable:** Gateway service NAR that can be deployed to NiFi.

---

## Phase 3: Java Processor - Week 2

**Goal:** Implement high-performance Java processor for direct queue access.

### Tasks

#### 3.1 Create Processor Module

```bash
mkdir -p nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/processors
mkdir -p nodejs-app-gateway/nodejs-app-gateway-processors-nar
```

#### 3.2 Implement Processor

**File:** `ReceiveFromNodeJSApp.java`

**Key features:**
- PropertyDescriptor for gateway service reference
- PropertyDescriptor for endpoint path
- @OnScheduled: Register endpoint with gateway
- @OnTrigger: Poll internal queue, create FlowFiles
- @OnStopped: Unregister endpoint

**Attributes added to FlowFile:**
- `endpoint.path`
- `http.method`
- `content.type`
- `request.timestamp`
- `client.address`
- `path.param.*` (if path parameters present)
- `http.header.*` (all headers)

#### 3.3 Unit Tests

**Test:** `ReceiveFromNodeJSAppTest`
- Test endpoint registration
- Test FlowFile creation
- Test attribute mapping
- Test queue handling

**Deliverable:** Java processor NAR ready for deployment.

---

## Phase 4: Python Processor - Week 2

**Goal:** Implement Python processor for easy customization.

### Tasks

#### 4.1 Create Processor Directory

```bash
cd liquid-library/src/native_python_processors
mkdir -p ReceiveFromNodeJSApp
```

#### 4.2 Implement Processor

**File:** `ReceiveFromNodeJSApp/ReceiveFromNodeJSApp.py`

**Key features:**
- FlowFileSource base class
- Property: Gateway URL (default: http://localhost:5050)
- Property: Endpoint Path
- Property: Poll Timeout (default: 1 sec)
- onScheduled: Initialize configuration
- create: Poll /_internal/poll/{endpoint}, create FlowFile

**Dependencies:** `requirements.txt`
```
requests>=2.28.0
```

#### 4.3 Create __init__.py

```python
from .ReceiveFromNodeJSApp import ReceiveFromNodeJSApp
```

#### 4.4 Test Manually

Since Python processors don't have standard unit tests:
1. Deploy to NiFi
2. Test polling with curl
3. Verify FlowFile creation
4. Test error handling

**Deliverable:** Python processor ready for deployment.

---

## Phase 5: Integration Testing - Week 3

**Goal:** Test complete end-to-end flow with quality-event-system.

### Tasks

#### 5.1 Deploy to liquid-playground

```bash
# Build all modules
cd liquid-library/src/java_extensions/nodejs-app-gateway
mvn clean install

# Copy NARs to playground
cp */target/*.nar $LIQUID_PLAYGROUND_HOME/files/

# Copy Python processor
cp -r ../../native_python_processors/ReceiveFromNodeJSApp \
      $LIQUID_PLAYGROUND_HOME/python_extensions/

# Rebuild Docker image
cd $LIQUID_PLAYGROUND_HOME
./build.sh
./start.sh
```

#### 5.2 Configure Services

**In NiFi UI:**
1. Add `StandardNodeJSAppAPIGateway` controller service
   - Gateway Port: 5050
   - CORS Allowed Origins: http://localhost:3000
2. Add `StandardNodeJSApplicationManagerService`
   - Application Port: 3000
   - Environment: `{"NIFI_GATEWAY_URL":"http://localhost:5050"}`
3. Enable both services

#### 5.3 Create Test Flow

```
ReceiveFromNodeJSApp (Python)
  endpoint: /api/quality-event
  ↓
LogAttribute (debug)
  ↓
EvaluateJsonPath
  eventType: $.eventType
  severity: $.severity
  ↓
PutFile (temporary storage)
```

#### 5.4 Test Scenarios

**Test 1: Basic submission**
```bash
curl -X POST http://localhost:5050/api/quality-event \
  -H "Content-Type: application/json" \
  -d '{"eventType":"test","severity":"low"}'
```

Expected: 202 Accepted, FlowFile created

**Test 2: Queue full**
```bash
# Submit 1001 requests rapidly
for i in {1..1001}; do
  curl -X POST http://localhost:5050/api/quality-event \
    -H "Content-Type: application/json" \
    -d '{"test":'$i'}' &
done
wait
```

Expected: Some 503 responses

**Test 3: Unknown endpoint**
```bash
curl -X POST http://localhost:5050/api/unknown
```

Expected: 404 Not Found

**Test 4: Metrics**
```bash
curl http://localhost:5050/_metrics
```

Expected: JSON with metrics

**Test 5: From Node.js app**
- Deploy quality-event-system
- Submit form
- Verify data reaches NiFi

#### 5.5 Performance Testing

**Measure:**
- Latency: Request → FlowFile creation
- Throughput: Requests per second
- Queue behavior: What happens under load?

**Tools:**
```bash
# Apache Bench
ab -n 1000 -c 10 -p event.json -T application/json \
   http://localhost:5050/api/quality-event

# Verify all requests processed
curl http://localhost:5050/_metrics/api/quality-event
```

**Deliverable:** Complete integration test report with performance metrics.

---

## Phase 6: Documentation - Week 3

### Tasks

#### 6.1 User Guide

**File:** `liquid-library/src/java_extensions/nodejs-app-gateway/USER_GUIDE.md`

**Contents:**
- Quick start
- Configuration examples
- Processor comparison (Java vs Python)
- Troubleshooting
- Performance tuning

#### 6.2 Update README

**File:** `liquid-library/src/java_extensions/nodejs-app-gateway/README.md`

Add:
- Link to USER_GUIDE.md
- Link to SPECIFICATION.md
- Quick examples

#### 6.3 API Documentation

**File:** `liquid-library/src/java_extensions/nodejs-app-gateway/API.md`

Document:
- Public API endpoints
- Internal API endpoints
- Metrics API endpoints
- Request/response formats

**Deliverable:** Complete documentation set.

---

## Deployment Checklist

- [ ] All Java modules compile successfully
- [ ] All unit tests pass
- [ ] Python processor tested manually
- [ ] NARs built (3 files total)
- [ ] NARs deployed to liquid-playground
- [ ] Python processor deployed
- [ ] Controller services configured and enabled
- [ ] Test flow created
- [ ] All test scenarios pass
- [ ] Performance metrics acceptable
- [ ] Documentation complete
- [ ] Committed to git
- [ ] Pushed to remote branch

---

## Success Criteria

### Functional
- ✅ Gateway accepts HTTP requests on port 5050
- ✅ Java processor creates FlowFiles with correct attributes
- ✅ Python processor creates FlowFiles with correct attributes
- ✅ Queue capacity limits enforced (503 responses)
- ✅ CORS headers present
- ✅ Metrics endpoint returns accurate data
- ✅ Path parameters extracted correctly
- ✅ Works with quality-event-system

### Performance
- ✅ Java processor: <5ms latency
- ✅ Python processor: <200ms latency
- ✅ Throughput: >100 requests/sec
- ✅ Memory: <100MB for 1000 queued requests

### Quality
- ✅ All unit tests pass
- ✅ No critical security vulnerabilities
- ✅ Documentation complete
- ✅ Code follows NiFi patterns

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Jetty version conflicts | HIGH | Use NiFi's included Jetty version |
| Python polling overhead | MEDIUM | Acceptable for use case, document limitation |
| Queue memory usage | MEDIUM | Enforce size limits, monitor metrics |
| CORS complexity | LOW | Well-documented feature, many examples |
| Path parameter parsing | LOW | Use proven regex patterns |

---

## Next Steps After v1.0.0

**v1.1.0 Features (Future):**
- API key authentication
- Schema validation at gateway level
- Request retry with backoff
- Async response support
- WebSocket support for real-time events
- Circuit breaker pattern
- Rate limiting per endpoint

---

## Contact and Support

**Questions during implementation:**
- Review SPECIFICATION_NodeJSAppAPIGateway.md for details
- Reference nodejs-app-manager for similar patterns
- Check NiFi 2.6.0 documentation for controller service patterns

**Testing:**
- Use quality-event-system as primary test application
- Document any issues in BACKLOG.md

---

*Implementation started: 2026-01-02*
*Target completion: 2026-01-20*