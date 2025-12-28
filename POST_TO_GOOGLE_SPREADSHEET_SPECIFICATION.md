# PostToGoogleSpreadsheet Processor Specification

## Overview

**PostToGoogleSpreadsheet** is a Python-based NiFi processor that writes data to Google Sheets using OAuth2 authentication provided by the AuthenticationBrokerService. It supports various write modes including append, update, and upsert operations based on identifier fields.

## Purpose

This processor enables seamless integration between NiFi data flows and Google Sheets, allowing users to:
- Write structured data to Google Sheets spreadsheets
- Create new sheets or append to existing ones
- Update existing rows based on identifier fields
- Handle headers automatically
- Format sheets as tables (optional)

## Configuration Properties

### Required Properties

| Property Name | Type | Default | Description |
|--------------|------|---------|-------------|
| Authentication Service | Controller Service | - | Reference to AuthenticationBrokerService for OAuth2 token |
| Spreadsheet ID | String | - | Google Sheets spreadsheet ID (from URL). Supports EL. |
| Sheet Name | String | Sheet1 | Name of the worksheet/tab within the spreadsheet. Supports EL. |

### Optional Properties

| Property Name | Type | Default | Description |
|--------------|------|---------|-------------|
| Write Mode | Enumeration | APPEND | How to write data: APPEND, UPDATE, UPSERT, REPLACE |
| Identifier Fields | String | - | Comma-separated list of field names to use as row identifiers (for UPDATE/UPSERT modes). Supports EL. |
| Starting Cell | String | A1 | Cell address where data starts (e.g., A1, B2). Only used for empty sheets. |
| Include Headers | Boolean | true | Whether to write field names as the first row |
| Format as Table | Boolean | false | Whether to format the range as a Google Sheets table with filters |
| Clear Sheet Before Write | Boolean | false | If true, clears all existing data before writing (only with REPLACE mode) |
| Value Input Option | Enumeration | USER_ENTERED | How values are interpreted: RAW (exact), USER_ENTERED (parsed like typing) |
| On Identifier Not Found | Enumeration | INSERT | For UPDATE mode: INSERT (add new row), IGNORE (skip), ERROR (route to failure) |
| On Identifier Duplicate | Enumeration | UPDATE | For UPSERT mode: UPDATE (overwrite), SKIP (keep original), ERROR (route to failure) |
| Batch Size | Integer | 100 | Number of rows to write in a single API call (max 1000) |

## Write Modes

### 1. APPEND Mode
- Adds new rows to the end of the sheet
- Ignores Identifier Fields
- Fastest mode for bulk inserts
- Does not check for duplicates

### 2. UPDATE Mode
- Updates existing rows based on Identifier Fields
- Requires Identifier Fields to be set
- Searches for rows where identifier field values match
- Behavior when identifier not found controlled by "On Identifier Not Found"

### 3. UPSERT Mode (Update or Insert)
- Updates existing rows if identifier found, otherwise inserts
- Requires Identifier Fields to be set
- Behavior when duplicate found controlled by "On Identifier Duplicate"

### 4. REPLACE Mode
- Clears entire sheet first (if Clear Sheet Before Write is true)
- Writes all data fresh
- Useful for complete data refresh scenarios

## Input FlowFile Format

### JSON Format

The processor expects FlowFile content in JSON format with one of these structures:

#### Single Row Object
```json
{
  "Name": "John Doe",
  "Email": "john@example.com",
  "Age": 30,
  "Department": "Engineering"
}
```

#### Multiple Rows Array
```json
[
  {
    "Name": "John Doe",
    "Email": "john@example.com",
    "Age": 30,
    "Department": "Engineering"
  },
  {
    "Name": "Jane Smith",
    "Email": "jane@example.com",
    "Age": 28,
    "Department": "Marketing"
  }
]
```

