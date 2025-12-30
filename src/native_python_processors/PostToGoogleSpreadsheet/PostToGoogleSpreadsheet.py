import json
import csv
import io
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

# Attempt to import Google Sheets API libraries
try:
    import requests
except ImportError:
    requests = None


class PostToGoogleSpreadsheet(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Posts data to a Google Spreadsheet using the Google Sheets API v4.
Uses the AuthenticationBrokerService for OAuth2 authentication.
Supports multiple write modes: APPEND (add rows), UPDATE (modify existing rows),
UPSERT (update if exists, insert if not), and REPLACE (clear and write).
Accepts JSON (array of objects, headers+rows array, headers+rows object) or CSV format."""
        tags = ['google', 'sheets', 'spreadsheet', 'write', 'oauth2', 'python']
        dependencies = ['requests']

    def __init__(self, **kwargs):
        super().__init__()
        self.auth_service = None

    # Property Descriptors
    AUTHENTICATION_SERVICE = PropertyDescriptor(
        name="Authentication Service",
        description="The AuthenticationBrokerService to use for OAuth2 authentication with Google Sheets API.",
        required=True,
        controller_service_definition="org.nocodenation.nifi.authenticationbroker.AuthenticationBrokerService",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    SPREADSHEET_ID = PropertyDescriptor(
        name="Spreadsheet ID",
        description="The ID of the Google Spreadsheet. This is found in the spreadsheet URL: "
                    "https://docs.google.com/spreadsheets/d/{SPREADSHEET_ID}/edit",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    SHEET_NAME = PropertyDescriptor(
        name="Sheet Name",
        description="The name of the sheet (tab) within the spreadsheet where data will be written. "
                    "If not specified, defaults to the first sheet.",
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
                    "Used when the sheet is empty or in REPLACE mode.",
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
        description="If true, writes column headers as the first row when the sheet is empty or in REPLACE mode.",
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

    VALUE_INPUT_OPTION = PropertyDescriptor(
        name="Value Input Option",
        description="How input data should be interpreted:\n"
                    "RAW - Values are stored as-is without parsing\n"
                    "USER_ENTERED - Values are parsed as if typed by a user (formulas, dates, numbers)",
        required=True,
        default_value="USER_ENTERED",
        allowable_values=["RAW", "USER_ENTERED"],
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    FORMAT_AS_TABLE = PropertyDescriptor(
        name="Format as Table",
        description="If true, formats the data range as a table with alternating row colors (banded rows). "
                    "This applies formatting to make the data visually organized like a table. "
                    "Only applicable in APPEND and REPLACE modes.",
        required=True,
        default_value="false",
        allowable_values=["true", "false"],
        validators=[StandardValidators.BOOLEAN_VALIDATOR]
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
            self.SPREADSHEET_ID,
            self.SHEET_NAME,
            self.WRITE_MODE,
            self.START_CELL,
            self.IDENTIFIER_FIELDS,
            self.INCLUDE_HEADER_ROW,
            self.INPUT_FORMAT,
            self.VALUE_INPUT_OPTION,
            self.FORMAT_AS_TABLE,
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

        self.logger.info("PostToGoogleSpreadsheet processor initialized")

    def transform(self, context, flowfile):
        """Transform the incoming FlowFile by posting its data to Google Sheets."""
        try:
            # Check authentication
            if self.auth_service is None:
                self.logger.error("AuthenticationBrokerService not configured")
                return FlowFileTransformResult(relationship="failure")

            if not self.auth_service.isAuthenticated():
                self.logger.error("Not authenticated with Google. Please authenticate via the AuthenticationBrokerService.")
                return FlowFileTransformResult(relationship="failure", attributes={"error": "Not authenticated"})

            # Get access token
            try:
                access_token = self.auth_service.getAccessToken()
            except Exception as e:
                self.logger.error(f"Failed to get access token: {str(e)}")
                return FlowFileTransformResult(relationship="failure", attributes={"error": f"Token error: {str(e)}"})

            # Get properties
            spreadsheet_id = context.getProperty(self.SPREADSHEET_ID).evaluateAttributeExpressions(flowfile).getValue()
            sheet_name = context.getProperty(self.SHEET_NAME).evaluateAttributeExpressions(flowfile).getValue()
            write_mode = context.getProperty(self.WRITE_MODE).getValue()
            start_cell = context.getProperty(self.START_CELL).evaluateAttributeExpressions(flowfile).getValue()
            identifier_fields_str = context.getProperty(self.IDENTIFIER_FIELDS).evaluateAttributeExpressions(flowfile).getValue()
            include_header = context.getProperty(self.INCLUDE_HEADER_ROW).asBoolean()
            input_format = context.getProperty(self.INPUT_FORMAT).getValue()
            value_input_option = context.getProperty(self.VALUE_INPUT_OPTION).getValue()
            format_as_table = context.getProperty(self.FORMAT_AS_TABLE).asBoolean()
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

            # Execute write operation based on mode
            if write_mode == "APPEND":
                rows_written = self._append_rows(access_token, spreadsheet_id, sheet_name, headers, rows,
                                                 include_header, value_input_option, format_as_table)
            elif write_mode == "REPLACE":
                rows_written = self._replace_all(access_token, spreadsheet_id, sheet_name, headers, rows,
                                                 start_cell, include_header, value_input_option, format_as_table)
            elif write_mode == "UPDATE":
                rows_written = self._update_rows(access_token, spreadsheet_id, sheet_name, headers, rows,
                                                identifier_fields, value_input_option, header_mismatch_strategy)
            elif write_mode == "UPSERT":
                rows_written = self._upsert_rows(access_token, spreadsheet_id, sheet_name, headers, rows,
                                                identifier_fields, include_header, value_input_option, header_mismatch_strategy)
            else:
                raise ValueError(f"Unsupported write mode: {write_mode}")

            self.logger.info(f"Successfully wrote {rows_written} rows in {write_mode} mode")
            return FlowFileTransformResult(relationship="success",
                                          attributes={"rows_written": str(rows_written),
                                                     "write_mode": write_mode,
                                                     "sheet_name": sheet_name})

        except Exception as e:
            import traceback
            error_details = f"Error posting to Google Spreadsheet: {str(e)}\n{traceback.format_exc()}"
            self.logger.error(error_details)
            return FlowFileTransformResult(relationship="failure",
                                          attributes={"error": str(e)})

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

    def _get_spreadsheet_metadata(self, access_token, spreadsheet_id):
        """Get spreadsheet metadata including available sheet names."""
        url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}"
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Accept': 'application/json'
        }

        response = requests.get(url, headers=headers)

        if response.status_code == 200:
            return response.json()
        else:
            raise Exception(f"Failed to get spreadsheet metadata: {response.status_code} - {response.text}")

    def _get_sheet_data(self, access_token, spreadsheet_id, sheet_name):
        """Fetch existing sheet data."""
        url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values/{sheet_name}"
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Accept': 'application/json'
        }

        response = requests.get(url, headers=headers)

        if response.status_code == 200:
            result = response.json()
            return result.get('values', [])
        elif response.status_code == 404:
            # Sheet doesn't exist or is empty
            return []
        elif response.status_code == 400:
            # Invalid sheet name - provide helpful error with available sheets
            try:
                metadata = self._get_spreadsheet_metadata(access_token, spreadsheet_id)
                available_sheets = [sheet['properties']['title'] for sheet in metadata.get('sheets', [])]
                raise Exception(f"Sheet '{sheet_name}' not found. Available sheets: {', '.join(available_sheets)}")
            except Exception as meta_error:
                raise Exception(f"Sheet '{sheet_name}' not found. Could not retrieve available sheets: {str(meta_error)}")
        else:
            raise Exception(f"Failed to get sheet data: {response.status_code} - {response.text}")

    def _append_rows(self, access_token, spreadsheet_id, sheet_name, headers, rows, include_header, value_input_option, format_as_table):
        """Append rows to the end of the sheet."""
        # Check if sheet is empty
        existing_data = self._get_sheet_data(access_token, spreadsheet_id, sheet_name)

        values_to_append = []
        is_new_sheet = not existing_data

        # If sheet is empty and include_header is true, add headers first
        if is_new_sheet and include_header:
            values_to_append.append(headers)

        values_to_append.extend(rows)

        url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values/{sheet_name}:append"
        headers_dict = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }

        body = {
            'values': values_to_append,
            'majorDimension': 'ROWS'
        }

        params = {
            'valueInputOption': value_input_option,
            'insertDataOption': 'INSERT_ROWS'
        }

        response = requests.post(url, headers=headers_dict, json=body, params=params)

        if response.status_code != 200:
            raise Exception(f"Failed to append rows: {response.status_code} - {response.text}")

        # Apply table formatting if requested and this is a new sheet with headers
        if format_as_table and is_new_sheet and include_header:
            num_cols = len(headers)
            num_rows = len(rows) + 1  # +1 for header row
            self._apply_table_formatting(access_token, spreadsheet_id, sheet_name, num_rows, num_cols)

        return len(rows)

    def _replace_all(self, access_token, spreadsheet_id, sheet_name, headers, rows, start_cell, include_header, value_input_option, format_as_table):
        """Clear all data and write new data."""
        # Clear existing data
        clear_url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values/{sheet_name}:clear"
        headers_dict = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }

        response = requests.post(clear_url, headers=headers_dict)

        if response.status_code != 200:
            raise Exception(f"Failed to clear sheet: {response.status_code} - {response.text}")

        # Write new data
        values_to_write = []
        if include_header:
            values_to_write.append(headers)
        values_to_write.extend(rows)

        range_notation = f"{sheet_name}!{start_cell}"
        update_url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values/{range_notation}"

        body = {
            'values': values_to_write,
            'majorDimension': 'ROWS'
        }

        params = {
            'valueInputOption': value_input_option
        }

        response = requests.put(update_url, headers=headers_dict, json=body, params=params)

        if response.status_code != 200:
            raise Exception(f"Failed to write data: {response.status_code} - {response.text}")

        # Apply table formatting if requested and headers are included
        if format_as_table and include_header:
            num_cols = len(headers)
            num_rows = len(rows) + 1  # +1 for header row
            self._apply_table_formatting(access_token, spreadsheet_id, sheet_name, num_rows, num_cols)

        return len(rows)

    def _apply_table_formatting(self, access_token, spreadsheet_id, sheet_name, num_rows, num_cols):
        """Apply table formatting (banded rows with header) to the data range."""
        try:
            # Get sheet ID from sheet name
            metadata = self._get_spreadsheet_metadata(access_token, spreadsheet_id)
            sheet_id = None
            for sheet in metadata.get('sheets', []):
                if sheet['properties']['title'] == sheet_name:
                    sheet_id = sheet['properties']['sheetId']
                    break

            if sheet_id is None:
                self.logger.warn(f"Could not find sheet ID for '{sheet_name}', skipping table formatting")
                return

            # Create batchUpdate request for table formatting
            url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}:batchUpdate"
            headers_dict = {
                'Authorization': f'Bearer {access_token}',
                'Content-Type': 'application/json'
            }

            requests_list = []

            # 1. Add banded range (alternating row colors)
            requests_list.append({
                'addBanding': {
                    'bandedRange': {
                        'range': {
                            'sheetId': sheet_id,
                            'startRowIndex': 0,
                            'endRowIndex': num_rows,
                            'startColumnIndex': 0,
                            'endColumnIndex': num_cols
                        },
                        'rowProperties': {
                            'headerColor': {
                                'red': 0.26,
                                'green': 0.52,
                                'blue': 0.96,
                                'alpha': 1.0
                            },
                            'firstBandColor': {
                                'red': 1.0,
                                'green': 1.0,
                                'blue': 1.0,
                                'alpha': 1.0
                            },
                            'secondBandColor': {
                                'red': 0.92,
                                'green': 0.95,
                                'blue': 0.99,
                                'alpha': 1.0
                            }
                        }
                    }
                }
            })

            # 2. Make header row bold
            requests_list.append({
                'repeatCell': {
                    'range': {
                        'sheetId': sheet_id,
                        'startRowIndex': 0,
                        'endRowIndex': 1,
                        'startColumnIndex': 0,
                        'endColumnIndex': num_cols
                    },
                    'cell': {
                        'userEnteredFormat': {
                            'textFormat': {
                                'bold': True,
                                'foregroundColor': {
                                    'red': 1.0,
                                    'green': 1.0,
                                    'blue': 1.0,
                                    'alpha': 1.0
                                }
                            }
                        }
                    },
                    'fields': 'userEnteredFormat.textFormat'
                }
            })

            # 3. Freeze header row
            requests_list.append({
                'updateSheetProperties': {
                    'properties': {
                        'sheetId': sheet_id,
                        'gridProperties': {
                            'frozenRowCount': 1
                        }
                    },
                    'fields': 'gridProperties.frozenRowCount'
                }
            })

            body = {
                'requests': requests_list
            }

            response = requests.post(url, headers=headers_dict, json=body)

            if response.status_code != 200:
                self.logger.warn(f"Failed to apply table formatting: {response.status_code} - {response.text}")
            else:
                self.logger.info(f"Successfully applied table formatting to {sheet_name}")

        except Exception as e:
            self.logger.warn(f"Error applying table formatting: {str(e)}")

    def _add_new_columns(self, access_token, spreadsheet_id, sheet_name, existing_headers, new_headers, value_input_option):
        """Add new column headers to the spreadsheet."""
        # Append new headers to the first row
        start_col = len(existing_headers)
        start_col_letter = self._column_letter(start_col + 1)  # +1 because columns are 1-indexed
        end_col_letter = self._column_letter(start_col + len(new_headers))

        range_notation = f"{sheet_name}!{start_col_letter}1:{end_col_letter}1"
        update_url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values/{range_notation}"

        headers_dict = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }

        body = {
            'values': [new_headers],
            'majorDimension': 'ROWS'  # Write headers as a single row
        }

        params = {
            'valueInputOption': value_input_option
        }

        response = requests.put(update_url, headers=headers_dict, json=body, params=params)

        if response.status_code != 200:
            raise Exception(f"Failed to add new columns: {response.status_code} - {response.text}")

    def _update_rows(self, access_token, spreadsheet_id, sheet_name, headers, rows, identifier_fields, value_input_option, header_mismatch_strategy):
        """Update existing rows based on identifier fields."""
        # Get existing data
        existing_data = self._get_sheet_data(access_token, spreadsheet_id, sheet_name)

        if not existing_data:
            raise Exception("Cannot UPDATE: Sheet is empty. Use APPEND or UPSERT mode instead.")

        # Assume first row is headers
        existing_headers = existing_data[0]
        existing_rows = existing_data[1:]

        # Check for header mismatches and handle according to strategy
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
                # Add new headers to the spreadsheet
                new_headers = sorted(unrecognized_headers)
                self._add_new_columns(access_token, spreadsheet_id, sheet_name, existing_headers, new_headers, value_input_option)
                # Re-fetch the sheet data to get the updated header row with correct column positions
                updated_data = self._get_sheet_data(access_token, spreadsheet_id, sheet_name)
                existing_headers = updated_data[0]  # Get the updated header row
                existing_rows = updated_data[1:]    # Update existing rows as well

        # Find identifier column indices
        identifier_indices = []
        for field in identifier_fields:
            if field not in existing_headers:
                raise Exception(f"Identifier field '{field}' not found in sheet headers")
            identifier_indices.append(existing_headers.index(field))

        # Build lookup map for existing rows
        existing_map = {}
        for row_idx, row in enumerate(existing_rows):
            # Create composite key from identifier fields
            key = tuple(row[i] if i < len(row) else '' for i in identifier_indices)
            existing_map[key] = row_idx + 2  # +2 because row 1 is headers, and sheets are 1-indexed

        # Update rows
        updates = []
        rows_updated = 0

        for row in rows:
            # Create row as dict for easier mapping
            row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}

            # Get identifier values
            key = tuple(row_dict.get(field, '') for field in identifier_fields)

            if key in existing_map:
                row_number = existing_map[key]

                # Map new row values to existing header order
                updated_row = []
                for header in existing_headers:
                    if header_mismatch_strategy == "IGNORE_FIELDS" and header in unrecognized_headers:
                        # Skip unrecognized headers when IGNORE_FIELDS strategy
                        continue
                    updated_row.append(row_dict.get(header, ''))

                range_notation = f"{sheet_name}!A{row_number}:{self._column_letter(len(existing_headers))}{row_number}"
                updates.append({
                    'range': range_notation,
                    'values': [updated_row]
                })
                rows_updated += 1
            else:
                self.logger.warn(f"Row with identifiers {key} not found in sheet, skipping")

        if updates:
            self._batch_update(access_token, spreadsheet_id, updates, value_input_option)

        return rows_updated

    def _upsert_rows(self, access_token, spreadsheet_id, sheet_name, headers, rows, identifier_fields, include_header, value_input_option, header_mismatch_strategy):
        """Update existing rows or insert new ones."""
        # Get existing data
        existing_data = self._get_sheet_data(access_token, spreadsheet_id, sheet_name)

        if not existing_data:
            # Sheet is empty, treat as REPLACE
            return self._replace_all(access_token, spreadsheet_id, sheet_name, headers, rows, "A1", include_header, value_input_option, False)

        # Assume first row is headers
        existing_headers = existing_data[0]
        existing_rows = existing_data[1:]

        # Check for header mismatches and handle according to strategy
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
                # Add new headers to the spreadsheet
                new_headers = sorted(unrecognized_headers)
                self._add_new_columns(access_token, spreadsheet_id, sheet_name, existing_headers, new_headers, value_input_option)
                # Re-fetch the sheet data to get the updated header row with correct column positions
                updated_data = self._get_sheet_data(access_token, spreadsheet_id, sheet_name)
                existing_headers = updated_data[0]  # Get the updated header row
                existing_rows = updated_data[1:]    # Update existing rows as well

        # Find identifier column indices
        identifier_indices = []
        for field in identifier_fields:
            if field not in existing_headers:
                raise Exception(f"Identifier field '{field}' not found in sheet headers")
            identifier_indices.append(existing_headers.index(field))

        # Build lookup map for existing rows
        existing_map = {}
        for row_idx, row in enumerate(existing_rows):
            key = tuple(row[i] if i < len(row) else '' for i in identifier_indices)
            existing_map[key] = row_idx + 2

        # Separate updates and inserts
        updates = []
        new_rows = []
        rows_updated = 0
        rows_inserted = 0

        for row in rows:
            row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
            key = tuple(row_dict.get(field, '') for field in identifier_fields)

            if key in existing_map:
                # Update existing row
                row_number = existing_map[key]

                # Map new row values to existing header order
                updated_row = []
                for header in existing_headers:
                    if header_mismatch_strategy == "IGNORE_FIELDS" and header in unrecognized_headers:
                        # Skip unrecognized headers when IGNORE_FIELDS strategy
                        continue
                    updated_row.append(row_dict.get(header, ''))

                range_notation = f"{sheet_name}!A{row_number}:{self._column_letter(len(existing_headers))}{row_number}"
                updates.append({
                    'range': range_notation,
                    'values': [updated_row]
                })
                rows_updated += 1
            else:
                # Insert new row
                new_rows.append(row)
                rows_inserted += 1

        # Execute updates
        if updates:
            self._batch_update(access_token, spreadsheet_id, updates, value_input_option)

        # Execute inserts - need to remap new_rows to match existing spreadsheet column order
        if new_rows:
            # Remap new rows to match existing header order
            remapped_rows = []
            for row in new_rows:
                row_dict = {headers[i]: row[i] if i < len(row) else '' for i in range(len(headers))}
                remapped_row = [row_dict.get(header, '') for header in existing_headers]
                remapped_rows.append(remapped_row)

            # Append using existing_headers order, not incoming headers order
            self._append_rows(access_token, spreadsheet_id, sheet_name, existing_headers, remapped_rows, False, value_input_option, False)

        self.logger.info(f"UPSERT completed: {rows_updated} updated, {rows_inserted} inserted")
        return rows_updated + rows_inserted

    def _batch_update(self, access_token, spreadsheet_id, updates, value_input_option):
        """Execute batch update of multiple ranges."""
        url = f"https://sheets.googleapis.com/v4/spreadsheets/{spreadsheet_id}/values:batchUpdate"
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }

        body = {
            'valueInputOption': value_input_option,
            'data': updates
        }

        response = requests.post(url, headers=headers, json=body)

        if response.status_code != 200:
            raise Exception(f"Failed to batch update: {response.status_code} - {response.text}")

    def _column_letter(self, col_num):
        """Convert column number to letter (1 -> A, 27 -> AA, etc.)."""
        result = ""
        while col_num > 0:
            col_num -= 1
            result = chr(col_num % 26 + ord('A')) + result
            col_num //= 26
        return result
