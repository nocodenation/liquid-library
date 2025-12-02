import json
import os
import urllib.parse
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

# Try importing google libraries
try:
    from google_auth_oauthlib.flow import Flow
    from google.oauth2.credentials import Credentials
except ImportError:
    Flow = None
    Credentials = None

class GoogleOAuthManager(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Helper processor to manage Google OAuth 2.0 Authorization Code Flow.
        It handles two modes based on input attributes (typically from HandleHttpRequest):
        1. GENERATE_URL: Generates the Google Authorization URL to redirect the user to.
        2. EXCHANGE_CODE: Exchanges the authorization code (received in callback) for access/refresh tokens.
        """
        tags = ['google', 'oauth', 'auth', 'token', 'helper']
        dependencies = ['google-auth-oauthlib', 'requests']

    def __init__(self, **kwargs):
        pass

    # Properties
    CREDENTIALS_FILE = PropertyDescriptor(
        name="Credentials File",
        description="Path to the 'credentials.json' file downloaded from Google Cloud Console.",
        required=True,
        validators=[StandardValidators.FILE_EXISTS_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    REDIRECT_URI = PropertyDescriptor(
        name="Redirect URI",
        description="The URI where Google will redirect back to (e.g., http://localhost:8999/callback). Must match configuration in Google Console.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    SCOPES = PropertyDescriptor(
        name="Scopes",
        description="Comma-separated list of OAuth scopes to request.",
        required=True,
        default_value="https://www.googleapis.com/auth/gmail.modify",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [self.CREDENTIALS_FILE, self.REDIRECT_URI, self.SCOPES]

    def transform(self, context, flowFile):
        if Flow is None:
             self.logger.error("Google libraries not found. Please ensure requirements.txt is installed.")
             return FlowFileTransformResult(relationship="failure")

        credentials_path = context.getProperty(self.CREDENTIALS_FILE).evaluateAttributeExpressions().getValue()
        redirect_uri = context.getProperty(self.REDIRECT_URI).evaluateAttributeExpressions(flowFile).getValue()
        scopes_str = context.getProperty(self.SCOPES).getValue()
        scopes = [s.strip() for s in scopes_str.split(',') if s.strip()]

        # Determine mode based on attributes (usually set by HandleHttpRequest)
        # If we have a 'code' attribute or query param, we assume Exchange mode.
        # Otherwise we assume Generate URL mode.
        
        http_query_param_code = flowFile.getAttribute("http.query.param.code")
        
        # MODE 1: EXCHANGE CODE (Callback)
        if http_query_param_code:
            try:
                self.logger.info(f"Exchanging code for token. Redirect URI: {redirect_uri}")
                
                # Initialize Flow
                flow = Flow.from_client_secrets_file(
                    credentials_path,
                    scopes=scopes,
                    redirect_uri=redirect_uri)

                # Exchange the code for credentials
                # Note: fetch_token expects the full authorization response URL or the code.
                # If using 'code' argument directly is not supported by fetch_token in this version,
                # we might need to construct a fake authorization response URL or set code explicitly.
                # flow.fetch_token(code=code) is the standard way.
                
                flow.fetch_token(code=http_query_param_code)
                
                credentials = flow.credentials
                
                # Serialize credentials to JSON string
                token_json = credentials.to_json()
                
                attributes = {
                    "oauth.status": "success",
                    "mime.type": "application/json"
                }
                
                return FlowFileTransformResult(
                    relationship="success",
                    attributes=attributes,
                    contents=token_json
                )

            except Exception as e:
                self.logger.error(f"Failed to exchange token: {str(e)}")
                return FlowFileTransformResult(relationship="failure")

        # MODE 2: GENERATE URL (Login request)
        else:
            try:
                self.logger.info(f"Generating Authorization URL. Redirect URI: {redirect_uri}")
                
                flow = Flow.from_client_secrets_file(
                    credentials_path,
                    scopes=scopes,
                    redirect_uri=redirect_uri)

                # Generate URL
                authorization_url, state = flow.authorization_url(
                    access_type='offline',
                    include_granted_scopes='true',
                    prompt='consent') # Force consent to ensure we get a refresh token

                attributes = {
                    "oauth.url": authorization_url,
                    "oauth.state": state,
                    "oauth.status": "redirect"
                }

                return FlowFileTransformResult(
                    relationship="success",
                    attributes=attributes,
                    contents=authorization_url # Put URL in content too for debugging/easy access
                )

            except Exception as e:
                self.logger.error(f"Failed to generate authorization URL: {str(e)}")
                return FlowFileTransformResult(relationship="failure")
