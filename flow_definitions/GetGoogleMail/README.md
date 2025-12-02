# GetGoogleMail

This flow retrieves emails from a Google Mail account using a specified search query.

## Processors

-   **GetGoogleMail** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Connects to the Gmail API to fetch messages.

## Configuration

### GetGoogleMail
-   **Search Query**: The query used to filter emails (e.g., `is:unread`).
-   **Token File Path**: Path to the JSON file containing the OAuth 2.0 token (e.g., `/files/token.json`).
-   **Output Format**: The format of the output content (e.g., `JSON`).
-   **Mark as Read**: Boolean flag to indicate if fetched emails should be marked as read.

## Usage

Use this flow to automate the ingestion of emails from Gmail. It requires a valid OAuth token, which can be obtained using the `Google_OAuth` flow.
