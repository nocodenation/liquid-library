# AttributeToParameter Example Flow

This flow demonstrates how to use the `AttributeToParameter` processor to dynamically update a NiFi Parameter Context and verify the result immediately.

## Flow Logic

1.  **GenerateFlowFile**: Creates a FlowFile to trigger the process.
2.  **AttributeToParameter**: 
    *   Takes the FlowFile and uses its attributes (or configured values) to update a parameter (e.g., `GreatParameter`) in the target Parameter Context (`MyParameterContext`).
    *   It initiates an **asynchronous update request** to NiFi. 
    *   It **waits** until NiFi confirms the update is fully applied (stopping and restarting referenced components as needed).
    *   Upon completion, it routes the FlowFile to `success`.
3.  **UpdateAttribute from Parameter** (Downstream):
    *   This processor is connected to the `success` relationship of `AttributeToParameter`.
    *   It references the parameter that was just updated (e.g., `param_#{GreatParameter}`).
    *   **Crucial:** Because it is downstream, it is guaranteed to see the *new* value set by the previous step.

## Usage
1.  Import this flow definition into NiFi.
2.  Ensure a Parameter Context named `MyParameterContext` exists.
3.  Configure `AttributeToParameter` with your NiFi API credentials and URL.
4.  Start the flow. The `UpdateAttribute` processor's output FlowFile should have a filename or attribute reflecting the updated parameter value.
