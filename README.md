# liquid-library
The complete Library of Processors and Features of NoCodeNation

## AttributeToParameter
A Python-based NiFi processor that updates a specific parameter in a Parameter Context with a value from a FlowFile attribute.

### Key Features
*   **Dynamic Updates:** Update parameters at runtime driven by data flow.
*   **Asynchronous Updates:** Uses the NiFi "Update Request" flow (`/update-requests`) to safely update Parameter Contexts even when they are referenced by running processors. NiFi automatically handles stopping, updating, and restarting affected components.
*   **Optimistic Locking:** Implements retry logic (3 attempts with backoff) to handle `409 Conflict` errors if multiple threads try to update the context simultaneously.
*   **Security:** Supports Authentication Tokens, Client Certificates (mTLS), and Username/Password exchange.

### Usage Caveats: Parallel vs. Downstream Visibility
When using this processor, it is important to understand when the updated parameter value becomes visible:

1.  **Downstream Processors (Safe):**
    The processor waits (polls) for the update to complete before transferring the FlowFile to the `success` relationship. Therefore, **any processor downstream of the `success` relationship is guaranteed to see the new parameter value**.

2.  **Parallel Processors:**
    For processors running in parallel (not downstream of this processor):
    *   **Race Condition:** If they execute before the update completes, they see the old value.
    *   **Interruption:** NiFi *must* stop all components referencing the Parameter Context to apply the update. Parallel processors using the context will be temporarily stopped and restarted by NiFi during the update process.

### Configuration
| Property | Description |
| :--- | :--- |
| **NiFi API URL** | Base URL of the NiFi API (e.g., `http://localhost:8080/nifi-api`). **Note:** If running inside a container, use the container hostname/ID (e.g., `https://<container-id>:8443/nifi-api`) instead of `localhost`. |
| **Parameter Context Name** | Name of the context to update. |
| **Parameter Name** | Name of the parameter to set. |
| **Parameter Value** | Value to set (supports Expression Language). |
| **Authentication Strategy** | Priority: Token > SSL Context Service > Check Client Cert Prop > Username/Password. |
