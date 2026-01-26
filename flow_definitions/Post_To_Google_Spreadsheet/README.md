# Post To Google Spreadsheet

This flow demonstrates posting data to a Google Spreadsheet using the PostToGoogleSpreadsheet processor with OAuth2 authentication.

## Processors

-   **GenerateFlowFile**: Generates sample employee data in JSON format for demonstration purposes.
-   **PostToGoogleSpreadsheet** ![Liquid Library](https://img.shields.io/badge/liquid--library-blue): Writes data to a Google Spreadsheet with support for UPSERT operations.

## Configuration

### PostToGoogleSpreadsheet
-   **Authentication Service**: Reference to an AuthenticationBrokerService controller service for OAuth2 authentication with Google.
-   **Spreadsheet ID**: The ID of the Google Spreadsheet (found in the spreadsheet URL).
-   **Sheet Name**: The name of the sheet within the spreadsheet (e.g., `Sheet1`).
-   **Write Mode**: Operation mode - `APPEND`, `OVERWRITE`, or `UPSERT`.
-   **Start Cell**: The cell where data writing begins (e.g., `A1`).
-   **Identifier Fields**: Comma-separated list of column names used to identify existing rows for UPSERT mode.
-   **Include Header Row**: Boolean flag to include headers in the output.
-   **Input Format**: Format of the input data (e.g., `JSON`).
-   **Value Input Option**: How Google Sheets should interpret values - `RAW` or `USER_ENTERED`.
-   **Format as Table**: Boolean flag to format the data range as a table in Google Sheets.
-   **Header Mismatch Strategy**: How to handle mismatches between input data and sheet headers - `ADD_COLUMNS`, `ERROR`, or `IGNORE`.

## Usage

Use this flow to automate data synchronization to Google Spreadsheets. It requires:
1. An AuthenticationBrokerService controller service configured for Google OAuth2
2. A valid Google Spreadsheet ID
3. Appropriate Google Sheets API permissions (https://www.googleapis.com/auth/spreadsheets)

The UPSERT mode allows for updating existing rows based on identifier fields, making it ideal for maintaining synchronized datasets.
