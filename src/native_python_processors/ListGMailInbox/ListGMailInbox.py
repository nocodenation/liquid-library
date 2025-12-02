import json
import os
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

try:
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build
    from google.auth.transport.requests import Request
except ImportError:
    Credentials = None
    build = None
    Request = None

class ListGMailInbox(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Lists emails from a Gmail account based on a search query.
        Input: Any FlowFile (trigger).
        Output: 
          - 'Individual' mode: One FlowFile per email found, containing metadata (Snippet, Subject, From, Date).
          - 'List' mode: Single FlowFile containing a JSON array of all emails found.
        Does not fetch the full email body/payload. Use 'FetchGoogleMail' (to be created) for full content."""
        tags = ['google', 'gmail', 'email', 'list', 'python']

    def __init__(self, **kwargs):
        self.service = None

    TOKEN_FILE = PropertyDescriptor(
        name="Token File Path",
        description="Path to the JSON file containing the OAuth2 tokens.",
        required=True,
        validators=[StandardValidators.FILE_EXISTS_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    QUERY = PropertyDescriptor(
        name="Search Query",
        description="Gmail search query (e.g., 'is:unread', 'from:example.com').",
        required=True,
        default_value="is:unread",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    MAX_RESULTS = PropertyDescriptor(
        name="Max Results",
        description="Maximum number of emails to list. Use 0 for no limit (process all matching).",
        required=True,
        default_value="50",
        validators=[StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [self.TOKEN_FILE, self.QUERY, self.MAX_RESULTS]

    def onScheduled(self, context):
        if Credentials is None:
            self.logger.error("Google libraries not found.")
            return

        token_path = context.getProperty(self.TOKEN_FILE).evaluateAttributeExpressions().getValue()
        
        try:
            self.logger.info(f"Loading credentials from {token_path}")
            self.creds = Credentials.from_authorized_user_file(token_path, ['https://www.googleapis.com/auth/gmail.modify'])
            
            if self.creds.expired and self.creds.refresh_token:
                self.creds.refresh(Request())

            self.service = build('gmail', 'v1', credentials=self.creds)
            
        except Exception as e:
            self.logger.error(f"Failed to initialize Gmail service: {str(e)}")
            self.service = None

    def transform(self, context, flowFile):
        if self.service is None:
            # Try re-init
            self.onScheduled(context)
            if self.service is None:
                return FlowFileTransformResult(relationship="failure")

        try:
            query = context.getProperty(self.QUERY).evaluateAttributeExpressions(flowFile).getValue()
            max_results = int(context.getProperty(self.MAX_RESULTS).getValue())

            self.logger.info(f"Listing messages query='{query}' max={max_results}")

            messages = []
            next_page_token = None
            
            # Fetch Loop
            while True:
                # Determine batch size for API call (max 500 allowed by Gmail API usually)
                batch_limit = 500
                if max_results > 0:
                    remaining = max_results - len(messages)
                    if remaining <= 0:
                        break
                    if remaining < 500:
                        batch_limit = remaining

                results = self.service.users().messages().list(
                    userId='me', 
                    q=query, 
                    maxResults=batch_limit, 
                    pageToken=next_page_token
                ).execute()
                
                batch = results.get('messages', [])
                messages.extend(batch)
                
                next_page_token = results.get('nextPageToken')
                if not next_page_token:
                    break

            if not messages:
                # No messages found
                # We pass the original flowfile to success but maybe with an attribute saying count=0? 
                # Or we can just return a "List" of empty json.
                # If Individual: No output flowfiles generated means the Input FlowFile is consumed and nothing is output?
                # Standard Transform usually expects 1 result or list of results.
                # If we return empty list [], the input flowfile is removed and nothing is output.
                # Let's assume we just pass the input through or drop it? 
                # Usually "List" processors output nothing if nothing found, OR an empty JSON list.
                
                if output_mode == "List":
                    return FlowFileTransformResult(
                        relationship="success",
                        contents="[]",
                        attributes={"gmail.count": "0"}
                    )
                else:
                    # Individual mode: No emails -> No output. 
                    # Return empty list implies input is successfully processed but produced 0 outputs.
                    return []

            self.logger.info(f"Found {len(messages)} messages. Fetching metadata...")

            # Now we need to fetch details (Snippet + Headers) for each message.
            # messages.list only gives ID and ThreadID.
            # To be efficient, we could use batch requests, but for simplicity we loop.
            # 'format=metadata' gets headers but no body.
            
            processed_emails = []
            
            for msg in messages:
                msg_id = msg['id']
                try:
                    details = self.service.users().messages().get(
                        userId='me', 
                        id=msg_id, 
                        format='metadata',
                        metadataHeaders=['Subject', 'From', 'To', 'Date']
                    ).execute()
                    
                    email_data = {
                        "id": msg_id,
                        "threadId": msg['threadId'],
                        "snippet": details.get('snippet', ''),
                        "historyId": details.get('historyId'),
                        "internalDate": details.get('internalDate')
                    }
                    
                    # Parse headers
                    headers = details.get('payload', {}).get('headers', [])
                    for h in headers:
                        email_data[h['name']] = h['value']
                        
                    processed_emails.append(email_data)
                    
                except Exception as e:
                    self.logger.error(f"Failed to fetch details for message {msg_id}: {str(e)}")
                    # Continue with others?
                    continue

            # Generate Output
            # Always return List format as FlowFileTransform only supports single output
            return FlowFileTransformResult(
                relationship="success",
                contents=json.dumps(processed_emails, indent=2),
                attributes={"gmail.count": str(len(processed_emails)), "mime.type": "application/json"}
            )

        except Exception as e:
            self.logger.error(f"Error listing inbox: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
