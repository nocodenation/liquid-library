# Post To Microsoft Spreadsheet - Flow Definition

## Overview

This NiFi flow demonstrates how to use the **PostToMicrosoftSpreadsheet** processor to write data to Microsoft Excel spreadsheets in OneDrive for Business or SharePoint using the Microsoft Graph API.

## Flow Components

### Processors

1. **GenerateFlowFile** - Generates sample employee data in JSON format
   - Scheduled to run manually (via Run button)
   - Outputs JSON array of employee objects with fields:
     - Employee ID
     - Full Name
     - Email Address
     - Age
     - Department Name
     - Start Date

2. **PostToMicrosoftSpreadsheet** - Writes data to Excel spreadsheet
   - Version: 0.0.2-SNAPSHOT
   - Uses AuthenticationBrokerService for OAuth2 authentication
   - Supports three write modes: REPLACE, APPEND, UPDATE
   - Configured to write to OneDrive for Business

### Controller Services

1. **AuthenticationBrokerService** - Manages OAuth2 authentication with Microsoft
   - Provider: Microsoft
   - Web Server Port: 8889
   - Handles token refresh automatically
   - User must authenticate via web interface at http://localhost:8889/

### Connections

- **success** (GenerateFlowFile → PostToMicrosoftSpreadsheet): Routes generated data to spreadsheet processor
- **success** (PostToMicrosoftSpreadsheet): Routes successfully written data
- **failure** (PostToMicrosoftSpreadsheet): Routes failed write attempts for error handling

## Configuration

### Prerequisites

1. **Microsoft Azure AD Application**
   - Create an App Registration in Azure Portal
   - Configure redirect URI: `http://localhost:8889/callback`
   - Grant API permissions: `Files.ReadWrite` (Microsoft Graph)
   - Note the Client ID and Client Secret

2. **AuthenticationBrokerService Setup**
   - Configure with Microsoft provider
   - Enter Client ID and Client Secret
   - Set scopes: `https://graph.microsoft.com/Files.ReadWrite`
   - Enable the service
   - Authenticate by visiting http://localhost:8889/

3. **Excel File Location**
   - Create an Excel file in your OneDrive for Business
   - Note the file path (e.g., `/Documents/employees.xlsx`)
   - Or use Drive ID + Item ID for SharePoint files

### Processor Configuration

**PostToMicrosoftSpreadsheet Properties:**

| Property | Value | Description |
|----------|-------|-------------|
| Authentication Service | AuthenticationBrokerService | Reference to OAuth2 service |
| File Location Method | FILE_PATH | Use simple path method |
| File Path | `/Documents/Christofs Sheet.xlsx` | Path to Excel file in OneDrive |
| Worksheet Name | Sheet1 | Target worksheet tab name |
| Write Mode | REPLACE | Clear and write new data |
| Include Header Row | true | Write column headers |
| Input Format | JSON | Incoming data format |

## Write Modes Explained

### REPLACE Mode (Recommended)
- **Behavior**: Clears all data in the worksheet and writes new data
- **Best for**: Refreshing entire datasets, avoiding cache issues
- **Advantages**:
  - Most reliable across all scenarios
  - Built-in cache invalidation
  - Predictable behavior
- **Use when**: You want to completely replace the sheet contents

### APPEND Mode
- **Behavior**: Adds new rows to the end of existing data
- **Best for**: Logging, accumulating records over time
- **Limitations**:
  - May encounter Microsoft API caching issues after manual sheet clearing
  - Wait 60+ seconds after clearing before using
- **Use when**: You want to continuously add new records

### UPDATE Mode
- **Behavior**: Updates rows matching identifier fields; appends non-matching rows
- **Best for**: Maintaining a master dataset with changing values
- **Requirements**: Configure "Identifier Fields" property (e.g., "Email Address")
- **Limitations**:
  - Subject to same caching issues as APPEND
  - Requires existing headers in sheet
- **Use when**: You want to update specific records by ID while adding new ones

## Usage Instructions

### First-Time Setup

1. **Import the Flow**
   ```bash
   # In NiFi UI, go to the canvas
   # Right-click → Upload Template → Select flow.json
   # Or drag the file onto the canvas
   ```

2. **Configure AuthenticationBrokerService**
   - Open Controller Services (gear icon in Operate panel)
   - Find "AuthenticationBrokerService"
   - Click configure (gear icon)
   - Enter your Microsoft Client ID and Client Secret
   - Set Tenant ID (or use "common" for personal accounts)
   - Enable the service

3. **Authenticate with Microsoft**
   - Visit http://localhost:8889/
   - Click "Login with Microsoft"
   - Grant permissions
   - Verify successful authentication

4. **Update File Path**
   - Right-click PostToMicrosoftSpreadsheet processor
   - Configure → Properties
   - Update "File Path" to your Excel file location
   - Apply changes