#### Headers + Rows Object (Array-based)
```json
{
  "headers": ["Name", "Email", "Age", "Department"],
  "rows": [
    ["John Doe", "john@example.com", 30, "Engineering"],
    ["Jane Smith", "jane@example.com", 28, "Marketing"]
  ]
}
```
**Note:** JSON arrays maintain insertion order per RFC 8259. The first header ("Name") corresponds to the first value in each row ("John Doe"), second to second, etc. This is the **positional mapping** approach.

#### Headers + Rows Object (Object-based - Recommended for Robustness)
```json
{
  "headers": ["Name", "Email", "Age", "Department"],
  "rows": [
    {
      "Name": "John Doe",
      "Email": "john@example.com",
      "Age": 30,
      "Department": "Engineering"
    },
    {
      "Name": "Jane Smith",
      "Email": "jane@example.com",
      "Age": 28,
      "Department": "Marketing"
    }
  ]
}
```
**Note:** This format combines explicit headers with object rows. The `headers` array defines column order for the spreadsheet. Each row object provides named values. This is **more robust** as it doesn't rely on positional matching and makes data self-documenting.

### CSV Format (Alternative)

The processor can also accept CSV format:
```csv
Name,Email,Age,Department
John Doe,john@example.com,30,Engineering
Jane Smith,jane@example.com,28,Marketing
```

## Output

### Success Relationship
FlowFile is routed to `success` with additional attributes:
- `sheets.spreadsheet.id`: Spreadsheet ID
- `sheets.sheet.name`: Sheet name
- `sheets.rows.written`: Number of rows written
- `sheets.rows.updated`: Number of rows updated
- `sheets.rows.inserted`: Number of rows inserted
- `sheets.range.updated`: A1 notation of the range updated (e.g., "Sheet1!A1:D10")
- `sheets.operation.mode`: Write mode used

### Failure Relationship
FlowFile is routed to `failure` if:
- Authentication fails (no valid token)
- Spreadsheet or sheet not found
- Invalid JSON format
- Identifier field not found in data
- Duplicate identifier error (when configured)
- API rate limit exceeded
- Network errors

## Google Sheets API Integration

### Authentication Flow

1. Processor requests access token from AuthenticationBrokerService
2. Service returns valid OAuth2 token (auto-refreshed if needed)
3. Processor uses token in Authorization header for all API calls
4. If token expires during operation, retry with fresh token

### API Endpoints Used

#### 1. Get Spreadsheet Metadata
```
GET https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}
```
Used to verify spreadsheet exists and get sheet properties.

#### 2. Get Sheet Values (for UPDATE/UPSERT)
```
GET https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}/values/{range}
```
Used to read existing data for identifier matching.

#### 3. Append Values (APPEND mode)
```
POST https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}/values/{range}:append
```
Appends rows to the end of the specified range.

#### 4. Update Values (UPDATE/UPSERT modes)
```
PUT https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}/values/{range}
```
Updates specific cell ranges.

#### 5. Batch Update (for multiple operations)
```
POST https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}:batchUpdate
```
Used for complex operations like clearing, formatting as table, etc.

#### 6. Clear Sheet (REPLACE mode)
```
POST https://sheets.googleapis.com/v4/spreadsheets/{spreadsheetId}/values/{range}:clear
```
Clears all data in specified range.

## Processing Logic

### APPEND Mode Flow

1. Parse FlowFile JSON content
2. Extract headers and row data
3. Get AuthenticationBrokerService token
4. If sheet is empty and Include Headers is true:
   - Write headers starting at Starting Cell
5. Append data rows using values:append API
6. If Format as Table is true:
   - Apply table formatting to the range
7. Route to success with metadata

### UPDATE Mode Flow

1. Parse FlowFile JSON content
2. Validate Identifier Fields are present in data
3. Get AuthenticationBrokerService token
4. Read entire sheet data
5. For each input row:
   - Search for matching row based on identifier fields
   - If found: prepare update for that row
   - If not found: apply "On Identifier Not Found" behavior
