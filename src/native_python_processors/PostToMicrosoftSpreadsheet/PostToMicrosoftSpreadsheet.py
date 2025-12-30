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
        version = '0.0.1-SNAPSHOT'
        description = """Posts data to a Microsoft Excel spreadsheet using the Microsoft Graph API.
Uses the AuthenticationBrokerService for OAuth2 authentication.
Supports multiple write modes: APPEND (add rows), UPDATE (modify existing rows),
UPSERT (update if exists, insert if not), and REPLACE (clear and write).
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
                    "APPEND - Add new rows to the end of existing data\n"
                    "UPDATE - Update existing rows based on identifier fields\n"
                    "UPSERT - Update existing rows or insert new ones if not found\n"
                    "REPLACE - Clear all data and write new data",
        required=True,
        default_value="APPEND",
        allowable_values=["APPEND", "UPDATE", "UPSERT", "REPLACE"],
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
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
        description="Comma-separated list of field names to use as row identifiers for UPDATE and UPSERT modes. "
                    "Example: 'Email' or 'FirstName,LastName'. Rows with matching identifier values will be updated.",
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
                    "Only applies to UPDATE and UPSERT modes when the spreadsheet already has headers.",
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
            write_mode = context.getProperty(self.WRITE_MODE).getValue()
            start_cell = context.getProperty(self.START_CELL).evaluateAttributeExpressions(flowfile).getValue()
            identifier_fields_str = context.getProperty(self.IDENTIFIER_FIELDS).evaluateAttributeExpressions(flowfile).getValue()
            include_header = context.getProperty(self.INCLUDE_HEADER_ROW).asBoolean()
            input_format = context.getProperty(self.INPUT_FORMAT).getValue()
            header_mismatch_strategy = context.getProperty(self.HEADER_MISMATCH_STRATEGY).getValue()

            # Parse identifier fields
            identifier_fields = []
            if identifier_fields_str:
                identifier_fields = [f.strip() for f in identifier_fields_str.split(',')]

            # Validate identifier fields for UPDATE/UPSERT modes
            if write_mode in ['UPDATE', 'UPSERT'] and not identifier_fields:
                self.logger.error(f"Identifier Fields are required for {write_mode} mode")
                return FlowFileTransformResult(relationship="failure",
                                              attributes={"error": f"Identifier Fields required for {write_mode} mode"})

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
                                                identifier_fields, header_mismatch_strategy, session_id)
            elif write_mode == "UPSERT":
                rows_written = self._upsert_rows(access_token, workbook_url, worksheet_name, headers, rows,
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

    def _get_worksheet_range(self, access_token, workbook_url, worksheet_name, session_id):
        """Get the used range of a worksheet."""
        url = f"{workbook_url}/worksheets/{worksheet_name}/usedRange"
        headers = self._get_session_headers(access_token, session_id)

        response = requests.get(url, headers=headers)

        if response.status_code == 200:
            range_data = response.json()
            return range_data.get('values', [])
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
        is_new_worksheet = not existing_data

        # If worksheet is empty and include_header is true, add headers first
        if is_new_worksheet and include_header:
            values_to_append.append(headers)

        values_to_append.extend(rows)

        # Determine the starting row
        start_row = len(existing_data) + 1 if existing_data else 1
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
        # Clear worksheet
        url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='A1:ZZ100000')/clear"
        headers_dict = self._get_session_headers(access_token, session_id)

        body = {
            'applyTo': 'Contents'
        }

        response = requests.post(url, headers=headers_dict, json=body)

        if response.status_code != 200 and response.status_code != 204:
            self.logger.warn(f"Failed to clear worksheet (continuing anyway): {response.status_code}")

        # Write new data
        values_to_write = []
        if include_header:
            values_to_write.append(headers)
        values_to_write.extend(rows)

        # Parse start cell (e.g., "A1" -> row 1, col A)
        import re
        match = re.match(r'([A-Z]+)(\d+)', start_cell.upper())
        if not match:
            raise ValueError(f"Invalid start cell format: {start_cell}")

        start_col_letter = match.group(1)
        start_row = int(match.group(2))

        end_col = self._column_letter(self._column_number(start_col_letter) + len(headers) - 1)
        end_row = start_row + len(values_to_write) - 1

        range_address = f"{worksheet_name}!{start_col_letter}{start_row}:{end_col}{end_row}"

        url = f"{workbook_url}/worksheets/{worksheet_name}/range(address='{range_address}')"
        headers_dict = self._get_session_headers(access_token, session_id)

        body = {
            'values': values_to_write
        }

        response = requests.patch(url, headers=headers_dict, json=body)

        if response.status_code != 200:
            raise Exception(f"Failed to write data: {response.status_code} - {response.text}")

        return len(rows)

    def _update_rows(self, access_token, workbook_url, worksheet_name, headers, rows, identifier_fields, header_mismatch_strategy, session_id):
        """Update existing rows based on identifier fields."""
        # Get existing data
        existing_data = self._get_worksheet_range(access_token, workbook_url, worksheet_name, session_id)

        if not existing_data:
            raise Exception("Cannot UPDATE: Worksheet is empty. Use APPEND or UPSERT mode instead.")

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

        # Update rows
        rows_updated = 0

        for row in rows:
            row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
            key = tuple(row_dict.get(field, '') for field in identifier_fields)

            if key in existing_map:
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
                else:
                    self.logger.warn(f"Failed to update row {row_number}: {response.status_code}")
            else:
                self.logger.warn(f"Row with identifiers {key} not found in worksheet, skipping")

        return rows_updated

    def _upsert_rows(self, access_token, workbook_url, worksheet_name, headers, rows, identifier_fields, include_header, header_mismatch_strategy, session_id):
        """Update existing rows or insert new ones."""
        # Get existing data
        existing_data = self._get_worksheet_range(access_token, workbook_url, worksheet_name, session_id)

        if not existing_data:
            # Worksheet is empty, treat as REPLACE
            return self._replace_all(access_token, workbook_url, worksheet_name, headers, rows, "A1", include_header, session_id)

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
            existing_map[key] = row_idx + 2

        # Separate updates and inserts
        new_rows = []
        rows_updated = 0
        rows_inserted = 0

        for row in rows:
            row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
            key = tuple(row_dict.get(field, '') for field in identifier_fields)

            if key in existing_map:
                # Update existing row
                row_number = existing_map[key]

                updated_row = []
                for header in existing_headers:
                    if header_mismatch_strategy == "IGNORE_FIELDS" and header in unrecognized_headers:
                        continue
                    updated_row.append(row_dict.get(header, ''))

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
            else:
                # Insert new row
                new_rows.append(row)
                rows_inserted += 1

        # Execute inserts - remap new_rows to match existing spreadsheet column order
        if new_rows:
            remapped_rows = []
            for row in new_rows:
                row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
                remapped_row = [row_dict.get(header, '') for header in existing_headers]
                remapped_rows.append(remapped_row)

            # Append using existing_headers order
            self._append_rows(access_token, workbook_url, worksheet_name, existing_headers, remapped_rows, False, session_id)

        self.logger.info(f"UPSERT completed: {rows_updated} updated, {rows_inserted} inserted")
        return rows_updated + rows_inserted

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