import json
import logging
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

# Attempt to import MSAL
try:
    import msal
except ImportError:
    msal = None

class MicrosoftOAuthManager(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """Helper processor to manage Microsoft Graph OAuth 2.0 Authorization Code Flow.
        It handles two modes based on input attributes (typically from HandleHttpRequest):
        1. GENERATE_URL: Generates the Microsoft Authorization URL to redirect the user to.
        2. EXCHANGE_CODE: Exchanges the authorization code (received in callback) for access/refresh tokens.
        
        The output content (on success) is a JSON object containing the tokens AND the client configuration, 
        suitable for use by the GetMicrosoftMail processor.
        """
        tags = ['microsoft', 'graph', 'oauth', 'auth', 'token', 'helper']
        dependencies = ['msal', 'requests']

    def __init__(self, **kwargs):
        pass

    # Properties
    CLIENT_ID = PropertyDescriptor(
        name="Client ID",
        description="Application (client) ID assigned by the Microsoft Entra admin center.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    CLIENT_SECRET = PropertyDescriptor(
        name="Client Secret",
        description="Application (client) Secret created in the Microsoft Entra admin center.",
        required=True,
        sensitive=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    TENANT_ID = PropertyDescriptor(
        name="Tenant ID",
        description="Directory (tenant) ID. Use 'common', 'organizations', or 'consumers' for multi-tenant apps.",
        required=True,
        default_value="common",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    REDIRECT_URI = PropertyDescriptor(
        name="Redirect URI",
        description="The URI where Microsoft will redirect back to. Must match the configuration in Entra ID.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    SCOPES = PropertyDescriptor(
        name="Scopes",
        description="Comma-separated list of OAuth scopes to request (e.g., 'User.Read,Mail.Read').",
        required=True,
        default_value="User.Read,Mail.ReadWrite",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [self.CLIENT_ID, self.CLIENT_SECRET, self.TENANT_ID, self.REDIRECT_URI, self.SCOPES]

    def transform(self, context, flowFile):
        if msal is None:
             self.logger.error("MSAL library not found. Please ensure requirements.txt is installed.")
             return FlowFileTransformResult(relationship="failure")

        client_id = context.getProperty(self.CLIENT_ID).evaluateAttributeExpressions().getValue()
        client_secret = context.getProperty(self.CLIENT_SECRET).evaluateAttributeExpressions().getValue()
        tenant_id = context.getProperty(self.TENANT_ID).evaluateAttributeExpressions().getValue()
        redirect_uri = context.getProperty(self.REDIRECT_URI).evaluateAttributeExpressions(flowFile).getValue()
        scopes_str = context.getProperty(self.SCOPES).getValue()
        scopes = [s.strip() for s in scopes_str.split(',') if s.strip()]

        authority = f"https://login.microsoftonline.com/{tenant_id}"

        try:
            app = msal.ConfidentialClientApplication(
                client_id,
                authority=authority,
                client_credential=client_secret,
            )

            http_query_param_code = flowFile.getAttribute("http.query.param.code")

            # MODE 1: EXCHANGE CODE (Callback)
            if http_query_param_code:
                self.logger.info(f"Exchanging code for token. Redirect URI: {redirect_uri}")
                
                result = app.acquire_token_by_authorization_code(
                    http_query_param_code,
                    scopes=scopes,
                    redirect_uri=redirect_uri
                )

                if "error" in result:
                    self.logger.error(f"Failed to exchange code: {result.get('error_description')}")
                    return FlowFileTransformResult(relationship="failure")
                
                # Success
                # We wrap the result in a structure that preserves the client config,
                # so GetMicrosoftMail can refresh it later without needing the user to re-enter credentials.
                output_data = {
                    "configuration": {
                        "client_id": client_id,
                        "client_secret": client_secret,
                        "tenant_id": tenant_id,
                        "scopes": scopes
                    },
                    "token_data": result
                }

                attributes = {
                    "oauth.status": "success",
                    "mime.type": "application/json"
                }

                return FlowFileTransformResult(
                    relationship="success",
                    attributes=attributes,
                    contents=json.dumps(output_data, indent=2)
                )

            # MODE 2: GENERATE URL (Login request)
            else:
                self.logger.info(f"Generating Authorization URL. Redirect URI: {redirect_uri}")
                
                auth_url = app.get_authorization_request_url(
                    scopes,
                    redirect_uri=redirect_uri
                )

                attributes = {
                    "oauth.url": auth_url,
                    "oauth.status": "redirect"
                }

                return FlowFileTransformResult(
                    relationship="success",
                    attributes=attributes,
                    contents=auth_url
                )

        except Exception as e:
            self.logger.error(f"Unexpected error in MicrosoftOAuthManager: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
