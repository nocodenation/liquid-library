# PR #22 - Unresolved Review Comments

**Generated:** 2026-01-11
**Total Unresolved Comments:** 17

*Note: Only comments where `isResolved == false` in GitHub review threads are listed.*

---

## 1. ReceiveFromNodeJSApp.java - Line 191
**Comment:** "This function can be refactored to simplify things: the queue polling logic can be extracted to a separate function that just returns request"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/ReceiveFromNodeJSApp.java`

---

## 2. ReceiveFromNodeJSApp.java - Line 223
**Comment:** "Returning from the function will not give any feedback to user. Send a flowFile to `REL_FAILURE` instead"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/ReceiveFromNodeJSApp.java`

---

## 3. ReceiveFromNodeJSApp.java - Line 251
**Comment:** "Returning from the function will not give any feedback to user. Send a flowFile to `REL_FAILURE` instead"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/ReceiveFromNodeJSApp.java`

---

## 4. ProcessMonitor.java - Line 53
**Comment:** "**Issue:** Hardcoded HTTP protocol and lack of SSL/TLS support for health checks. **Impact:** Mandatory production-ready NiFi extensions must support HTTPS. Hardcoding `http://` prevents monitoring applications that require SSL. **Recommendation:** Add an `SSLContextService` property to the Controller Service and use it to configure the `HttpURLConnection` in `ProcessMonitor`."
**File:** `src/java_extensions/nodejs-app-manager/nodejs-app-manager-service/src/main/java/org/nocodenation/nifi/nodejsapp/ProcessMonitor.java`

---

## 5. ProcessMonitor.java - Line 234
**Comment:** "**Issue:** Resource management: `connection.getInputStream().close()` should be called to release resources. **Impact:** Failure to close streams can lead to resource leaks and potentially OOM in high-load scenarios. **Recommendation:** Ensure that the input stream (and error stream) are closed after reading or checking the response code. Use try-with-resources if reading the body."
**File:** `src/java_extensions/nodejs-app-manager/nodejs-app-manager-service/src/main/java/org/nocodenation/nifi/nodejsapp/ProcessMonitor.java`

---

## 6. ProcessMonitor.java - Line 224
**Comment:** "**Issue:** Lack of `SSLContextService` support for health checks. **Impact:** Mandatory for production-ready code. Applications running over HTTPS cannot be monitored correctly without SSL configuration. **Recommendation:** Integrate with NiFi's `SSLContextService` to allow secure health checks."
**File:** `src/java_extensions/nodejs-app-manager/nodejs-app-manager-service/src/main/java/org/nocodenation/nifi/nodejsapp/ProcessMonitor.java`

---

## 7. GatewayServlet.java - Line 205
**Comment:** "**Issue: Hardcoded Request Size Limit Conflicts with Configurable Property**

**Problem:** The `readRequestBody()` method uses a hardcoded 50MB limit (`maxBodySize = 50 * 1024 * 1024`) while the gateway has a configurable `MAX_REQUEST_SIZE` property.

**Impact:** Security risk - potential OOM vulnerability. The configurable `MAX_REQUEST_SIZE` property becomes ineffective for preventing memory exhaustion.

**Recommendation:** Use `gateway.getMaxRequestSize()` instead of the hardcoded value."
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/GatewayServlet.java`

---

## 8. SwaggerServlet.java - Line 121
**Comment:** "**Issue: `readAllBytes()` Called Before Size Check**

**Problem:** In `loadResourceAsString()`, `in.readAllBytes()` is called before checking if the file exceeds 1MB.

**Impact:** Security risk - potential OOM for large bundled resources.

**Recommendation:** Use a counted/limited stream approach similar to `GatewayServlet`."
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/SwaggerServlet.java`

---

## 9. StandardNodeJSApplicationManagerService.java - Line 40
**Comment:** "**Issue: Missing \"nocodenation\" Tag**

**Problem:** The `@Tags` annotation is missing \"nocodenation\" which is required for discoverability in NiFi UI per project guidelines.

