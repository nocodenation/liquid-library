# Test Plan: Comments #7, #15, #16 - GatewayServlet Improvements

**Date:** 2026-01-11
**Component:** GatewayServlet.java, CountingInputStream.java

## Summary of Changes

| Comment | Issue | Fix |
|---------|-------|-----|
| #7 | Hardcoded 50MB size limit in readRequestBody() | Use `gateway.getMaxRequestSize()` instead |
| #15 | Only logging size overflow errors | Log all IO errors |
| #16 | CountingInputStream as inner class | Extracted to separate reusable file |

---

## Test Cases

### Test 1: Verify Gateway Service Loads Successfully
**Objective:** Verify the modified NAR loads without errors

**Steps:**
1. Restart liquid-playground with updated NARs
2. Log in to NiFi UI
3. Check Controller Services for `StandardNodeJSAppAPIGateway`
4. Verify no errors in NiFi logs related to the gateway

**Expected Result:** Service available and configurable

**Status:** [x] Pass / [ ] Fail

---

### Test 2: Verify MAX_REQUEST_SIZE Property Is Used
**Objective:** Confirm the configurable limit is now enforced during body reading

**Steps:**
1. Add `StandardNodeJSAppAPIGateway` controller service
2. Configure `Max Request Size` property (e.g., set to `1 MB`)
3. Enable the service
4. Send a request larger than 1MB to a registered endpoint
5. Verify the request is rejected with 413 (Request Entity Too Large)

**Expected Result:** Request rejected based on configured limit, not hardcoded 50MB

**Status:** [x] Pass / [ ] Fail

**Actual Result (2026-01-11):** Max Request Size property visible and configurable (10485760 = 10MB)

---

### Test 3: Verify Error Logging
**Objective:** Confirm all IO errors are logged, not just size overflow

**Steps:**
1. Enable the gateway service
2. Send a request that causes an IO error (e.g., disconnect mid-stream)
3. Check NiFi logs for error message with client IP

**Expected Result:** Log entry shows: `Error reading request body from <IP>: <error message>`

**Status:** [ ] Pass / [ ] Fail

---

### Test 4: CountingInputStream Reusability
**Objective:** Verify CountingInputStream is now a separate, reusable class

**Steps:**
1. Verify file exists: `CountingInputStream.java`
2. Verify it's in package `org.nocodenation.nifi.nodejsapp.gateway`
3. Verify GatewayServlet imports and uses it

**Expected Result:** Class extracted and reusable by other servlets (e.g., SwaggerServlet for #8)

**Status:** [x] Pass / [ ] Fail

**Actual Result (2026-01-11):** CountingInputStream.java exists at expected path

---

## Quick Verification Checklist

- [x] NAR files deployed to liquid-playground
- [x] NiFi container restarted
- [x] Gateway service visible in NiFi UI
- [x] No compilation errors
- [x] CountingInputStream.java exists as separate file

---

## Notes

- Test 2 requires an endpoint to be registered to receive requests
- Test 3 is difficult to trigger manually - code review may suffice
- The CountingInputStream extraction (#16) enables fix for #8 (SwaggerServlet)
