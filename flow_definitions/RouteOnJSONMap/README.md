
# RouteOnJSONMap Process Group

This Process Group demonstrates the usage of the custom `RouteOnJSONMap` processor.

## Description
The `RouteOnJSONMap` processor allows for dynamic routing of FlowFiles based on a JSON map of rules. This process group setup includes:
- **GenerateFlowFile**: Simulates incoming data with attributes.
- **RouteOnJSONMap**: The core processor configured with routing rules.
- **LogAttribute**: Logs the output of the routing (matched vs unmatched).

## Configuration
The processor is configured with the following properties:
- **JSON Map**: A JSON object defining the routes and their conditions (Expression Language supported).
- **JSON Map File**: (Optional) Path to an external JSON file containing the rules.

## Usage
1. Import this flow definition into your NiFi instance.
2. Ensure the `nifi-route-on-json-map` NAR is installed.
3. Start the processors to observe the routing behavior.
