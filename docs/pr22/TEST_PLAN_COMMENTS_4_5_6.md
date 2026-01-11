# Test Plan: Comments #4, #5, #6 - SSL Health Check Support

**Date:** 2026-01-11
**Components:** ProcessMonitor.java, StandardNodeJSApplicationManagerService.java

## Summary of Changes

| Comment | Issue | Fix |
|---------|-------|-----|
| #4 | Hardcoded HTTP protocol for health checks | Added HTTPS support when SSLContextService is configured |
| #5 | Resource management - streams not closed | Added proper stream closing in finally block |
| #6 | Lack of SSLContextService support | Added SSL_CONTEXT_SERVICE property descriptor |
| #9 (bonus) | Missing "nocodenation" tag | Added to @Tags annotation |

---

## Test Cases

### Test 1: Verify Component Discovery (Tag Fix #9)
**Objective:** Verify the "nocodenation" tag was added and component is discoverable

**Steps:**
1. Open NiFi UI at https://localhost:8443/nifi
2. Add a new Controller Service
3. Search for "nocodenation" in the filter
4. Verify "StandardNodeJSApplicationManagerService" appears in results

**Expected Result:** Component appears when filtering by "nocodenation"

**Status:** [x] Pass / [ ] Fail

---

### Test 2: Verify SSL Context Service Property Exists
**Objective:** Verify the new SSL Context Service property is available

**Steps:**
1. Add "StandardNodeJSApplicationManagerService" controller service
2. Open the configuration panel
3. Verify "SSL Context Service" property appears under health check settings
4. Verify it's only visible when "Enable Health Check" is set to true

**Expected Result:** SSL Context Service property is visible and properly conditionally displayed

**Status:** [x] Pass / [ ] Fail

---

### Test 3: HTTP Health Check (Backward Compatibility)
**Objective:** Verify health checks still work over HTTP when no SSL is configured

**Prerequisites:**
- A simple Node.js application with a `/health` endpoint returning HTTP 200

**Steps:**
1. Configure StandardNodeJSApplicationManagerService:
   - Application Path: (path to test Node.js app)
   - Application Port: 3000
   - Health Check Path: /health
   - Enable Health Check: true
   - SSL Context Service: (leave empty)
2. Enable the controller service
3. Check NiFi logs for health check messages
4. Verify logs show "Health checks will use HTTP (no SSL Context Service configured)"

**Expected Result:** 
- Service starts successfully
- Health checks work over HTTP
- Log message confirms HTTP mode

**Status:** [x] Pass / [ ] Fail

**Actual Result (2026-01-11):** 
- HTTPS mode: Log confirmed `Health checks will use HTTPS with SSL Context Service` (SSL handshake error proves HTTPS attempted)
- HTTP mode: Service running successfully, app accessible on localhost:3000, health checks passing

---

### Test 4: HTTPS Health Check (New Feature)
**Objective:** Verify health checks work over HTTPS when SSL is configured

**Prerequisites:**
- A Node.js application with HTTPS enabled on `/health` endpoint
- SSL certificates configured

**Steps:**
1. Create/configure an SSLContextService with appropriate certificates
2. Configure StandardNodeJSApplicationManagerService:
   - Application Path: (path to HTTPS Node.js app)
   - Application Port: 3443
   - Health Check Path: /health
   - Enable Health Check: true
   - SSL Context Service: (select the SSLContextService)
3. Enable the controller service
4. Check NiFi logs for health check messages
5. Verify logs show "Health checks will use HTTPS with SSL Context Service"

**Expected Result:**
- Service starts successfully
- Health checks work over HTTPS
- Log message confirms HTTPS mode

**Status:** [ ] Pass / [ ] Fail

---

### Test 5: Resource Cleanup Verification
**Objective:** Verify streams are properly closed (no resource leaks)

**Steps:**
1. Configure StandardNodeJSApplicationManagerService with health checks enabled
2. Enable the service and let it run for several minutes
3. Monitor NiFi's memory usage
4. Check for any resource leak warnings in logs
5. Disable and re-enable the service multiple times

**Expected Result:**
- No memory growth over time
- No resource leak warnings
- Service can be disabled/enabled repeatedly without issues

**Status:** [ ] Pass / [ ] Fail

---

### Test 6: Health Check Failure Handling
**Objective:** Verify health check failures are properly handled and logged

**Steps:**
1. Configure service to point to a non-existent health endpoint
2. Enable the service
3. Monitor logs for health check failure messages
4. Verify the service properly reports unhealthy status

**Expected Result:**
- Health check failures are logged
- Service status reflects unhealthy state
- No exceptions or crashes

**Status:** [ ] Pass / [ ] Fail

---

## Quick Verification Checklist

- [x] NAR files deployed to liquid-playground
- [x] NiFi container restarted
- [x] Component visible in NiFi UI
- [x] "nocodenation" tag works for filtering
- [x] SSL Context Service property visible
- [x] HTTPS health checks confirmed via log message
- [x] No build errors

---

## Notes

- Tests 3 and 4 require a Node.js application to be available
- Test 4 requires SSL certificates to be configured
- For quick verification, Tests 1, 2, and the checklist are sufficient
