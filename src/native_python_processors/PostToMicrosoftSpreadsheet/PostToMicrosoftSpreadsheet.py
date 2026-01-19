import json
import csv
import io
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

# Attempt to import required libraries
try:
    import requests
except ImportError:
    requests = None


class PostToMicrosoftSpreadsheet(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.2-SNAPSHOT'
        description = """Posts data to a Microsoft Excel spreadsheet using the Microsoft Graph API.
Uses the AuthenticationBrokerService for OAuth2 authentication.
Supports write modes: REPLACE (clear and write), APPEND (add rows), UPDATE (update matching rows, append others).
Accepts JSON (array of objects, headers+rows array, headers+rows object) or CSV format.
Works with Excel files (.xlsx) in OneDrive for Business or SharePoint."""
        tags = ['microsoft', 'excel', 'spreadsheet', 'write', 'oauth2', 'graph', 'python']
        dependencies = ['requests']

    def __init__(self, **kwargs):
        super().__init__()
        self.auth_service = None

    # Property Descriptors
    AUTHENTICATION_SERVICE = PropertyDescriptor(
        name="Authentication Service",
        description="The AuthenticationBrokerService to use for OAuth2 authentication with Microsoft Graph API.",
        required=True,
        controller_service_definition="org.nocodenation.nifi.authenticationbroker.AuthenticationBrokerService",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    FILE_LOCATION_METHOD = PropertyDescriptor(
        name="File Location Method",
        description="How to identify the Excel file:\n"
                    "FILE_PATH - Simple path (e.g., /Documents/sales.xlsx) - Recommended\n"
                    "DRIVE_ITEM_ID - Drive ID + Item ID - For advanced scenarios",
        required=True,
        default_value="FILE_PATH",
        allowable_values=["FILE_PATH", "DRIVE_ITEM_ID"],
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    FILE_PATH = PropertyDescriptor(
        name="File Path",
        description="Path to the Excel file in OneDrive/SharePoint (e.g., /Documents/sales.xlsx). "
                    "Used when File Location Method is FILE_PATH. "
                    "To find: Open file in web browser, path is after '/Documents'.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    DRIVE_ID = PropertyDescriptor(
        name="Drive ID",
        description="The unique identifier of the drive containing the Excel file. "
                    "Used when File Location Method is DRIVE_ITEM_ID. "
                    "Required for SharePoint sites.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    ITEM_ID = PropertyDescriptor(
        name="Item ID",
        description="The unique identifier of the Excel file. "
                    "Used when File Location Method is DRIVE_ITEM_ID.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    WORKSHEET_NAME = PropertyDescriptor(
        name="Worksheet Name",
        description="The name of the worksheet (tab) within the Excel file where data will be written. "
                    "If not specified, defaults to the first worksheet.",
        required=False,
        default_value="Sheet1",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    WRITE_MODE = PropertyDescriptor(
        name="Write Mode",
        description="Determines how data is written to the spreadsheet:\n"
                    "REPLACE - Clear all data and write new data\n"
                    "APPEND - Add new rows to the end; if sheet is empty, acts like REPLACE\n"
                    "UPDATE - Update rows matching Identifier Fields; if no match found, APPEND the row\n"
                    "Supports Expression Language to dynamically set mode from FlowFile attributes.",
        required=True,
        default_value="REPLACE",
        allowable_values=["REPLACE", "APPEND", "UPDATE"],
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    START_CELL = PropertyDescriptor(
        name="Start Cell",
        description="The starting cell in A1 notation where data should be written (e.g., 'A1', 'B2'). "
                    "Used when the worksheet is empty or in REPLACE mode.",
        required=False,
        default_value="A1",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    IDENTIFIER_FIELDS = PropertyDescriptor(
        name="Identifier Fields",
        description="Comma-separated list of field names to use as row identifiers for UPDATE mode. "
                    "Example: 'Email' or 'FirstName,LastName'. Rows with matching identifier values will be updated; "
                    "rows without a match will be appended. Supports Expression Language.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    INCLUDE_HEADER_ROW = PropertyDescriptor(
        name="Include Header Row",
        description="If true, writes column headers as the first row when the worksheet is empty or in REPLACE mode.",
        required=True,
        default_value="true",
        allowable_values=["true", "false"],
        validators=[StandardValidators.BOOLEAN_VALIDATOR]
    )

    INPUT_FORMAT = PropertyDescriptor(
        name="Input Format",
        description="The format of the incoming FlowFile content. "
                    "JSON supports: array of objects, headers+rows array, headers+rows object. "
                    "CSV must have a header row.",
        required=True,
        default_value="JSON",
        allowable_values=["JSON", "CSV"],
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    HEADER_MISMATCH_STRATEGY = PropertyDescriptor(
        name="Header Mismatch Strategy",
        description="How to handle incoming data with field names not found in the spreadsheet:\n"
                    "ADD_COLUMNS - Add new columns for unrecognized field names (appends to the right)\n"
                    "IGNORE_FIELDS - Skip values for unrecognized field names and log a warning\n"
                    "FAIL - Reject the data with an error listing the unrecognized field names\n"
                    "Only applies to UPDATE mode when the spreadsheet already has headers.",
        required=True,
        default_value="FAIL",
        allowable_values=["ADD_COLUMNS", "IGNORE_FIELDS", "FAIL"],
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [
            self.AUTHENTICATION_SERVICE,
            self.FILE_LOCATION_METHOD,
            self.FILE_PATH,
            self.DRIVE_ID,
            self.ITEM_ID,
            self.WORKSHEET_NAME,
            self.WRITE_MODE,
            self.START_CELL,
            self.IDENTIFIER_FIELDS,
            self.INCLUDE_HEADER_ROW,
            self.INPUT_FORMAT,
            self.HEADER_MISMATCH_STRATEGY
        ]

    def onScheduled(self, context):
        """Called when the processor is scheduled to run."""
        if requests is None:
            self.logger.error("requests library not found. Please ensure requirements.txt is installed.")
            return

        # Get the AuthenticationBrokerService
        self.auth_service = context.getProperty(self.AUTHENTICATION_SERVICE).asControllerService()
        if self.auth_service is None:
            self.logger.error("AuthenticationBrokerService not configured")
            return

        self.logger.info("PostToMicrosoftSpreadsheet processor initialized")

    def transform(self, context, flowfile):
        """Transform the incoming FlowFile by posting its data to Microsoft Excel."""
        try:
            # Check authentication
            if self.auth_service is None:
                self.logger.error("AuthenticationBrokerService not configured")
                return FlowFileTransformResult(relationship="failure")

            if not self.auth_service.isAuthenticated():
                self.logger.error("Not authenticated with Microsoft. Please authenticate via the AuthenticationBrokerService.")
                return FlowFileTransformResult(relationship="failure", attributes={"error": "Not authenticated"})

            # Get access token
            try:
                access_token = self.auth_service.getAccessToken()
            except Exception as e:
                self.logger.error(f"Failed to get access token: {str(e)}")
                return FlowFileTransformResult(relationship="failure", attributes={"error": f"Token error: {str(e)}"})

            # Get file location parameters
            file_location_method = context.getProperty(self.FILE_LOCATION_METHOD).getValue()

            # Build workbook URL based on location method
            if file_location_method == "FILE_PATH":
                file_path = context.getProperty(self.FILE_PATH).evaluateAttributeExpressions(flowfile).getValue()
                if not file_path:
                    self.logger.error("File Path is required when using FILE_PATH method")
                    return FlowFileTransformResult(relationship="failure", attributes={"error": "File Path required"})

                # URL encode the path
                from urllib.parse import quote
                encoded_path = quote(file_path)
                workbook_url = f"https://graph.microsoft.com/v1.0/me/drive/root:{encoded_path}:/workbook"

            else:  # DRIVE_ITEM_ID
                drive_id = context.getProperty(self.DRIVE_ID).evaluateAttributeExpressions(flowfile).getValue()
                item_id = context.getProperty(self.ITEM_ID).evaluateAttributeExpressions(flowfile).getValue()

                if not drive_id or not item_id:
                    self.logger.error("Both Drive ID and Item ID are required when using DRIVE_ITEM_ID method")
                    return FlowFileTransformResult(relationship="failure", attributes={"error": "Drive ID and Item ID required"})

                workbook_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/{item_id}/workbook"

            # Get other properties
            worksheet_name = context.getProperty(self.WORKSHEET_NAME).evaluateAttributeExpressions(flowfile).getValue()
            write_mode = context.getProperty(self.WRITE_MODE).evaluateAttributeExpressions(flowfile).getValue()
            start_cell = context.getProperty(self.START_CELL).evaluateAttributeExpressions(flowfile).getValue()
            identifier_fields_str = context.getProperty(self.IDENTIFIER_FIELDS).evaluateAttributeExpressions(flowfile).getValue()
            include_header = context.getProperty(self.INCLUDE_HEADER_ROW).asBoolean()
            input_format = context.getProperty(self.INPUT_FORMAT).getValue()
            header_mismatch_strategy = context.getProperty(self.HEADER_MISMATCH_STRATEGY).getValue()

            # Validate write mode
            valid_modes = ['REPLACE', 'APPEND', 'UPDATE']
            if write_mode not in valid_modes:
                self.logger.error(f"Invalid Write Mode: {write_mode}. Must be one of: {', '.join(valid_modes)}")
                return FlowFileTransformResult(relationship="failure",
                                              attributes={"error": f"Invalid Write Mode: {write_mode}"})

            # Parse identifier fields
            identifier_fields = []
            if identifier_fields_str:
                identifier_fields = [f.strip() for f in identifier_fields_str.split(',')]

            # Validate identifier fields for UPDATE mode
            if write_mode == 'UPDATE' and not identifier_fields:
                self.logger.error("Identifier Fields are required for UPDATE mode")
                return FlowFileTransformResult(relationship="failure",
                                              attributes={"error": "Identifier Fields required for UPDATE mode"})

            # Read FlowFile content
            content = flowfile.getContentsAsBytes().decode('utf-8')

            # Parse input data
            headers, rows = self._parse_input(content, input_format)

            if not headers or not rows:
                self.logger.warn("No data to write")
                return FlowFileTransformResult(relationship="success",
                                              attributes={"rows_written": "0", "message": "No data to write"})

            # Create session for better performance
            session_id = self._create_session(access_token, workbook_url)

            # Execute write operation based on mode
            if write_mode == "APPEND":
                rows_written = self._append_rows(access_token, workbook_url, worksheet_name, headers, rows,
                                                 include_header, session_id)
            elif write_mode == "REPLACE":
                rows_written = self._replace_all(access_token, workbook_url, worksheet_name, headers, rows,
                                                 start_cell, include_header, session_id)
            elif write_mode == "UPDATE":
                rows_written = self._update_rows(access_token, workbook_url, worksheet_name, headers, rows,
                                                identifier_fields, include_header, header_mismatch_strategy, session_id)
            else:
                raise ValueError(f"Unsupported write mode: {write_mode}")

            # Close session
            self._close_session(access_token, workbook_url, session_id)

            self.logger.info(f"Successfully wrote {rows_written} rows in {write_mode} mode")
            return FlowFileTransformResult(relationship="success",
                                          attributes={"rows_written": str(rows_written),
                                                     "write_mode": write_mode,
                                                     "worksheet_name": worksheet_name})

        except Exception as e:
            import traceback
            error_details = f"Error posting to Microsoft Excel: {str(e)}\n{traceback.format_exc()}"
            self.logger.error(error_details)
            return FlowFileTransformResult(relationship="failure",
                                          attributes={"error": str(e)})

    def _create_session(self, access_token, workbook_url):
        """Create a workbook session for better performance."""
        url = f"{workbook_url}/createSession"
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }
        body = {
            'persistChanges': True
        }

        response = requests.post(url, headers=headers, json=body)

        if response.status_code == 201:
            session_data = response.json()
            session_id = session_data.get('id')
            self.logger.info(f"Created workbook session: {session_id}")
            return session_id
        else:
            self.logger.warn(f"Failed to create session: {response.status_code} - {response.text}")
            return None

    def _close_session(self, access_token, workbook_url, session_id):
        """Close the workbook session."""
        if not session_id:
            return

        url = f"{workbook_url}/closeSession"
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json',
            'workbook-session-id': session_id
        }

        response = requests.post(url, headers=headers)

        if response.status_code == 204:
            self.logger.info(f"Closed workbook session: {session_id}")
        else:
            self.logger.warn(f"Failed to close session: {response.status_code}")

    def _get_session_headers(self, access_token, session_id):
        """Get headers with session ID."""
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }
        if session_id:
            headers['workbook-session-id'] = session_id
        return headers

    def _parse_input(self, content, input_format):
        """Parse input content and return headers and rows."""
        if input_format == "CSV":
            return self._parse_csv(content)
        else:
            return self._parse_json(content)

    def _parse_csv(self, content):
        """Parse CSV content."""
        reader = csv.reader(io.StringIO(content))
        rows_list = list(reader)

        if not rows_list:
            return None, None

        headers = rows_list[0]
        data_rows = rows_list[1:]

        return headers, data_rows

    def _parse_json(self, content):
        """Parse JSON content in various formats."""
        data = json.loads(content)

        # Format 1: Array of objects
        if isinstance(data, list) and len(data) > 0 and isinstance(data[0], dict):
            headers = list(data[0].keys())
            rows = [[row.get(h, '') for h in headers] for row in data]
            return headers, rows

        # Format 2 & 3: Object with headers and rows
        if isinstance(data, dict) and 'headers' in data and 'rows' in data:
            headers = data['headers']
            rows_data = data['rows']

            # Format 2: Rows are arrays (positional mapping)
            if isinstance(rows_data[0], list):
                return headers, rows_data

            # Format 3: Rows are objects (key-value mapping)
            if isinstance(rows_data[0], dict):
                rows = [[row.get(h, '') for h in headers] for row in rows_data]
                return headers, rows

        raise ValueError(f"Unsupported JSON format. Expected array of objects or object with 'headers' and 'rows' fields.")

    def _get_worksheet_range(self, access_token, workbook_url, worksheet_name, session_id, use_session=True):
        """Get the used range of a worksheet.

        Args:
            use_session: If False, bypasses session caching to get fresh data.
                        Use False when checking for existing data in UPSERT mode to avoid stale cache.
        """
        url = f"{workbook_url}/worksheets/{worksheet_name}/usedRange"
        headers = self._get_session_headers(access_token, session_id if use_session else None)

        response = requests.get(url, headers=headers)

        if response.status_code == 200:
            range_data = response.json()
            values = range_data.get('values', [])
            address = range_data.get('address', '')

            # Log for debugging
            self.logger.info(f"UsedRange address: {address}, values shape: {len(values)}x{len(values[0]) if values else 0}")

            # Note: Microsoft may return address like "Sheet1!B1:G4" (starting at B) but the values
            # array is correctly sized and does NOT include row numbers. The "B" in the address just
            # indicates the visual range in Excel UI which displays row numbers in column A.
            # We should NOT strip any columns from the values array.

            return values
        elif response.status_code == 404:
            # Worksheet doesn't exist or is empty
            return []
        else:
            raise Exception(f"Failed to get worksheet data: {response.status_code} - {response.text}")

    def _append_rows(self, access_token, workbook_url, worksheet_name, headers, rows, include_header, session_id):
        """Append rows to the end of the worksheet."""
        # Check if worksheet is empty
        existing_data = self._get_worksheet_range(access_token, workbook_url, worksheet_name, session_id)

        values_to_append = []
        # Worksheet is considered new/empty if no data OR only empty cells
        is_new_worksheet = not existing_data or (len(existing_data) == 1 and all(cell == '' for cell in existing_data[0]))

        # If worksheet is empty and include_header is true, add headers first
        if is_new_worksheet and include_header:
            values_to_append.append(headers)

        values_to_append.extend(rows)

        # Determine the starting row
        # If worksheet is truly empty (including sheets with only empty cells), start at row 1
        if is_new_worksheet:
            start_row = 1
        else:
            start_row = len(existing_data) + 1

        end_col = self._column_letter(len(headers))
        range_address = f"{worksheet_name}!A{start_row}:{end_col}{start_row + len(values_to_append) - 1}"

        url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='{range_address}')"
        headers_dict = self._get_session_headers(access_token, session_id)

        body = {
            'values': values_to_append
        }

        response = requests.patch(url, headers=headers_dict, json=body)

        if response.status_code != 200:
            raise Exception(f"Failed to append rows: {response.status_code} - {response.text}")

        return len(rows)

    def _replace_all(self, access_token, workbook_url, worksheet_name, headers, rows, start_cell, include_header, session_id):
        """Clear all data and write new data."""
        import time
        import re

        # Close existing session if any, to invalidate cache before clearing
        if session_id:
            self.logger.info("REPLACE: Closing existing session before clear to invalidate cache")
            self._close_session(access_token, workbook_url, session_id)
            # Give server a moment to process session closure
            time.sleep(0.5)

        # Clear worksheet WITHOUT session (fresh, no cache)
        url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='A1:ZZ100000')/clear"
        headers_dict = self._get_session_headers(access_token, None)  # No session

        body = {
            'applyTo': 'Contents'
        }

        self.logger.info("REPLACE: Clearing worksheet without session")
        response = requests.post(url, headers=headers_dict, json=body)

        if response.status_code != 200 and response.status_code != 204:
            self.logger.warn(f"Failed to clear worksheet (continuing anyway): {response.status_code}")

        # Wait for clear operation to propagate
        self.logger.info("REPLACE: Waiting 1s for clear operation to propagate")
        time.sleep(1)

        # Create NEW session for writing
        new_session_id = self._create_session(access_token, workbook_url)
        self.logger.info(f"REPLACE: Created new session {new_session_id} for writing")

        # Write new data
        values_to_write = []
        if include_header:
            values_to_write.append(headers)
        values_to_write.extend(rows)

        # Parse start cell (e.g., "A1" -> row 1, col A)
        match = re.match(r'([A-Z]+)(\d+)', start_cell.upper())
        if not match:
            raise ValueError(f"Invalid start cell format: {start_cell}")

        start_col_letter = match.group(1)
        start_row = int(match.group(2))

        end_col = self._column_letter(self._column_number(start_col_letter) + len(headers) - 1)
        end_row = start_row + len(values_to_write) - 1

        range_address = f"{worksheet_name}!{start_col_letter}{start_row}:{end_col}{end_row}"

        self.logger.info(f"REPLACE: Writing to range {range_address}")
        self.logger.info(f"REPLACE: headers = {headers}")
        self.logger.info(f"REPLACE: values_to_write has {len(values_to_write)} rows x {len(values_to_write[0]) if values_to_write else 0} cols")
        self.logger.info(f"REPLACE: First row data = {values_to_write[0] if values_to_write else 'None'}")

        url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='{range_address}')"
        headers_dict = self._get_session_headers(access_token, new_session_id)

        body = {
            'values': values_to_write
        }

        response = requests.patch(url, headers=headers_dict, json=body)
        self.logger.info(f"REPLACE: Response status = {response.status_code}")

        if response.status_code != 200:
            raise Exception(f"Failed to write data: {response.status_code} - {response.text}")

        # Close the new session we created
        self._close_session(access_token, workbook_url, new_session_id)

        return len(rows)

    def _update_rows(self, access_token, workbook_url, worksheet_name, headers, rows, identifier_fields, include_header, header_mismatch_strategy, session_id):
        """Update existing rows matching identifier fields; append rows without a match.

        If worksheet is empty, acts like APPEND.
        """
        # Get existing data
        existing_data = self._get_worksheet_range(access_token, workbook_url, worksheet_name, session_id)

        # If worksheet is empty or contains only empty cells, append all rows
        if not existing_data or (len(existing_data) == 1 and all(cell == '' for cell in existing_data[0])):
            self.logger.info("UPDATE: Worksheet is empty, appending all rows")
            return self._append_rows(access_token, workbook_url, worksheet_name, headers, rows, include_header, session_id)

        # Assume first row is headers
        existing_headers = existing_data[0]
        existing_rows = existing_data[1:]

        # Check for header mismatches
        incoming_headers_set = set(headers)
        existing_headers_set = set(existing_headers)
        unrecognized_headers = incoming_headers_set - existing_headers_set

        if unrecognized_headers:
            if header_mismatch_strategy == "FAIL":
                recognized = sorted(existing_headers_set & incoming_headers_set)
                unrecognized = sorted(unrecognized_headers)
                error_msg = (f"Data contains unrecognized field header names: {', '.join(unrecognized)}. "
                           f"Recognized headers: {', '.join(recognized)}. "
                           f"To fix: (1) Set 'Header Mismatch Strategy' to 'ADD_COLUMNS' to add new columns, "
                           f"(2) Set to 'IGNORE_FIELDS' to skip unrecognized fields, "
                           f"(3) Or update your data to only include these headers: {', '.join(existing_headers)}")
                raise Exception(error_msg)
            elif header_mismatch_strategy == "IGNORE_FIELDS":
                self.logger.warn(f"Ignoring unrecognized field headers: {', '.join(sorted(unrecognized_headers))}")
            elif header_mismatch_strategy == "ADD_COLUMNS":
                self.logger.info(f"Adding new columns for unrecognized headers: {', '.join(sorted(unrecognized_headers))}")
                new_headers = sorted(unrecognized_headers)
                self._add_new_columns(access_token, workbook_url, worksheet_name, existing_headers, new_headers, session_id)
                # Re-fetch worksheet data
                existing_data = self._get_worksheet_range(access_token, workbook_url, worksheet_name, session_id)
                existing_headers = existing_data[0]
                existing_rows = existing_data[1:]

        # Find identifier column indices
        identifier_indices = []
        for field in identifier_fields:
            if field not in existing_headers:
                raise Exception(f"Identifier field '{field}' not found in worksheet headers")
            identifier_indices.append(existing_headers.index(field))

        # Build lookup map for existing rows
        existing_map = {}
        for row_idx, row in enumerate(existing_rows):
            key = tuple(row[i] if i < len(row) else '' for i in identifier_indices)
            existing_map[key] = row_idx + 2  # +2 because row 1 is headers, 1-indexed

        # Separate updates and appends
        rows_to_append = []
        rows_updated = 0

        for row in rows:
            row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
            key = tuple(row_dict.get(field, '') for field in identifier_fields)

            if key in existing_map:
                # UPDATE: Match found, update the row
                row_number = existing_map[key]

                # Map new row values to existing header order
                updated_row = []
                for header in existing_headers:
                    if header_mismatch_strategy == "IGNORE_FIELDS" and header in unrecognized_headers:
                        continue
                    updated_row.append(row_dict.get(header, ''))

                # Update the row
                end_col = self._column_letter(len(existing_headers))
                range_address = f"{worksheet_name}!A{row_number}:{end_col}{row_number}"

                url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='{range_address}')"
                headers_dict = self._get_session_headers(access_token, session_id)

                body = {
                    'values': [updated_row]
                }

                response = requests.patch(url, headers=headers_dict, json=body)

                if response.status_code == 200:
                    rows_updated += 1
                    self.logger.info(f"UPDATE: Updated row {row_number} with identifiers {key}")
                else:
                    self.logger.warn(f"UPDATE: Failed to update row {row_number}: {response.status_code}")
            else:
                # APPEND: No match found, will append this row
                rows_to_append.append(row)
                self.logger.info(f"UPDATE: No match for identifiers {key}, will append")

        # Append unmatched rows
        rows_appended = 0
        if rows_to_append:
            self.logger.info(f"UPDATE: Appending {len(rows_to_append)} unmatched rows")
            # Remap rows to match existing header order
            remapped_rows = []
            for row in rows_to_append:
                row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
                remapped_row = [row_dict.get(header, '') for header in existing_headers]
                remapped_rows.append(remapped_row)

            rows_appended = self._append_rows(access_token, workbook_url, worksheet_name, existing_headers, remapped_rows, False, session_id)

        self.logger.info(f"UPDATE completed: {rows_updated} updated, {rows_appended} appended")
        return rows_updated + rows_appended

    def _add_new_columns(self, access_token, workbook_url, worksheet_name, existing_headers, new_headers, session_id):
        """Add new column headers to the worksheet."""
        start_col = len(existing_headers) + 1
        start_col_letter = self._column_letter(start_col)
        end_col_letter = self._column_letter(start_col + len(new_headers) - 1)

        range_address = f"{worksheet_name}!{start_col_letter}1:{end_col_letter}1"

        url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='{range_address}')"
        headers_dict = self._get_session_headers(access_token, session_id)

        body = {
            'values': [new_headers]
        }

        response = requests.patch(url, headers=headers_dict, json=body)

        if response.status_code != 200:
            raise Exception(f"Failed to add new columns: {response.status_code} - {response.text}")

    def _column_letter(self, col_num):
        """Convert column number to letter (1 -> A, 27 -> AA, etc.)."""
        result = ""
        while col_num > 0:
            col_num -= 1
            result = chr(col_num % 26 + ord('A')) + result
            col_num //= 26
        return result

    def _column_number(self, col_letter):
        """Convert column letter to number (A -> 1, AA -> 27, etc.)."""
        result = 0
        for char in col_letter.upper():
            result = result * 26 + (ord(char) - ord('A') + 1)
        return result