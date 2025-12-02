import json
import base64
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

class FetchGMailMessage(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Fetches the full content of a specific Gmail message by ID.
        Input: A FlowFile containing the 'id' of the message (e.g., from ListGMailInbox).
        Output: The full email content (JSON metadata + Body, or Raw RFC822).
        """
        tags = ['google', 'gmail', 'email', 'fetch', 'python']
        dependencies = ['google-api-python-client==2.187.0', 'google-auth-oauthlib==1.2.3']

    def __init__(self, **kwargs):
        self.service = None

    TOKEN_FILE = PropertyDescriptor(
        name="Token File Path",
        description="Path to the JSON file containing the OAuth2 tokens.",
        required=True,
        validators=[StandardValidators.FILE_EXISTS_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    MESSAGE_ID = PropertyDescriptor(
        name="Message ID",
        description="The ID of the message to fetch. Can be an attribute expression.",
        required=True,
        default_value="${id}",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    OUTPUT_FORMAT = PropertyDescriptor(
        name="Output Format",
        description="Format of the output content.",
        required=True,
        default_value="JSON",
        allowable_values=["JSON", "RAW"]
    )

    MARK_READ = PropertyDescriptor(
        name="Mark as Read",
        description="If true, removes the UNREAD label from the message.",
        required=True,
        default_value="false",
        allowable_values=["true", "false"]
    )

    def getPropertyDescriptors(self):
        return [self.TOKEN_FILE, self.MESSAGE_ID, self.OUTPUT_FORMAT, self.MARK_READ]

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
            self.onScheduled(context)
            if self.service is None:
                return FlowFileTransformResult(relationship="failure")

        try:
            msg_id = context.getProperty(self.MESSAGE_ID).evaluateAttributeExpressions(flowFile).getValue()
            output_format = context.getProperty(self.OUTPUT_FORMAT).getValue()
            mark_read = context.getProperty(self.MARK_READ).asBoolean()

            # If msg_id is missing or empty, fail
            if not msg_id:
                self.logger.error("Message ID is empty")
                return FlowFileTransformResult(relationship="failure")

            fetch_format = 'raw' if output_format == 'RAW' else 'full'
            
            self.logger.info(f"Fetching message {msg_id} format={fetch_format}")
            
            message_details = self.service.users().messages().get(
                userId='me', 
                id=msg_id, 
                format=fetch_format
            ).execute()

            content = ""
            attributes = {
                "gmail.id": msg_id,
                "gmail.threadId": message_details.get('threadId', '')
            }

            if output_format == "RAW":
                raw_data = message_details.get('raw', '')
                content = base64.urlsafe_b64decode(raw_data)
                attributes["mime.type"] = "message/rfc822"
            else:
                # JSON Format
                content = json.dumps(message_details, indent=2)
                attributes["mime.type"] = "application/json"
                
                # Add convenient attributes
                payload = message_details.get('payload', {})
                headers = payload.get('headers', [])
                for h in headers:
                    name = h.get('name').lower()
                    if name in ['subject', 'from', 'to', 'date']:
                        attributes[f"email.{name}"] = h.get('value')
                if 'snippet' in message_details:
                    attributes["email.snippet"] = message_details['snippet']

            # Mark as read if requested
            if mark_read:
                self.service.users().messages().modify(
                    userId='me', 
                    id=msg_id, 
                    body={'removeLabelIds': ['UNREAD']}
                ).execute()

            return FlowFileTransformResult(
                relationship="success",
                contents=content,
                attributes=attributes
            )

        except Exception as e:
            self.logger.error(f"Failed to fetch message {msg_id}: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