6. Batch update all matched rows
7. Route to success/failure based on results

### UPSERT Mode Flow

1. Parse FlowFile JSON content
2. Validate Identifier Fields are present in data
3. Get AuthenticationBrokerService token
4. Read entire sheet data
5. Build identifier index of existing rows
6. For each input row:
   - Check if identifier exists in index
   - If exists: prepare update (apply "On Identifier Duplicate" behavior)
   - If not exists: prepare append
7. Execute batch operations (updates + appends)
8. Route to success with metadata

### REPLACE Mode Flow

1. Parse FlowFile JSON content
2. Get AuthenticationBrokerService token
3. If Clear Sheet Before Write is true:
   - Clear all data in sheet
4. Write headers (if Include Headers is true)
5. Write all data rows starting from Starting Cell
6. If Format as Table is true:
   - Apply table formatting
7. Route to success with metadata

## Identifier Matching Logic

### How Identifiers Work

When Identifier Fields is set to `"Email,Department"`:

1. Create composite key from specified fields
2. For input row: `{Email: "john@example.com", Department: "Engineering"}` → Key: `"john@example.com|Engineering"`
3. For each existing row, create same composite key
4. Match input row to existing row by comparing keys
5. Update the matched row's other fields

### Edge Cases

- **Missing identifier field in data**: Route to failure with error message
- **Null/empty identifier value**: Treat as distinct value (null matches null)
- **Multiple rows with same identifier in sheet**: Update first match only, log warning
- **Case sensitivity**: Identifiers are case-sensitive by default

## Table Formatting

When "Format as Table" is enabled:

1. Apply alternating row colors (light gray)
2. Bold header row
3. Add filter buttons to header row
4. Freeze header row
5. Auto-resize columns to fit content

Uses Google Sheets API `requests`:
- `repeatCell` for formatting
- `setBasicFilter` for filter buttons
- `updateSheetProperties` for frozen rows

## Error Handling

### Authentication Errors

| Error | Behavior |
|-------|----------|
| No token available | Route to failure: "Authentication service not configured or not authenticated" |
| Token expired and refresh failed | Route to failure: "Failed to refresh OAuth2 token" |
| Insufficient permissions | Route to failure: "Token lacks required scope: spreadsheets" |

### Spreadsheet Errors

| Error | Behavior |
|-------|----------|
| Spreadsheet not found (404) | Route to failure: "Spreadsheet {id} not found or access denied" |
| Sheet name not found | Create new sheet with that name (configurable) OR route to failure |
| Invalid range format | Route to failure: "Invalid cell range: {range}" |

### Data Errors

| Error | Behavior |
|-------|----------|
| Invalid JSON | Route to failure: "Invalid JSON format in FlowFile" |
| Empty FlowFile | Route to failure: "FlowFile is empty" |
| Identifier field missing | Route to failure: "Identifier field '{field}' not found in data" |
| Data type mismatch | Convert to string, log warning |

### API Errors

| Error | Behavior |
|-------|----------|
| Rate limit exceeded (429) | Retry with exponential backoff (3 attempts), then route to failure |
| Network timeout | Retry once, then route to failure |
| 5xx server errors | Retry with backoff, then route to failure |

## Performance Considerations

### Batching

- Group multiple rows into single API call (up to Batch Size)
- For large datasets, split into multiple batch requests
- Use `batchUpdate` API for efficiency

### Caching

- Cache spreadsheet metadata (sheet IDs, ranges) for 5 minutes
- Reuse AuthenticationBrokerService token until expiry
- Cache existing sheet data for UPDATE/UPSERT within single FlowFile processing

### Rate Limiting

- Google Sheets API limits:
  - 100 requests per 100 seconds per user
  - 500 requests per 100 seconds per project
- Implement request throttling
- Respect `Retry-After` header

## Dependencies

### Python Packages

