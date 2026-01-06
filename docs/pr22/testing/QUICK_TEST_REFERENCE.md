# Quick Test Reference Card - PR #22

## ✅ Test A: nocodenation Tags (COMPLETED)

**Status:** PASSED - See [PR22_TEST_RESULTS.md](PR22_TEST_RESULTS.md)

**What was verified:**
- Build compiles successfully
- Tags present in 4 source files
- Tags preserved in compiled bytecode

---

## Test B: RespondWithTimestamp (GAT-7)

**Quick Test:**
```bash
# 1. Start NiFi with deployed NARs
# 2. Add NodeJSAppAPIGateway controller service
# 3. Add RespondWithTimestamp processor, configure:
#    - Gateway Service: <your gateway>
#    - Endpoint Pattern: /api/time
# 4. Test:
curl http://localhost:5050/api/time

# Expected Response:
{
  "timestamp": "2026-01-06T19:20:45.123Z",
  "epochMillis": 1736189245123,
  "formatted": "2026-01-06 19:20:45 UTC",
  "endpoint": "/api/time",
  "method": "GET"
}
```

---

## Test C: MIME Type (GAT-9)

**Quick Test:**
```bash
# Send request with Content-Type
curl -X POST http://localhost:5050/api/test \
  -H "Content-Type: application/json; charset=utf-8" \
  -d '{"test": true}'

# Check FlowFile attributes in NiFi:
# Expected: mime.type = "application/json"
```

---

## Test D: Python Error Attributes (GAT-12-16)

**Quick Test:**
```bash
# Scenario 1: Invalid gateway URL
# Configure Python processor with Gateway URL: http://invalid:9999
# Expected FlowFile failure attributes:
# - error.message
# - error.type = "URLError"
# - error.gateway.url

# Scenario 2: Unregistered endpoint
# Configure with Endpoint Pattern: /not-registered
# Expected failure attributes:
# - error.message
# - error.type = "EndpointNotFound"
# - error.http.code = "404"
```

---

## Test E: no_data Relationship (GAT-17)

**Quick Test:**
```bash
# 1. Add ReceiveFromNodeJSApp (Python) to canvas
# 2. Verify it has 3 relationships:
#    - success
#    - failure
#    - no_data
# 3. Start processor WITHOUT sending any requests
# 4. Check no_data queue
# Expected: FlowFiles appear in no_data (not success)
```

---

## Test F: OOM Protection (GAT-1)

**Quick Test:**
```bash
# Create 51MB file
dd if=/dev/zero of=/tmp/51MB.bin bs=1M count=51

# Send it
curl -X POST http://localhost:5050/api/test \
  --data-binary @/tmp/51MB.bin

# Expected: HTTP 500 error
# Expected log: "Request body size exceeds maximum allowed size"
# Expected: No OutOfMemoryError in NiFi logs

# Cleanup
rm /tmp/51MB.bin
```

---

## Test G: SwaggerServlet Error Handling (GAT-2)

**Quick Test:**
```bash
# 1. Access Swagger UI
curl http://localhost:5050/swagger/

# Expected: Swagger UI loads successfully
# OR if error: HTTP 500 with error message

# 2. Check NiFi logs for any Swagger errors
# Expected: Errors logged via servlet log() method
```

---

## Test H: SSL/TLS (GAT-3)

**Setup:**
```bash
# Generate test certificates
keytool -genkeypair -alias nifi-test -keyalg RSA -keysize 2048 \
  -validity 365 -keystore keystore.jks -storepass changeit \
  -dname "CN=localhost,OU=Test,O=Test,L=Test,ST=Test,C=US"
```

**Quick Test:**
```bash
# 1. Configure StandardSSLContextService in NiFi
# 2. Link to NodeJSAppAPIGateway SSL Context Service property
# 3. Test HTTP fails:
curl http://localhost:5050/api/time
# Expected: Connection refused

# 4. Test HTTPS works:
curl -k https://localhost:5050/api/time
# Expected: JSON response

# 5. Check logs
# Expected: "Gateway configured with HTTPS using SSL Context Service"
```

---

## One-Liner Test Commands

```bash
# Build
cd liquid-library/src/java_extensions/nodejs-app-gateway && mvn clean install -DskipTests

# Deploy (adjust paths)
cp */target/*.nar $NIFI_HOME/lib/

# Restart NiFi
$NIFI_HOME/bin/nifi.sh restart

# Quick endpoint test
curl http://localhost:5050/api/time

# Large request test
dd if=/dev/zero bs=1M count=51 2>/dev/null | curl -X POST http://localhost:5050/api/test --data-binary @-

# Check NiFi logs
tail -f $NIFI_HOME/logs/nifi-app.log | grep -i "gateway"
```

---

## Test Priorities

1. **MUST TEST** (Critical):
   - ✅ A. nocodenation tags (DONE)
   - H. SSL/TLS (security)
   - F. OOM protection (security)

2. **SHOULD TEST** (Important):
   - B. RespondWithTimestamp (new feature)
   - E. no_data relationship (breaking change)
   - D. Python error attributes (debugging)

3. **NICE TO TEST** (Enhancement):
   - C. MIME type attribute
   - G. SwaggerServlet error handling

---

## Success Criteria

- [ ] Build succeeds
- [ ] NiFi starts without errors
- [ ] All components searchable by "nocodenation"
- [ ] RespondWithTimestamp returns JSON
- [ ] Python processor has 3 relationships
- [ ] Large requests rejected (>50MB)
- [ ] HTTPS works when SSL configured
- [ ] HTTP fails when SSL configured
- [ ] Error FlowFiles have error.* attributes

---

**Quick Start:**
1. Run: `mvn clean install -DskipTests`
2. Deploy NARs to NiFi
3. Restart NiFi
4. Run test commands above
5. Check NiFi UI and logs
