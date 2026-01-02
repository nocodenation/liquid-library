import json
import base64
import urllib.request
import urllib.parse
import urllib.error
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

class ReceiveFromNodeJSApp(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '1.0.0-SNAPSHOT'
        description = '''Receives HTTP requests from Node.js applications via the NodeJSAppAPIGateway controller service.
        This processor polls the gateway's internal API for requests matching the configured endpoint pattern.'''
        tags = ['nodejs', 'http', 'gateway', 'api', 'rest', 'python']

    def __init__(self, **kwargs):
        self.gateway_url = None
        self.endpoint_pattern = None
        self.poll_timeout = 30

    GATEWAY_URL = PropertyDescriptor(
        name="Gateway URL",
        description="Base URL of the NodeJSAppAPIGateway service (e.g., http://localhost:5050)",
        required=True,
        default_value="http://localhost:5050",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    ENDPOINT_PATTERN = PropertyDescriptor(
        name="Endpoint Pattern",
        description="The endpoint pattern to poll for (e.g., /api/events, /users/:userId). Must match a pattern registered with the gateway.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.ENVIRONMENT
    )

    POLL_TIMEOUT = PropertyDescriptor(
        name="Poll Timeout",
        description="Timeout in seconds for long-polling requests to the gateway",
        required=True,
        default_value="30",
        validators=[StandardValidators.POSITIVE_INTEGER_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [self.GATEWAY_URL, self.ENDPOINT_PATTERN, self.POLL_TIMEOUT]

    def onScheduled(self, context):
        """Called when the processor is scheduled to run."""
        self.gateway_url = context.getProperty(self.GATEWAY_URL).evaluateAttributeExpressions().getValue()
        self.endpoint_pattern = context.getProperty(self.ENDPOINT_PATTERN).evaluateAttributeExpressions().getValue()
        self.poll_timeout = int(context.getProperty(self.POLL_TIMEOUT).getValue())

        # Remove trailing slash from gateway URL if present
        if self.gateway_url.endswith('/'):
            self.gateway_url = self.gateway_url[:-1]

    def transform(self, context, flowFile):
        """
        Polls the gateway for the next request matching the endpoint pattern.
        Creates a new FlowFile with the request data.
        """
        try:
            # Build polling URL
            # URL encode the endpoint pattern
            encoded_pattern = urllib.parse.quote(self.endpoint_pattern, safe='')
            poll_url = f"{self.gateway_url}/_internal/poll/{encoded_pattern}"

            self.logger.debug(f"Polling gateway at: {poll_url}")

            # Create request with timeout
            req = urllib.request.Request(poll_url, method='GET')
            req.add_header('Content-Type', 'application/json')

            # Poll with timeout
            try:
                with urllib.request.urlopen(req, timeout=self.poll_timeout + 5) as response:
                    if response.status == 204:
                        # No content - timeout, no request available
                        self.logger.debug("Poll timeout - no requests available")
                        return FlowFileTransformResult(relationship="success")

                    if response.status != 200:
                        self.logger.error(f"Gateway returned status {response.status}")
                        return FlowFileTransformResult(relationship="failure")

                    # Read and parse response
                    response_data = response.read().decode('utf-8')
                    request_json = json.loads(response_data)

            except urllib.error.HTTPError as e:
                if e.code == 404:
                    self.logger.error(f"Endpoint pattern '{self.endpoint_pattern}' not registered with gateway")
                    return FlowFileTransformResult(relationship="failure")
                else:
                    self.logger.error(f"HTTP error polling gateway: {e.code} - {e.reason}")
                    return FlowFileTransformResult(relationship="failure")

            except urllib.error.URLError as e:
                self.logger.error(f"URL error polling gateway: {str(e.reason)}")
                return FlowFileTransformResult(relationship="failure")

            except TimeoutError:
                self.logger.debug("Poll timeout - no requests available")
                return FlowFileTransformResult(relationship="success")

            # Extract request data
            method = request_json.get('method', '')
            path = request_json.get('path', '')
            headers = request_json.get('headers', {})
            query_params = request_json.get('queryParameters', {})
            path_params = request_json.get('pathParameters', {})
            content_type = request_json.get('contentType', '')
            body_base64 = request_json.get('body', '')
            body_text = request_json.get('bodyText', '')
            client_address = request_json.get('clientAddress', '')
            timestamp = request_json.get('timestamp', '')

            self.logger.info(f"Received request: {method} {path}")

            # Decode body from base64
            if body_base64:
                try:
                    body_bytes = base64.b64decode(body_base64)
                except Exception as e:
                    self.logger.error(f"Failed to decode body: {str(e)}")
                    body_bytes = body_text.encode('utf-8')
            else:
                body_bytes = b''

            # Build attributes
            attributes = {
                'http.method': method,
                'http.path': path,
                'http.client.address': client_address,
                'http.timestamp': timestamp
            }

            # Add query parameters
            for key, value in query_params.items():
                attributes[f'http.query.{key}'] = str(value)

            # Add path parameters
            for key, value in path_params.items():
                attributes[f'http.path.{key}'] = str(value)

            # Add headers
            for key, value in headers.items():
                attributes[f'http.header.{key.lower()}'] = str(value)

            # Create FlowFile with body content and attributes
            return FlowFileTransformResult(
                relationship="success",
                contents=body_bytes,
                attributes=attributes
            )

        except Exception as e:
            self.logger.error(f"Error processing request: {str(e)}")
            import traceback
            self.logger.error(traceback.format_exc())
            return FlowFileTransformResult(relationship="failure")