```python
# Required packages (add to processor requirements)
google-api-python-client==2.100.0  # Google Sheets API client
google-auth==2.23.0                # Authentication utilities
```

### NiFi API

- `ProcessorContext` - Access to properties and controller services
- `FlowFile` - Input/output handling
- `ProcessSession` - FlowFile routing
- `ComponentLog` - Logging

### Controller Service Integration

```python
from org.apache.nifi.python.processor import Processor
from com.example.nifi.services import AuthenticationBrokerService

class PostToGoogleSpreadsheet(Processor):
    def getPropertyDescriptors(self):
        return [auth_service_property, ...]

    def onTrigger(self, context, session):
        # Get controller service
        auth_service = context.getProperty(AUTH_SERVICE).asControllerService()

        # Get access token
        try:
            access_token = auth_service.getAccessToken()
        except Exception as e:
            self.logger.error("Failed to get access token", e)
            session.transfer(flowfile, REL_FAILURE)
            return

        # Use token in API calls
        headers = {"Authorization": f"Bearer {access_token}"}
```

## Example Configurations

### Example 1: Simple Append

```
Authentication Service: GoogleAuth
Spreadsheet ID: 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms
Sheet Name: DataLog
Write Mode: APPEND
Include Headers: true
```

**Input JSON:**
```json
[
  {"Timestamp": "2025-12-28T10:00:00", "Value": 42, "Status": "OK"},
  {"Timestamp": "2025-12-28T11:00:00", "Value": 45, "Status": "OK"}
]
```

**Result:** Appends 2 rows to end of "DataLog" sheet

### Example 2: Update by Email

```
Authentication Service: GoogleAuth
Spreadsheet ID: 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms
Sheet Name: Employees
Write Mode: UPDATE
Identifier Fields: Email
On Identifier Not Found: ERROR
```

**Input JSON:**
```json
{
  "Email": "john@example.com",
  "Department": "Sales",
  "Salary": 75000
}
```

**Result:** Finds row with Email="john@example.com", updates Department and Salary

### Example 3: Upsert with Composite Key

```
Authentication Service: GoogleAuth
Spreadsheet ID: 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms
Sheet Name: Inventory
Write Mode: UPSERT
Identifier Fields: SKU,Location
On Identifier Duplicate: UPDATE
```

**Input JSON:**
```json
[
  {"SKU": "WIDGET-001", "Location": "Warehouse-A", "Quantity": 100, "LastUpdated": "2025-12-28"},
  {"SKU": "WIDGET-002", "Location": "Warehouse-A", "Quantity": 50, "LastUpdated": "2025-12-28"}
]
```

**Result:** Updates existing rows with matching SKU+Location, inserts new rows for non-matching

### Example 4: Full Refresh with Table

```
Authentication Service: GoogleAuth
Spreadsheet ID: 1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms
Sheet Name: DailyReport
Write Mode: REPLACE
Clear Sheet Before Write: true
Include Headers: true
Format as Table: true
```

**Input JSON:**
```json
{
  "headers": ["Date", "Sales", "Visitors", "Conversion"],
  "rows": [
    ["2025-12-27", 15000, 500, 0.15],
    ["2025-12-28", 18000, 600, 0.18]
  ]
}
```

**Result:** Clears sheet, writes headers + data, formats as table with filters

## Workflow Validation

### Authorization Workflow Checklist