**Recommendation:** Add \"nocodenation\" to the tags."
**File:** `src/java_extensions/nodejs-app-manager/nodejs-app-manager-service/src/main/java/org/nocodenation/nifi/nodejsapp/StandardNodeJSApplicationManagerService.java`

---

## 10. ReceiveFromNodeJSApp.py - Line 10
**Comment:** "This processor should be removed"
**File:** `src/native_python_processors/ReceiveFromNodeJSApp/ReceiveFromNodeJSApp.py`

---

## 11. InternalApiServlet.java - Line 45
**Comment:** "This code is not necessary, since Python Processors can access ControllerServices directly the same way as Java Processors. This class should be removed entirely"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/InternalApiServlet.java`

---

## 12. StandardNodeJSAppAPIGateway.java - Line 495
**Comment:** "This servlet is not necessary, since Python Processors can access ControllerServices directly the same way as Java Processors. The InternalApiServlet should be removed, and therefore its usage here should be removed as well"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/StandardNodeJSAppAPIGateway.java`

---

## 13. RespondWithTimestamp.java - Line 197
**Comment:** "Instead of creating a handle function and passing it as a parameter do the following:

Create a Class that implements `EndpointHandler` interface from `nodejs-app-gateway-service-api`, and passes instance of that class to the function.

The implementation of `handleRequest` function should be similar to the `handleTimestampRequest` function of this class."
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/RespondWithTimestamp.java`

---

## 14. RespondWithTimestamp.java - Line 243
**Comment:** "### Better JSON Serialization Options for Java

The manual `StringBuilder` approach in lines 231-243 is problematic - it's error-prone (easy to miss quotes, commas, escaping), hard to maintain, and doesn't handle special characters properly.

**Recommended Solution:** Use Jackson ObjectMapper (already used in `nodejs-app-gateway-service` module)."
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/RespondWithTimestamp.java`

---

## 15. GatewayServlet.java - Line 221
**Comment:** "If logging errors - log everything, not only Request body size overflow.

No need to throw a new IOException here"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/GatewayServlet.java`

---

## 16. GatewayServlet.java - Line 231
**Comment:** "This class should be extracted to separate file and reused by other servlets as well"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/GatewayServlet.java`

---

## 17. OpenAPIServlet.java - Line 21
**Comment:** "Put the gateway as a first parameter to make the constructor uniform with SwaggerServlet"
**File:** `src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/OpenAPIServlet.java`

---

## Summary by Category

### 🔴 Security Issues (4)
- **#4:** Hardcoded HTTP protocol for health checks (ProcessMonitor.java:53)
- **#5:** Resource management - streams not closed (ProcessMonitor.java:234)
- **#6:** Lack of SSLContextService support (ProcessMonitor.java:224)
- **#7:** Hardcoded size limit conflicts with configurable property (GatewayServlet.java:205)
- **#8:** readAllBytes() called before size check (SwaggerServlet.java:121)

### 🟡 Architecture/Removal Issues (4)
- **#10:** ReceiveFromNodeJSApp.py should be removed
- **#11:** InternalApiServlet should be removed entirely
- **#12:** InternalApiServlet usage should be removed from StandardNodeJSAppAPIGateway
- **#13:** RespondWithTimestamp should properly implement EndpointHandler interface

### 🟢 Code Quality/Refactoring (6)
- **#1:** Refactor queue polling logic (ReceiveFromNodeJSApp.java:191)
- **#2, #3:** Send flowFile to REL_FAILURE instead of returning (ReceiveFromNodeJSApp.java:223, 251)
- **#14:** Use Jackson instead of manual JSON serialization (RespondWithTimestamp.java:243)
- **#15:** Log all errors, not just size overflow (GatewayServlet.java:221)
- **#16:** Extract CountingInputStream to separate file (GatewayServlet.java:231)
- **#17:** Uniform constructor parameter order (OpenAPIServlet.java:21)

### 🏷️ Tags/Metadata (1)
- **#9:** Add "nocodenation" tag (StandardNodeJSApplicationManagerService.java:40)

---

**Total: 17 unresolved comments**
