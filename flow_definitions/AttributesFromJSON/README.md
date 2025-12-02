# AttributesFromJSON

This flow demonstrates how to extract attributes from a JSON object and add them as FlowFile attributes.

## Processors

-   **GenerateFlowFile** ![Built-in](https://img.shields.io/badge/built--in-grey): Generates a FlowFile containing sample JSON data.
-   **AttributesFromJSON** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): A custom Python processor that parses the input JSON and promotes its keys to FlowFile attributes.

## Configuration

### GenerateFlowFile
Generates a JSON object with various fields such as `BasePriceText`, `CategoryId`, `Content`, `FinalStorePrice`, `InternalId`, `Material`, etc.

### AttributesFromJSON
-   **Input Strategy**: `Direct Text`
-   **Text Direct Input**: Contains the JSON string to be parsed. (Note: In a real-world scenario, this might be configured to read from the FlowFile content).

## Usage

This flow is useful for flattening JSON data so that individual fields can be used for routing (`RouteOnAttribute`) or other attribute-based processing logic within NiFi.
