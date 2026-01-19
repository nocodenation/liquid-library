# PostToMicrosoftSpreadsheet Parameter Acquisition Guide

This guide explains how to acquire the necessary parameters for accessing Microsoft Excel spreadsheets using the PostToMicrosoftSpreadsheet processor.

## Overview

The processor supports **two methods** for identifying Excel files:

- **Mode 1: File Path** (Recommended) - Simple path like `/Documents/sales.xlsx`
- **Mode 2: Drive ID + Item ID** (Advanced) - For SharePoint sites and specific drives

## ⚠️ Important Notes About Write Modes

### Recommended Write Mode: REPLACE

**REPLACE mode works most reliably** because it:
- Clears the sheet and waits 1 second for the clear to propagate
- Creates a fresh session after clearing to avoid cache issues
- Writes data to a known location (Start Cell, default A1)

### UPDATE and APPEND Modes - Cache Limitations

**Microsoft Graph API Caching Behavior:**
- UPDATE and APPEND modes rely on reading existing data using the `usedRange` API
- Microsoft's API **aggressively caches** worksheet data for up to 60 seconds
- After manually clearing a sheet in Excel, the API may still return stale data
- This causes UPDATE/APPEND to write to incorrect locations or fail

**Workarounds:**

1. **Wait 60+ seconds** after manually clearing a sheet before using UPDATE/APPEND modes
2. **Use REPLACE mode instead** - it handles clearing and writing in one operation with cache invalidation
3. **Delete and recreate** the worksheet instead of clearing it
4. **Use a different worksheet** for each write operation

**Symptoms of cache issues:**
- Data appears in unexpected columns (e.g., H-N instead of A-F)
- Headers missing even when "Include Header Row" is enabled
- Empty cells detected as data
- See processor logs for "UsedRange address: Sheet1!H5:N7" or similar unexpected ranges

---

## Mode 1: File Path Method (Recommended)

### ✅ When to Use
- ✅ Files in your **personal OneDrive for Business**
- ✅ Simple, user-friendly approach
- ✅ Most common scenario

### Required Parameters
- **File Location Method**: `FILE_PATH`
- **File Path**: Example: `/Documents/MyFolder/sales.xlsx`

### Step-by-Step: How to Get the File Path

#### Option A: From Excel Web Interface

1. **Open your Excel file** in the web browser (OneDrive or SharePoint)

2. **Look at the browser URL**, it will look like:
   ```
   https://[tenant].sharepoint.com/personal/[user]/Documents/MyFolder/sales.xlsx
   ```
   OR
   ```
   https://onedrive.live.com/...path...
   ```

3. **Extract the path** starting from `/Documents`:
   ```
   /Documents/MyFolder/sales.xlsx
   ```

4. **Use this as your File Path** in the processor configuration

#### Option B: Using Microsoft Graph Explorer

1. Go to https://developer.microsoft.com/en-us/graph/graph-explorer

2. **Sign in** with your account

3. **Make a request** to list your files:
   ```
   GET https://graph.microsoft.com/v1.0/me/drive/root/children
   ```

4. **Find your file** in the response, look for the `parentReference.path` and `name` fields:
   ```json
   {
     "name": "sales.xlsx",
     "parentReference": {
       "path": "/drive/root:/Documents/MyFolder"
     }
   }
   ```

5. **Construct the path**:
   ```
   /Documents/MyFolder/sales.xlsx
   ```

#### Option C: From File Properties Dialog

1. **Right-click** your Excel file in OneDrive → **Details** → **Path**

2. Copy the path shown (it may include the drive, remove that part)

3. Keep only the path from `/Documents` onwards

### Examples

| Scenario | File Path |
|----------|-----------|
| File in root Documents | `/Documents/sales.xlsx` |
| File in subfolder | `/Documents/Reports/2025/sales.xlsx` |
| File with spaces | `/Documents/My Reports/sales data.xlsx` |
| Non-English characters | `/Documents/Données/ventes.xlsx` |

**Note**: Spaces and special characters are automatically URL-encoded by the processor.

---

## Mode 2: Drive ID + Item ID Method (Advanced)

