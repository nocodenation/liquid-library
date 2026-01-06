# GAT-17: Add no_data Relationship to Python Processor - COMPLETE SPECIFICATION

**Generated:** 2026-01-06
**Status:** Breaking Change for v1.0.0
**File:** `liquid-library/src/native_python_processors/ReceiveFromNodeJSApp/ReceiveFromNodeJSApp.py`

---

## Problem Statement

Currently, when the Python processor times out (no requests available), it routes to the `success` relationship:

```python
except TimeoutError:
    self.logger.debug("Poll timeout - no requests available")
    return FlowFileTransformResult(relationship="success")  # ← Semantically incorrect
```

**Why this is wrong:**
- "success" implies something succeeded, but nothing actually happened
- Makes it impossible to distinguish between "processed a request" and "no request available"
- Can cause confusion in flow design and metrics

---

## Solution: Add `no_data` Relationship

### Implementation Changes

#### 1. Add Relationship Class Import (Line 6)

**Current:**
```python
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope
```

**Updated:**
```python
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope
from nifiapi.relationship import Relationship
```

#### 2. Add getRelationships() Method (After line 50)

**Current structure:**
```python
def getPropertyDescriptors(self):
    return [self.GATEWAY_URL, self.ENDPOINT_PATTERN, self.POLL_TIMEOUT]

def onScheduled(self, context):  # Line 52
    """Called when the processor is scheduled to run."""
```

**Updated structure:**
```python
def getPropertyDescriptors(self):
    return [self.GATEWAY_URL, self.ENDPOINT_PATTERN, self.POLL_TIMEOUT]

def getRelationships(self):
    """Define output relationships for this processor."""
    return [
        Relationship(
            name="success",
            description="Successfully received and parsed an HTTP request from the gateway"
        ),
        Relationship(
            name="failure",
            description="Failed to retrieve or parse request due to an error (network error, invalid JSON, etc.)"
        ),
        Relationship(
            name="no_data",
            description="No data available from gateway within the poll timeout period. " +
                       "This is a normal condition when no requests are pending."
        )
    ]

def onScheduled(self, context):  # Line 52
    """Called when the processor is scheduled to run."""
```

#### 3. Update TimeoutError Handler (Line 107-109)

**Current:**
```python
except TimeoutError:
    self.logger.debug("Poll timeout - no requests available")
    return FlowFileTransformResult(relationship="success")
```

**Updated:**
```python
except TimeoutError:
    self.logger.debug("Poll timeout - no requests available")
    return FlowFileTransformResult(relationship="no_data")
```

#### 4. Update 204 No Content Handler (Line 82-85)

**Current:**
```python
if response.status == 204:
    # No content - timeout, no request available
    self.logger.debug("Poll timeout - no requests available")
    return FlowFileTransformResult(relationship="success")
```

**Updated:**
```python
if response.status == 204:
    # No content - timeout, no request available
    self.logger.debug("Poll timeout - no requests available")
    return FlowFileTransformResult(relationship="no_data")
```

---

## Complete Modified File

Here's the complete updated section showing all changes:

```python
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope
from nifiapi.relationship import Relationship  # ← ADD THIS IMPORT

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

    def getRelationships(self):
        """Define output relationships for this processor."""
        return [
            Relationship(
                name="success",
                description="Successfully received and parsed an HTTP request from the gateway"
            ),
            Relationship(
                name="failure",
                description="Failed to retrieve or parse request due to an error (network error, invalid JSON, etc.)"
            ),
            Relationship(
                name="no_data",
                description="No data available from gateway within the poll timeout period. " +
                           "This is a normal condition when no requests are pending."
            )
        ]

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
                        return FlowFileTransformResult(relationship="no_data")  # ← UPDATED

                    if response.status != 200:
                        # Add error attributes as per GAT-12-16
                        error_attrs = {
                            'error.message': f"Gateway returned unexpected status: {response.status}",
                            'error.type': 'UnexpectedStatus',
                            'error.http.code': str(response.status)
                        }
                        self.logger.error(error_attrs['error.message'])
                        return FlowFileTransformResult(relationship="failure", attributes=error_attrs)

                    # Read and parse response
                    response_data = response.read().decode('utf-8')

                    try:
                        request_json = json.loads(response_data)
                    except json.JSONDecodeError as e:
                        error_attrs = {
                            'error.message': f"Invalid JSON response from gateway: {str(e)}",
                            'error.type': 'JSONDecodeError',
                            'error.response': response_data[:500] if len(response_data) <= 500 else response_data[:500] + "..."
                        }
                        self.logger.error(error_attrs['error.message'])
                        return FlowFileTransformResult(relationship="failure", attributes=error_attrs)

            except urllib.error.HTTPError as e:
                if e.code == 404:
                    error_attrs = {
                        'error.message': f"Endpoint pattern '{self.endpoint_pattern}' not registered with gateway",
                        'error.type': 'EndpointNotFound',
                        'error.http.code': '404'
                    }
                    self.logger.error(error_attrs['error.message'])
                    return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
                else:
                    error_attrs = {
                        'error.message': f"HTTP error polling gateway: {e.code} - {e.reason}",
                        'error.type': 'HTTPError',
                        'error.http.code': str(e.code)
                    }
                    self.logger.error(error_attrs['error.message'])
                    return FlowFileTransformResult(relationship="failure", attributes=error_attrs)

            except urllib.error.URLError as e:
                error_attrs = {
                    'error.message': f"URL error polling gateway: {str(e.reason)}",
                    'error.type': 'URLError',
                    'error.gateway.url': poll_url
                }
                self.logger.error(error_attrs['error.message'])
                return FlowFileTransformResult(relationship="failure", attributes=error_attrs)

            except TimeoutError:
                self.logger.debug("Poll timeout - no requests available")
                return FlowFileTransformResult(relationship="no_data")  # ← UPDATED

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
            error_attrs = {
                'error.message': f"Unexpected error processing request: {str(e)}",
                'error.type': type(e).__name__,
                'error.traceback': traceback.format_exc()[:1000]  # First 1000 chars
            }
            self.logger.error(error_attrs['error.message'])
            self.logger.error(traceback.format_exc())
            return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
```

---

## Breaking Change Documentation

### What's Breaking

**Old Behavior:**
- 2 relationships: `success`, `failure`
- Timeout routes to `success`

**New Behavior:**
- 3 relationships: `success`, `failure`, `no_data`
- Timeout routes to `no_data`

### Migration Guide for Users

When upgrading to v1.0.0, users with existing flows using this processor will need to:

1. **Update Flow Connections:**
   - Add a new outbound connection from the processor's `no_data` relationship
   - Options:
     - Route `no_data` back to the processor (auto-retry)
     - Route `no_data` to a termination processor (ignore)
     - Route `no_data` to a logging processor (monitor)

2. **Recommended Flow Pattern:**
   ```
   ReceiveFromNodeJSApp
   ├─ success → [Process Request Flow]
   ├─ failure → [Error Handling]
   └─ no_data → [Auto-retry or Log]
   ```

3. **Backward Compatible Option (not recommended):**
   If you want the old behavior temporarily, route both `success` and `no_data` to the same downstream processor.

### Release Notes Entry

```markdown
### Breaking Changes

- **ReceiveFromNodeJSApp (Python)**: Added `no_data` relationship for timeout scenarios
  - **Action Required**: Update flows to add connection from new `no_data` relationship
  - **Reason**: Improves semantic clarity - timeouts are not successes
  - **Migration**: Route `no_data` to auto-retry, log, or terminate based on your use case
```

---

## Testing Checklist

- [ ] Import Relationship class works
- [ ] getRelationships() returns 3 relationships
- [ ] Timeout (204 response) routes to `no_data`
- [ ] TimeoutError exception routes to `no_data`
- [ ] Successful request routes to `success`
- [ ] Error conditions route to `failure` with attributes
- [ ] Flow validation catches missing `no_data` connection
- [ ] Existing flows show validation warning about new relationship

---

*Complete specification ready for implementation in v1.0.0*