# NodeJSAppAPIGateway - Executive Summary

**Version:** 1.0.0
**Date:** 2026-01-02
**Status:** ✅ Approved - Ready for Implementation
**Estimated Duration:** 2-3 weeks

---

## What Problem Does This Solve?

Currently, connecting a Node.js frontend (Quality Event System) to NiFi requires:
- 5-6 processors per endpoint
- Complex routing logic with RouteOnAttribute
- HandleHTTPRequest/Response processor pairs
- Manual request/response correlation

**This is too complex for simple form submissions and API calls.**

---

## The Solution

**NodeJSAppAPIGateway** provides a lightweight HTTP server (port 5050) that:
- Accepts requests from Node.js apps
- Automatically routes to appropriate processors
- Eliminates routing complexity
- Reduces processor count from 5-6 to just 2

### Before (HandleHTTP Pattern)
```
POST from frontend
  ↓
HandleHTTPRequest
  ↓
RouteOnAttribute (check path)
  ↓
ExtractText / EvaluateJsonPath
  ↓
PutDatabaseRecord
  ↓
HandleHTTPResponse
```
**5-6 processors + routing logic**

### After (Gateway Pattern)
```
POST from frontend
  ↓
NodeJSAppAPIGateway (auto-routes)
  ↓
ReceiveFromNodeJSApp (Python or Java)
  ↓
PutDatabaseRecord
```
**2 processors + zero routing**

---

## Key Features

### 1. Simple Integration
```javascript
// In Node.js app
fetch('http://localhost:5050/api/quality-event', {
  method: 'POST',
  body: JSON.stringify(eventData)
})
```

### 2. Processor Choice

**Java Processor (High Performance):**
- Direct queue access
- 0ms latency
- Best for high-throughput

**Python Processor (Easy Maintenance):**
- HTTP polling (100ms latency)
- Easy to customize
- Best for most use cases

### 3. Built-in Monitoring
```bash
curl http://localhost:5050/_metrics
```
Returns request counts, latencies, queue sizes per endpoint.

### 4. Safe Defaults
- Port 5050 (non-standard)
- Localhost only (127.0.0.1)
- CORS enabled for browser apps
- 1000 request queue per endpoint
- 10MB max request size

---

## Architecture

```
┌─────────────────────────────────────────────┐
│  Node.js App (port 3000)                    │
│  - Quality Event System                     │
│  - Environment: NIFI_GATEWAY_URL=:5050      │
└────────────────┬────────────────────────────┘
                 │
                 │ HTTP POST
                 ↓
┌─────────────────────────────────────────────┐
│  NodeJSAppAPIGateway (Java, port 5050)      │
│  - Embedded Jetty HTTP server               │
│  - Per-endpoint queues (1000 capacity)      │
│  - Metrics tracking                         │
│  - CORS support                             │
└────────────────┬────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────┐
│  Processor (choose one):                    │
│                                             │
│  Option A: Java Processor                   │
│  - Direct queue access                      │
│  - 0ms latency                              │
│                                             │
│  Option B: Python Processor                 │
│  - HTTP polling                             │
│  - 100ms latency                            │
│  - Easier to customize                      │
└────────────────┬────────────────────────────┘
                 │
                 ↓
┌─────────────────────────────────────────────┐
│  Standard NiFi Processors                   │
│  - ValidateRecord                           │
│  - PutDatabaseRecord                        │
│  - etc.                                     │
└─────────────────────────────────────────────┘
```

---

## Configuration Example

### 1. Gateway Service
```
Service Name: nodejs-gateway
Port: 5050
Host: 127.0.0.1
CORS Origins: http://localhost:3000
Max Queue Size: 1000
```

### 2. Python Processor
```
Processor Name: Receive Quality Events
Gateway URL: http://localhost:5050
Endpoint Path: /api/quality-event
Poll Timeout: 1 sec
```

### 3. Node.js App
```javascript
const NIFI_GATEWAY_URL = process.env.NIFI_GATEWAY_URL;

async function submitEvent(data) {
  await fetch(`${NIFI_GATEWAY_URL}/api/quality-event`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  });
}
```

---

## Technical Decisions (All Approved)

| Decision | Choice | Why |
|----------|--------|-----|
| **Port** | 5050 | Non-standard, avoids conflicts |
| **Host** | 127.0.0.1 | Security - localhost only |
| **Processor Options** | Both Java & Python | Flexibility |
| **Schema Validation** | Processor-level | Use existing ValidateRecord |
| **Response Model** | Synchronous | Simpler, adequate for use case |
| **Path Parameters** | Supported | Common pattern (`/api/user/{id}`) |
| **Authentication** | Not in v1.0 | Add in v1.1 if needed |
| **Metrics** | Yes | Essential for monitoring |
| **Queue Size** | 1000/endpoint | Prevent memory exhaustion |
| **Max Request** | 10 MB | Prevent DoS |