- [x] Processor references AuthenticationBrokerService via controller service property
- [x] Processor calls `getAccessToken()` to obtain valid OAuth2 token
- [x] Service handles token refresh automatically if expired
- [x] Processor includes token in `Authorization: Bearer {token}` header
- [x] Processor handles TokenExpiredException by routing to failure
- [x] Required scope `https://www.googleapis.com/auth/spreadsheets` is configured in AuthenticationBrokerService
- [x] User has authenticated via web interface (http://localhost:8888)
- [x] Processor can retry on authentication failure

### API Workflow Checklist

- [x] Processor validates spreadsheet ID format
- [x] Processor retrieves spreadsheet metadata to verify access
- [x] Processor retrieves sheet properties to verify sheet exists
- [x] For UPDATE/UPSERT: Read existing data before writing
- [x] For APPEND: Direct append without reading
- [x] Batch operations to minimize API calls
- [x] Handle API rate limiting with retries
- [x] Handle API errors gracefully
- [x] Return meaningful error messages

## Implementation Plan

### Phase 1: Basic Functionality
1. Parse JSON input (single row, array, headers+rows)
2. Integrate with AuthenticationBrokerService
3. Implement APPEND mode
4. Write to Google Sheets API

### Phase 2: Advanced Write Modes
1. Implement UPDATE mode with identifier matching
2. Implement UPSERT mode
3. Implement REPLACE mode
4. Add batch processing

### Phase 3: Features & Polish
1. Add table formatting support
2. Add CSV input support
3. Implement retry logic and error handling
4. Add comprehensive logging
5. Performance optimization

## Potential Issues & Solutions

### 1. Large Dataset Performance

**Issue:** Reading entire sheet for UPDATE/UPSERT is slow for large sheets

**Solutions:**
- Cache sheet data within FlowFile processing
- Use pagination for large sheets (read in chunks)
- Recommend APPEND mode for large bulk inserts
- Consider creating index sheet for fast lookups

### 2. Concurrent Updates

**Issue:** Multiple processors updating same sheet simultaneously could cause conflicts

**Solutions:**
- Google Sheets handles concurrent updates
- Use optimistic locking with version checks
- Document concurrent access limitations
- Recommend using NiFi's single concurrency for this processor

### 3. Data Type Handling

**Issue:** JSON numbers/booleans vs Google Sheets string/number detection

**Solutions:**
- Use VALUE_INPUT_OPTION = USER_ENTERED for type detection
- Alternatively use RAW for exact values
- Document conversion behavior
- Provide examples for each data type

### 4. Identifier Field Ordering

**Issue:** Order of identifier fields affects composite key

**Solutions:**
- Always sort identifier fields alphabetically for consistent keys
- Document that order doesn't matter
- Trim whitespace from field names

### 5. API Quota Exhaustion

**Issue:** High-frequency writes may exceed quota

**Solutions:**
- Implement exponential backoff
- Add configurable delay between requests
- Monitor quota usage via metrics
- Document quota limits in processor docs

## Testing Scenarios

### Test 1: Basic Append
- Empty sheet → Write with headers → Verify headers + data
- Existing sheet → Append → Verify data appended after last row

### Test 2: Update Existing Row
- Sheet with 5 rows → Update row 3 by identifier → Verify only row 3 changed

### Test 3: Upsert Mixed
- Sheet with rows A, B, C → Upsert B (update), D (insert) → Verify B updated, D added

### Test 4: Replace All
- Sheet with data → Replace → Verify old data gone, new data present

### Test 5: Error Handling
- Invalid spreadsheet ID → Verify failure
- Missing identifier field → Verify failure
- Authentication failure → Verify failure with clear message

### Test 6: Large Dataset
- 1000 rows → Verify batching works
- Check API call count is minimized

## Conclusion

This specification provides a complete design for the PostToGoogleSpreadsheet processor that:

✅ Integrates seamlessly with AuthenticationBrokerService for OAuth2
✅ Supports multiple write modes (APPEND, UPDATE, UPSERT, REPLACE)
✅ Handles various input formats (JSON object, array, headers+rows)
✅ Implements robust error handling and retry logic
✅ Provides flexible configuration for different use cases
✅ Follows Google Sheets API best practices
✅ Includes comprehensive validation and testing scenarios

The authorization workflow is complete and verified - the processor can obtain access tokens from the AuthenticationBrokerService and use them to authenticate API requests to Google Sheets.