### ✅ When to Use
- ✅ Files in **SharePoint sites** (not personal OneDrive)
- ✅ Shared drives or group drives
- ✅ Need precise identification across multiple drives
- ✅ Programmatic/automated scenarios

### Required Parameters
- **File Location Method**: `DRIVE_ITEM_ID`
- **Drive ID**: Example: `b!-RIj2DuyvEy...`
- **Item ID**: Example: `01CYZLFJGUJ7JHBSZDFZFL25KSZGQTVAUN`

### Step-by-Step: How to Get Drive ID and Item ID

#### Method 1: Using Microsoft Graph Explorer (Recommended)

**Step 1: List Your Drives**

1. Go to https://developer.microsoft.com/en-us/graph/graph-explorer

2. **Sign in** with your account

3. **Request your drives**:
   ```
   GET https://graph.microsoft.com/v1.0/me/drives
   ```

4. **Find the drive** containing your file in the response:
   ```json
   {
     "value": [
       {
         "id": "b!-RIj2DuyvEy...",
         "name": "Documents",
         "driveType": "business",
         "owner": {
           "user": {
             "displayName": "John Doe"
           }
         }
       }
     ]
   }
   ```

5. **Copy the Drive ID**: `b!-RIj2DuyvEy...`

**Step 2: List Files in the Drive**

1. **Request files** using the Drive ID:
   ```
   GET https://graph.microsoft.com/v1.0/drives/{drive-id}/root/children
   ```

   Example:
   ```
   GET https://graph.microsoft.com/v1.0/drives/b!-RIj2DuyvEy.../root/children
   ```

2. **Navigate to subfolders** if needed:
   ```
   GET https://graph.microsoft.com/v1.0/drives/{drive-id}/root:/Documents/MyFolder:/children
   ```

3. **Find your Excel file** in the response:
   ```json
   {
     "value": [
       {
         "id": "01CYZLFJGUJ7JHBSZDFZFL25KSZGQTVAUN",
         "name": "sales.xlsx",
         "file": {
           "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
         }
       }
     ]
   }
   ```

4. **Copy the Item ID**: `01CYZLFJGUJ7JHBSZDFZFL25KSZGQTVAUN`

#### Method 2: From SharePoint URL

**For SharePoint files:**

1. **Open the Excel file** in SharePoint

2. **Browser URL** will look like:
   ```
   https://[tenant].sharepoint.com/sites/[site]/Shared%20Documents/sales.xlsx
   ```

3. **Use Graph Explorer** to query the site:
   ```
   GET https://graph.microsoft.com/v1.0/sites/[tenant].sharepoint.com:/sites/[site]
   ```

4. **Get the site ID** from response, then query:
   ```
   GET https://graph.microsoft.com/v1.0/sites/{site-id}/drives
   ```

5. **Find the "Documents" drive**, copy Drive ID

6. **Query for your file**:
   ```
   GET https://graph.microsoft.com/v1.0/drives/{drive-id}/root:/sales.xlsx
   ```

7. **Copy the Item ID** from response

#### Method 3: Using PowerShell with Microsoft Graph SDK

```powershell
# Install Microsoft Graph SDK
Install-Module Microsoft.Graph -Scope CurrentUser

# Connect
Connect-MgGraph -Scopes "Files.Read"

# List drives
Get-MgUserDrive

# Get Drive ID
$driveId = (Get-MgUserDrive).Id

# List files in drive
Get-MgDriveItem -DriveId $driveId -DriveItemId root

# Search for specific file
$file = Get-MgDriveItem -DriveId $driveId -DriveItemId root |
    Where-Object {$_.Name -eq "sales.xlsx"}

# Get Item ID
$itemId = $file.Id

Write-Host "Drive ID: $driveId"
Write-Host "Item ID: $itemId"
```

### Examples

| Scenario | Drive ID | Item ID |
|----------|----------|---------|
| Personal OneDrive | `b!-RIj2DuyvEy...` | `01CYZLFJGUJ7...` |
| SharePoint Site | `b!Kj8uXdB3ck...` | `01BYE5RZ6Q...` |
| Shared Drive | `b!OeDVWrHY...` | `01OEL39SK...` |

---

## Comparison: When to Use Each Mode