---

## Implementation Phases

### Week 1: Core Gateway (Java)
- API module (interfaces, DTOs)
- Gateway service with Jetty
- Internal polling API
- Metrics API
- Unit tests

### Week 2: Processors
- Java processor (high performance)
- Python processor (easy maintenance)
- Integration tests

### Week 3: Testing & Docs
- End-to-end testing with quality-event-system
- Performance testing
- Documentation
- Deployment to liquid-playground

---

## Deliverables

### Software
1. **nodejs-app-gateway-service-api-nar** (API definitions)
2. **nodejs-app-gateway-service-nar** (Gateway implementation)
3. **nodejs-app-gateway-processors-nar** (Java processor)
4. **ReceiveFromNodeJSApp.py** (Python processor)

### Documentation
1. **SPECIFICATION_NodeJSAppAPIGateway.md** (Complete technical spec)
2. **IMPLEMENTATION_PLAN_NodeJSAppAPIGateway.md** (This document)
3. **USER_GUIDE.md** (How to use)
4. **API.md** (API reference)

---

## Success Criteria

### Functional ✅
- Gateway accepts requests on port 5050
- Both processors create FlowFiles correctly
- Queue limits enforced
- CORS works
- Metrics accurate
- Works with quality-event-system

### Performance ✅
- Java processor: <5ms latency
- Python processor: <200ms latency
- Throughput: >100 requests/sec

### Quality ✅
- All tests pass
- No security vulnerabilities
- Complete documentation
- Follows NiFi patterns

---

## Use Cases

### 1. Quality Event System (Primary)
```
User fills form → Submit → NiFi → Database
```
**Benefit:** 60% fewer processors

### 2. User Registration
```
Registration form → NiFi validation → Database + Email
```
**Benefit:** No routing logic needed

### 3. Analytics Events
```
Frontend clicks → NiFi → S3 + Kafka
```
**Benefit:** High throughput with batching

---

## Security Model

**Network Isolation (Primary):**
- Gateway binds to 127.0.0.1 (localhost only)
- Node.js app and NiFi on same host
- No external exposure

**Request Limits:**
- 10MB max request size
- 1000 requests per queue
- 30 second timeout

**CORS Whitelisting:**
- Specific origins only
- Never use `*` in production

**Future (v1.1):**
- API key authentication
- Rate limiting

---

## Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Jetty conflicts | Low | High | Use NiFi's Jetty version |
| Python polling overhead | Low | Medium | Acceptable for use case |
| Queue memory usage | Medium | Medium | Size limits + monitoring |
| CORS issues | Low | Low | Well-documented |

**Overall Risk:** LOW ✅

---

## Comparison to Alternatives

### vs HandleHTTP
| Feature | HandleHTTP | Gateway |
|---------|-----------|---------|
| Processors needed | 5-6 | 2 |
| Routing logic | Manual | Automatic |
| Setup complexity | High | Low |
| Maintenance | Complex | Simple |

**Winner:** Gateway 🏆

### vs Direct Database Access
| Feature | Direct DB | Gateway |
|---------|----------|---------|
| Data validation | Frontend | NiFi (better) |
| Processing | None | Full NiFi power |
| Monitoring | Limited | Complete |
| Flexibility | Low | High |

**Winner:** Gateway 🏆

---

## Timeline

```
Week 1:
  Day 1-2: API module + POMs
  Day 3-5: Gateway service implementation

Week 2:
  Day 1-2: Java processor
  Day 3-4: Python processor
  Day 5: Integration tests

Week 3:
  Day 1-2: End-to-end testing
  Day 3-4: Documentation
  Day 5: Final deployment + handoff
```

**Target Completion:** 2026-01-20

---

## Next Steps (Immediate)

1. ✅ **Specification approved** (DONE)
2. **Create git branch:** `feat/nodejs-app-gateway-v1.0.0`
3. **Set up module structure**
4. **Implement Phase 1** (API module)
5. **Weekly status updates**

---

## Questions?

- **Full spec:** See SPECIFICATION_NodeJSAppAPIGateway.md
- **Implementation details:** See IMPLEMENTATION_PLAN_NodeJSAppAPIGateway.md
- **Similar patterns:** See nodejs-app-manager project

---

## Approval Status

- ✅ Architecture approved
- ✅ Technical decisions confirmed
- ✅ Implementation plan reviewed
- ✅ Ready to start development

**Approved by:** User
**Date:** 2026-01-02

---

*Let's build this! 🚀*