5. **Run the Flow**
   - Select GenerateFlowFile processor
   - Click Run (play button)
   - Check success/failure queues
   - Verify data in Excel spreadsheet

### Testing Different Write Modes

**Test REPLACE Mode:**
```
1. Set Write Mode to "REPLACE"
2. Run flow multiple times
3. Observe: Sheet always contains 3 rows (same data each time)
```

**Test APPEND Mode:**
```
1. Set Write Mode to "APPEND"
2. Run flow 3 times
3. Observe: Sheet contains 9 rows (3 + 3 + 3)
4. Note: Clear sheet manually, wait 60+ seconds before next append
```

**Test UPDATE Mode:**
```
1. Set Write Mode to "UPDATE"
2. Set Identifier Fields to "Employee ID"
3. Run flow first time (creates 3 rows)
4. Modify data in GenerateFlowFile (change names/ages)
5. Run flow again
6. Observe: Existing employees updated, new employees appended
```

## Sample Data

The GenerateFlowFile processor creates this sample data:

```json
[
  {
    "Employee ID": "E002",
    "Full Name": "Jane Smith",
    "Email Address": "jane@example.com",
    "Age": 51,
    "Department Name": "VP Marketing",
    "Start Date": "20.03.2021"
  },
  {
    "Employee ID": "E004",
    "Full Name": "Alice Williams",
    "Email Address": "alice@example.com",
    "Age": 62,
    "Department Name": "Human Resources",
    "Start Date": "01.05.2023"
  },
  {
    "Employee ID": "E005",
    "Full Name": "Charlie Brown",
    "Email Address": "charlie@example.com",
    "Age": 73,
    "Department Name": "Information Technology",
    "Start Date": "15.09.2022"
  }
]
```

## Troubleshooting

### Issue: "Not authenticated with Microsoft"

**Solution:**
1. Visit http://localhost:8889/
2. Complete authentication flow
3. Verify "Authenticated" status shown

### Issue: "File not found" error

**Solution:**
1. Verify file path starts with `/Documents`
2. Check file exists in OneDrive for Business
3. Try using Microsoft Graph Explorer to validate path:
   ```
   GET https://graph.microsoft.com/v1.0/me/drive/root:/Documents/yourfile.xlsx
   ```

### Issue: Data appears in wrong columns after clearing sheet

**Solution:**
This is due to Microsoft Graph API caching (60-second delay).

**Workarounds:**
- Wait 60+ seconds after manually clearing the sheet
- Use REPLACE mode instead (handles clearing automatically)
- Delete and recreate the worksheet
- Use a different worksheet name

### Issue: Headers missing when using UPDATE/APPEND on empty sheet

**Solution:**
- Ensure "Include Header Row" is set to `true`
- Use REPLACE mode for first write to establish headers
- Or manually add headers to sheet first

## Best Practices

1. **Use REPLACE Mode for Reliability**
   - Avoids Microsoft API caching issues
   - Predictable behavior
   - Built-in cache invalidation

2. **Test with Microsoft Graph Explorer First**
   - Verify file paths and permissions
   - Test API access before configuring flow
   - URL: https://developer.microsoft.com/en-us/graph/graph-explorer

3. **Monitor Success/Failure Queues**
   - Check failure queue for error messages
   - Review processor logs in NiFi UI
   - Look for "UsedRange address" logs for debugging

4. **Use Expression Language for Dynamic Configuration**
   - Set Write Mode from FlowFile attributes: `${write.mode}`
   - Dynamic file paths: `${excel.file.path}`
   - Dynamic worksheet names: `${excel.worksheet}`

5. **Document Your Configuration**
   - Keep track of file paths and Drive IDs
   - Document identifier fields used for UPDATE mode
   - Note any special handling requirements

## Related Documentation

- [PARAMETER_ACQUISITION_GUIDE.md](../../src/native_python_processors/PostToMicrosoftSpreadsheet/PARAMETER_ACQUISITION_GUIDE.md) - Detailed guide for obtaining file paths and IDs
- [AUTHENTICATION_BROKER_SPECIFICATION.md](../../AUTHENTICATION_BROKER_SPECIFICATION.md) - OAuth2 authentication details
- [Microsoft Graph API Docs](https://learn.microsoft.com/en-us/graph/api/resources/excel) - Excel API reference

## Version History

- **v0.0.2-SNAPSHOT** (2026-01-01)
  - Removed UPSERT mode
  - Simplified to 3 write modes: REPLACE, APPEND, UPDATE
  - Added Expression Language support for Write Mode
  - Fixed empty sheet detection issues
  - Improved documentation on API caching limitations

## Support

For issues or questions:
- GitHub Issues: https://github.com/nocodenation/liquid-library/issues
- Documentation: See PARAMETER_ACQUISITION_GUIDE.md