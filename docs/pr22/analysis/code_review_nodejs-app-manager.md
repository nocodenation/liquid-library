### Security & Infrastructure
#### Issue: Lack of HTTPS/SSL Support for Health Checks
*   **Description:** The `ProcessMonitor` class has a hardcoded `HTTP_PROTOCOL = "http://"` and uses `HttpURLConnection` directly for health checks. It does not provide a property to configure an `SSLContextService`.
*   **Impact:** If the managed Node.js application is configured to use HTTPS, the health check will fail or be insecure. This violates the project requirement that all NiFi Services handling HTTP traffic MUST support HTTPS.
*   **Recommendation:**
    1.  Add an optional `SSLContextService` property to `StandardNodeJSApplicationManagerService`.
    2.  Update `ProcessMonitor` to accept an `SSLContextService`.
    3.  If `SSLContextService` is provided, use `HttpsURLConnection` and configure it with the SSL context from the service.
    4.  Allow the protocol ("http" vs "https") to be derived from the presence of the `SSLContextService` or a separate property.
### NiFi Best Practices
#### Issue: Missing `nocodenation` Tag
*   **Description:** The `StandardNodeJSApplicationManagerService` class is missing the `nocodenation` tag in its `@Tags` annotation.
*   **Impact:** Reduced discoverability in the NiFi UI when users search for components related to the `nocodenation` ecosystem.
*   **Recommendation:** Add `"nocodenation"` to the `@Tags` annotation in `StandardNodeJSApplicationManagerService.java`.
### Code Quality & Architecture
#### Issue: Hardcoded Stability Threshold
*   **Description:** The stability threshold for resetting the restart counter is hardcoded to 5 minutes (`STABILITY_THRESHOLD_MS = 5 * 60 * 1000`) in `ProcessMonitor.java`.
*   **Impact:** Users cannot configure what defines a "stable" run for their specific application. For some apps, 5 minutes might be too long; for others, too short.
*   **Recommendation:** Expose this as a configurable property (e.g., `Stability Period`) with a default value of 5 minutes.
#### Issue: Potential Resource Leak in `ProcessLifecycleManager`
*   **Description:** In `ProcessLifecycleManager.startLogCapture()`, the `BufferedReader` is created within a thread, but if the process is restarted multiple times, ensure that old threads are always properly cleaned up. While there is a `stopLogCaptureThreads()` method, it's called in `stopApplication()`.
*   **Impact:** If not carefully managed during all failure modes, multiple threads could be left reading from closed streams or competing for resources.
*   **Recommendation:** Ensure `stopLogCaptureThreads()` is called at the beginning of `startApplication()` if `nodeProcess` is not null, or ensure that any existing threads are joined before starting new ones.
### Performance
#### Issue: Efficient Log Retrieval
*   **Description:** `LogCapture.getRecentLogs(int maxLines)` iterates through the `LinkedList` using `logBuffer.get(index)`.
*   **Impact:** `LinkedList.get(index)` is an O(n) operation. For large buffer sizes, retrieving recent logs could become inefficient.
*   **Recommendation:** Use a `Deque` or an `ArrayList` with manual index management, or use an `Iterator` from the end of the `LinkedList` to achieve O(k) performance where k is `maxLines`. Alternatively, use `logBuffer.descendingIterator()`.