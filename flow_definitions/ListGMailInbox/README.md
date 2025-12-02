# ListGMailInbox

This flow lists messages from a Gmail inbox based on a search query.

## Processors

-   **GenerateFlowFile** ![Built-in](https://img.shields.io/badge/built--in-grey): Triggers the flow execution.
-   **ListGMailInbox** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Connects to the Gmail API to list messages matching the criteria.

## Configuration

### ListGMailInbox
-   **Max Results**: Maximum number of results to return (e.g., `50`).
-   **Search Query**: The query used to filter emails (e.g., `is:unread`).
-   **Token File Path**: Path to the JSON file containing the OAuth 2.0 token (e.g., `/files/token.json`).

## Usage

This flow is useful for getting a list of email IDs and summaries. It is often the first step in a pipeline that processes emails, followed by fetching the full content of specific messages.
