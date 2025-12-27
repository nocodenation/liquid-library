
# SetDynamicAttribute Process Group

This Process Group demonstrates the usage of the custom `SetDynamicAttribute` processor.

## Description
The `SetDynamicAttribute` processor allows for setting FlowFile attributes dynamically based on custom logic. This flow demonstrates its configuration and effect on FlowFiles.

## Configuration
The processor is configured with dynamic properties where:
- **Property Name**: The name of the attribute to set.
- **Property Value**: The value to assign (supports Expression Language).

## Usage
1. Import this flow definition into your NiFi instance.
2. Ensure the `nifi-set-attribute-processor` NAR is installed.
3. Start the flow to observe attribute manipulation.