| Aspect | Mode 1: File Path | Mode 2: Drive ID + Item ID |
|--------|-------------------|----------------------------|
| **Ease of Use** | ⭐⭐⭐⭐⭐ Very Easy | ⭐⭐ Moderate |
| **Setup Time** | < 1 minute | 5-10 minutes |
| **Use Case** | Personal OneDrive | SharePoint, Shared Drives |
| **Flexibility** | Good for most cases | Works everywhere |
| **File Moves** | Path breaks if file moved | ID persists across moves |
| **Recommended For** | Beginners, Simple setups | Advanced users, Automation |

---

## Configuration Examples

### Example 1: Mode 1 - Personal OneDrive File

```
File Location Method: FILE_PATH
File Path: /Documents/Sales/2025/monthly_report.xlsx
Worksheet Name: Sheet1
```

### Example 2: Mode 2 - SharePoint Site File

```
File Location Method: DRIVE_ITEM_ID
Drive ID: b!-RIj2DuyvEy38k8PPSqCTfqd7kbEz9xBr5vXJvPqJd7D4LkP_mH1SqwZ8YhZq7qN
Item ID: 01CYZLFJGUJ7JHBSZDFZFL25KSZGQTVAUN
Worksheet Name: Sales Data
```

### Example 3: Using FlowFile Attributes

```
File Location Method: FILE_PATH
File Path: ${excel.file.path}
Worksheet Name: ${excel.worksheet}
```

---

## Troubleshooting

### Problem: "File not found" error with File Path

**Solution:**
1. Verify the file exists in OneDrive
2. Check the path starts with `/Documents` or `/drive/root:`
3. Try using Microsoft Graph Explorer to confirm the path:
   ```
   GET https://graph.microsoft.com/v1.0/me/drive/root:/Documents/yourfile.xlsx
   ```

### Problem: "Unauthorized" or 401 error

**Solution:**
1. Verify AuthenticationBrokerService is configured with Microsoft provider
2. Check you've authenticated (visit http://localhost:8889/)
3. Ensure scopes include `Files.ReadWrite`

### Problem: Invalid Drive ID or Item ID

**Solution:**
1. IDs should be long alphanumeric strings (30+ characters)
2. Use Microsoft Graph Explorer to verify IDs
3. Don't confuse Drive ID with Site ID

### Problem: File path with spaces doesn't work

**Solution:**
- The processor automatically URL-encodes spaces
- Use the path as-is: `/Documents/My Files/report.xlsx`
- Don't manually encode spaces to `%20`

---

## Best Practices

### 1. Prefer Mode 1 (File Path) for Simplicity
Unless you have a specific reason to use Drive ID + Item ID, use File Path method.

### 2. Use Environment Variables or FlowFile Attributes
Instead of hardcoding paths:
```
File Path: ${env.EXCEL_FILE_PATH}
```

### 3. Test with Microsoft Graph Explorer First
Before configuring the processor, test your path or IDs in Graph Explorer to ensure they work.

### 4. Document Your Parameters
Keep a record of your Drive IDs and Item IDs if using Mode 2, as they're not human-readable.

### 5. Use Consistent Worksheet Names
Excel worksheet names are case-sensitive. Use exact names like `Sheet1`, not `sheet1`.

---

## Additional Resources

- **Microsoft Graph Explorer**: https://developer.microsoft.com/en-us/graph/graph-explorer
- **Graph API Documentation**: https://learn.microsoft.com/en-us/graph/api/resources/excel
- **OneDrive API Reference**: https://learn.microsoft.com/en-us/graph/api/resources/onedrive

---

## Quick Reference Card

### Mode 1 Setup (1 minute)

1. Open Excel file in browser
2. Copy path from URL after `/Documents`
3. Configure processor:
   - File Location Method: `FILE_PATH`
   - File Path: `/Documents/yourfile.xlsx`

### Mode 2 Setup (5 minutes)

1. Go to Graph Explorer
2. GET `/me/drives` → Copy Drive ID
3. GET `/drives/{drive-id}/root/children` → Find file → Copy Item ID
4. Configure processor:
   - File Location Method: `DRIVE_ITEM_ID`
   - Drive ID: `b!...`
   - Item ID: `01...`

---

*This guide covers all methods for acquiring Excel file parameters for the PostToMicrosoftSpreadsheet processor.*