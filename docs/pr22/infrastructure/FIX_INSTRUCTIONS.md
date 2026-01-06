### Fix Instructions for `nodejs-app-manager` module

This document provides instructions for fixing issues identified in the `src/java_extensions/nodejs-app-manager/` module.

---

#### 1. Add HTTPS/SSL Support for Health Checks

**Context:** The `ProcessMonitor` currently only supports HTTP. It needs to support HTTPS via `SSLContextService`.

**Instructions:**
1.  **Modify `StandardNodeJSApplicationManagerService.java`**:
    *   Add a new `PropertyDescriptor` named `SSL_CONTEXT_SERVICE`.
    *   Set it as a `ControllerService` property for `SSLContextService.class`.
    *   Add this property to the `PROPERTY_DESCRIPTORS` list.
    *   In the `onEnabled` method, retrieve the `SSLContextService` if configured.
    *   Pass the `SSLContextService` (or its `SSLContext`) to the `ProcessMonitor` constructor.
2.  **Modify `ProcessMonitor.java`**:
    *   Update the constructor to accept an optional `SSLContextService`.
    *   In `performHttpHealthCheck()`, determine the protocol ("http" or "https") based on whether `SSLContextService` is provided.
    *   If `SSLContextService` is provided:
        *   Use `https://` protocol in the URL.
        *   Cast the `url.openConnection()` result to `HttpsURLConnection`.
        *   Set the `SSLSocketFactory` from the `SSLContextService` on the connection.

---

#### 2. Add Missing `nocodenation` Tag

**Context:** The service is missing a required tag for discoverability.

**Instructions:**
1.  **Modify `StandardNodeJSApplicationManagerService.java`**:
    *   Update the `@Tags` annotation to include `"nocodenation"`.

---

#### 3. Make Stability Threshold Configurable

**Context:** The 5-minute stability period is currently hardcoded.

**Instructions:**
1.  **Modify `StandardNodeJSApplicationManagerService.java`**:
    *   Add a new `PropertyDescriptor` named `STABILITY_PERIOD`.
    *   Set a default value of `5 min` and use `StandardValidators.TIME_PERIOD_VALIDATOR`.
    *   Add this property to the `PROPERTY_DESCRIPTORS` list.
    *   In `onEnabled`, retrieve this value and pass it to the `ProcessMonitor`.
2.  **Modify `ProcessMonitor.java`**:
    *   Remove the hardcoded `STABILITY_THRESHOLD_MS` constant.
    *   Update the constructor to accept the stability threshold (in milliseconds).
    *   Use this value in the logic that resets the restart counter.

---

#### 4. Prevent Resource Leaks in `ProcessLifecycleManager`

**Context:** Old log capture threads might not be properly cleaned up if the process is restarted multiple times.

**Instructions:**
1.  **Modify `ProcessLifecycleManager.java`**:
    *   In the `startApplication()` method, before starting a new process, check if `nodeProcess` is already set or if capture threads are alive.
    *   Explicitly call `stopLogCaptureThreads()` (or equivalent cleanup) at the beginning of `startApplication()` to ensure a clean state.
    *   Ensure that `stopCaptureThread` properly joins and nullifies the thread references.

---

#### 5. Optimize Log Retrieval in `LogCapture`

**Context:** `LinkedList.get(index)` is O(n), making retrieval of recent logs inefficient for large buffers.

**Instructions:**
1.  **Modify `LogCapture.java`**:
    *   In `getRecentLogs(int maxLines)`, instead of using `logBuffer.get(index)` in a loop, use an `Iterator` or `descendingIterator()`.
    *   Alternatively, change the internal storage to an `ArrayList` and manage the circular index manually, or use a data structure optimized for tail retrieval.
    *   Example using `descendingIterator()`:
        ```java
        Iterator<String> it = logBuffer.descendingIterator();
        for (int i = 0; i < linesToReturn && it.hasNext(); i++) {
            recentLogs.add(it.next());
        }
        ```
