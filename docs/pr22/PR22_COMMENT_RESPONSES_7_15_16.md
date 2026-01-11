# PR #22 - Comment Responses for #7, #15, #16

**Date:** 2026-01-11

Use these responses when closing the review comments on PR #22.

---

## Comment #7: Hardcoded Request Size Limit (GatewayServlet.java:205)

**Response:**

Fixed. The `readRequestBody()` method now uses the configurable `gateway.getMaxRequestSize()` instead of the hardcoded 50MB limit.

**Before:**
```java
final long maxBodySize = 50 * 1024 * 1024; // 50MB - hardcoded
```

**After:**
```java
final long maxBodySize = gateway.getMaxRequestSize(); // Uses configured property
```

This ensures that:
- The configured `Max Request Size` property is enforced during body reading
- Memory protection is applied at the configured limit, not a hardcoded 50MB
- No OOM vulnerability from mismatched limits

**Files modified:**
- `GatewayServlet.java`

---

## Comment #15: Log All Errors (GatewayServlet.java:221)

**Response:**

Fixed. The error handling in `readRequestBody()` now logs all IO errors with client IP, not just size overflow errors.

**Before:**
```java
} catch (IOException e) {
    if (e.getMessage() != null && e.getMessage().contains("Request body size exceeds maximum")) {
        log("Request body size exceeds maximum allowed size...");
        throw new IOException("Request body too large...", e);
    }
    throw e;
}
```

**After:**
```java
} catch (IOException e) {
    // Log all IO errors, not just size overflow
    log("Error reading request body from " + req.getRemoteAddr() + ": " + e.getMessage());
    throw e;
}
```

Also removed the unnecessary `new IOException()` wrapper - the original exception is now rethrown directly.

**Files modified:**
- `GatewayServlet.java`

---

## Comment #16: Extract CountingInputStream to Separate File (GatewayServlet.java:231)

**Response:**

Fixed. The `CountingInputStream` inner class has been extracted to a separate file for reusability.

**New file:** `CountingInputStream.java`

Features:
- Standalone class in `org.nocodenation.nifi.nodejsapp.gateway` package
- Full Javadoc documentation
- Added `getBytesRead()` and `getMaxBytes()` accessor methods for potential future use
- Can now be reused by other servlets (e.g., SwaggerServlet for Comment #8)

**Files created:**
- `CountingInputStream.java`

**Files modified:**
- `GatewayServlet.java` - Removed inner class, now imports the standalone class

---

## Summary

All three issues in `GatewayServlet.java` have been addressed:
- Configurable size limit is now enforced ✅
- All IO errors are logged with client context ✅
- CountingInputStream is now reusable ✅

The gateway service loads successfully and the `Max Request Size` property is visible and configurable in the NiFi UI.
