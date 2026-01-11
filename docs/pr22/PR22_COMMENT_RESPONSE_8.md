# PR #22 - Comment Response for #8

**Date:** 2026-01-11

Use this response when closing review comment #8 on PR #22.

---

## Comment #8: readAllBytes() Before Size Check (SwaggerServlet.java:121)

**Response:**

Fixed. The `loadResourceAsString()` method now uses `CountingInputStream` to enforce the size limit **during** reading rather than **after** loading the entire file into memory.

**Before:**
```java
// Defensive check: reject files larger than 1MB
byte[] bytes = in.readAllBytes();  // <-- Loads entire file into memory FIRST
if (bytes.length > 1_048_576) {    // <-- THEN checks size (too late!)
    throw new IOException("Resource too large: " + name + " (" + bytes.length + " bytes)");
}
```

**After:**
```java
final long maxResourceSize = 1_048_576; // 1MB limit

// Use CountingInputStream to enforce size limit during reading (not after)
try (CountingInputStream in = new CountingInputStream(rawIn, maxResourceSize);
     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
    
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = in.read(buffer)) != -1) {
        out.write(buffer, 0, bytesRead);
    }
    
    return out.toString(StandardCharsets.UTF_8);
}
```

This ensures:
- Size limit is enforced incrementally during reading
- If a bundled resource exceeds 1MB, reading stops immediately when limit is reached
- No OOM vulnerability from loading arbitrarily large files before checking

**Reuse:** The fix leverages the `CountingInputStream` class extracted in Comment #16, demonstrating the value of that refactoring.

**Files modified:**
- `SwaggerServlet.java`

---

## Verification

- [x] Build successful
- [x] NAR deployed to liquid-playground
- [x] Swagger UI accessible at configured path
- [x] Code review confirms size limit enforced during reading
