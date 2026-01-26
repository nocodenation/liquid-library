# State Management Flow Definitions

This folder contains NiFi flow definitions demonstrating the usage of the `ManageState` and `StatefulCounter` processors.

## StateManager.json

This flow demonstrates the full capabilities of the **ManageState** processor. It includes examples for:
*   **Setting State:** Saving values to different backends (Cluster State Manager, JSON File, Map Cache).
*   **Getting State:** Retrieving values and verifying them against expected output.
*   **Deleting State:** Removing keys from the store.
*   **Dynamic Operations:** Using Expression Language to switch between GET/SET operations dynamically.

## StatefulCounter.json

This flow demonstrates the **StatefulCounter** processor:
*   **Atomic Increment:** Increasing a counter value in a cluster-safe manner.
*   **Initial Value:** Handling the first count operation.
*   **Failure Handling:** Simulating concurrency (conceptually) by showing the retry mechanism in action via logs (if observed under load).
