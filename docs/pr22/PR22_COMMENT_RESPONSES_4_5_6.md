# PR #22 - Comment Responses for #4, #5, #6

**Date:** 2026-01-11

Use these responses when closing the review comments on PR #22.

---

## Comment #4: Hardcoded HTTP protocol for health checks (ProcessMonitor.java:53)

**Response:**

Implemented SSL/TLS support for health checks. Changes:

1. Added `SSL_CONTEXT_SERVICE` property to `StandardNodeJSApplicationManagerService` that allows users to optionally configure an `SSLContextService` for HTTPS health checks.

2. Modified `ProcessMonitor` to accept an optional `SSLContext` parameter:
   - When SSL Context Service is configured → health checks use HTTPS
   - When not configured → health checks use HTTP (backward compatible)

3. Updated `performHttpHealthCheck()` to:
   - Dynamically select protocol based on SSL configuration
   - Configure `HttpsURLConnection` with the provided `SSLSocketFactory` when using HTTPS

**Files modified:**
- `ProcessMonitor.java` - Added SSLContext support
- `StandardNodeJSApplicationManagerService.java` - Added SSL_CONTEXT_SERVICE property
- `nodejs-app-manager-service/pom.xml` - Added nifi-ssl-context-service-api dependency

**Testing:** Verified in liquid-playground that:
- HTTP mode works (backward compatible)
- HTTPS mode attempts SSL connection when configured

---

## Comment #5: Resource management - streams not closed (ProcessMonitor.java:234)

**Response:**

Fixed resource leak by properly closing input and error streams in the `finally` block of `performHttpHealthCheck()`.

Changes:
- Added `closeStreamQuietly()` helper method
- Modified finally block to close both `getErrorStream()` and `getInputStream()` before calling `disconnect()`

```java
} finally {
    if (connection != null) {
        closeStreamQuietly(connection.getErrorStream());
        try {
            closeStreamQuietly(connection.getInputStream());
        } catch (IOException ignored) {
            // getInputStream() may throw if response was error
        }
        connection.disconnect();
    }
}
```

**Files modified:**
- `ProcessMonitor.java`

---

## Comment #6: Lack of SSLContextService support (ProcessMonitor.java:224)

**Response:**

Addressed as part of Comment #4. The `ProcessMonitor` now supports HTTPS health checks when an `SSLContextService` is configured.

The NAR dependency chain properly inherits SSL support:
- `nodejs-app-manager-service-api-nar` → depends on `nifi-standard-services-api-nar`
- `nodejs-app-manager-service-nar` → depends on `nodejs-app-manager-service-api-nar`

**Files modified:**
- Same as Comment #4

---

## Bonus: Comment #9 - Missing "nocodenation" tag

**Response:**

Added "nocodenation" to the `@Tags` annotation in `StandardNodeJSApplicationManagerService.java` for discoverability in NiFi UI.

```java
@Tags({"nocodenation", "nodejs", "node", "javascript", "application", "process", "management"})
```

**Files modified:**
- `StandardNodeJSApplicationManagerService.java`

---

## Summary

All changes are backward compatible. Existing configurations without SSL Context Service configured will continue to work with HTTP health checks as before.
