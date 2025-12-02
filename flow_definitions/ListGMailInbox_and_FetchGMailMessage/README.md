# ListGMailInbox and FetchGMailMessage

This flow demonstrates a complete pipeline for processing Gmail messages: listing them based on a query and then fetching the full content of each message.

## Processors

-   **GenerateFlowFile** ![Built-in](https://img.shields.io/badge/built--in-grey): Triggers the flow.
-   **ListGMailInbox** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Lists messages matching a search query (e.g., `is:unread`).
-   **SplitJson** ![Built-in](https://img.shields.io/badge/built--in-grey): Splits the list of messages into individual FlowFiles.
-   **AttributesFromJSON** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Extracts the message ID (`${id}`) from the JSON object.
-   **FetchGMailMessage** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Fetches the full content of the message using its ID.

## Configuration

### ListGMailInbox
-   **Search Query**: Filter for listing emails (e.g., `is:unread`).
-   **Token File Path**: Path to the OAuth token file.

### FetchGMailMessage
-   **Message ID**: The ID of the message to fetch (derived from the list output).
-   **Mark as Read**: Option to mark the message as read after fetching.

## Usage

This flow provides a template for building email processing applications, such as support ticket creation from emails or automated email archiving.
