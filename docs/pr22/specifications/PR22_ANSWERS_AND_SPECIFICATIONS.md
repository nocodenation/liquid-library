# PR #22 Implementation Specifications - COMPLETE

**Generated:** 2026-01-06
**Status:** Ready for Implementation

---

## GAT-2: SwaggerServlet Error Handling - ✅ RESOLVED

### Answer: YES, we can implement a logger!

**Pattern from GatewayServlet:** Pass the gateway service to the servlet constructor and use `gateway.getLogger()`

### Current SwaggerServlet Constructor:
```java
public SwaggerServlet(String openapiSpecUrl) {
    this.openapiSpecUrl = openapiSpecUrl;
}
```

### Updated Implementation:

**1. Modify Constructor:**
```java
private final StandardNodeJSAppAPIGateway gateway;  // Add field
private final String openapiSpecUrl;

public SwaggerServlet(StandardNodeJSAppAPIGateway gateway, String openapiSpecUrl) {
    this.gateway = gateway;
    this.openapiSpecUrl = openapiSpecUrl;
}
```

**2. Update serveIndexHtml() with error handling:**
```java
private void serveIndexHtml(HttpServletResponse resp) throws IOException {
    resp.setContentType("text/html");
    resp.setCharacterEncoding("UTF-8");

    try {
        // Load index.html template
        String html = loadResourceAsString("index.html");

        // Inject OpenAPI spec URL
        html = html.replace("{{OPENAPI_SPEC_URL}}", openapiSpecUrl);

        resp.getWriter().write(html);
        resp.setStatus(HttpServletResponse.SC_OK);
    } catch (IOException e) {
        gateway.getLogger().error("Failed to load Swagger UI: {}", e.getMessage());
        resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
            "Swagger UI unavailable: " + e.getMessage());
    }
}
```

**3. Update instantiation in StandardNodeJSAppAPIGateway.java line 489:**

**Current:**
```java
context.addServlet(new ServletHolder(new SwaggerServlet(openapiPath)), swaggerPath + "/*");
```

**Updated:**
```java
context.addServlet(new ServletHolder(new SwaggerServlet(this, openapiPath)), swaggerPath + "/*");
```

---

## GAT-3: SSL/TLS Implementation - ✅ COMPLETE SPECIFICATION

### SSL_CONTEXT_SERVICE PropertyDescriptor Location

**Found at lines 80-87 (COMMENTED OUT):**
```java
// TODO: Uncomment when nifi-ssl-context-service dependency is added to pom.xml
// public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
//         .name("SSL Context Service")
//         .description("SSL Context Service to use for HTTPS. If not specified, HTTP will be used. " +
//                 "IMPORTANT: Using HTTP (without SSL) is NOT recommended for production deployments as data will be transmitted in plaintext.")
//         .required(false)
//         .identifiesControllerService(SSLContextService.class)
//         .build();
```

### PROPERTY_DESCRIPTORS List Location

**Found at lines 176-193:**
```java
private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;

static {
    List<PropertyDescriptor> props = new ArrayList<>();
    props.add(GATEWAY_HOST);
    props.add(GATEWAY_PORT);
    // SSL_CONTEXT_SERVICE would go here (line 182)
    props.add(MAX_QUEUE_SIZE);
    props.add(MAX_REQUEST_SIZE);
    // ... rest
    PROPERTY_DESCRIPTORS = Collections.unmodifiableList(props);
}
```

### Complete SSL Implementation Steps

#### Step 1: Add Dependency to pom.xml

**File:** `liquid-library/src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-service/pom.xml`

**After line 30 (after nifi-api dependency), add:**
```xml
<!-- SSL Context Service for HTTPS support -->
<dependency>
    <groupId>org.apache.nifi</groupId>
    <artifactId>nifi-ssl-context-service-api</artifactId>
    <scope>provided</scope>
</dependency>
```

#### Step 2: Uncomment Imports (Lines 28-32)

**Change from:**
```java
// TODO: Add nifi-ssl-context-service dependency to pom.xml and uncomment:
// import org.apache.nifi.ssl.SSLContextService;
// import org.eclipse.jetty.server.ServerConnector;
// import org.eclipse.jetty.util.ssl.SslContextFactory;
// import javax.net.ssl.SSLContext;
```

**To:**
```java
import org.apache.nifi.ssl.SSLContextService;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import javax.net.ssl.SSLContext;
```

#### Step 3: Uncomment SSL_CONTEXT_SERVICE PropertyDescriptor (Lines 80-87)

**Change from:**
```java
// TODO: Uncomment when nifi-ssl-context-service dependency is added to pom.xml
// public static final PropertyDescriptor SSL_CONTEXT_SERVICE = ...
```

**To:**
```java
public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
        .name("SSL Context Service")
        .description("SSL Context Service to use for HTTPS. If not specified, HTTP will be used. " +
                "IMPORTANT: Using HTTP (without SSL) is NOT recommended for production deployments as data will be transmitted in plaintext.")
        .required(false)
        .identifiesControllerService(SSLContextService.class)
        .build();
```

#### Step 4: Add SSL_CONTEXT_SERVICE to PROPERTY_DESCRIPTORS

**In static initializer block at line 182, add:**
```java
static {
    List<PropertyDescriptor> props = new ArrayList<>();
    props.add(GATEWAY_HOST);
    props.add(GATEWAY_PORT);
    props.add(SSL_CONTEXT_SERVICE);  // ← ADD THIS LINE
    props.add(MAX_QUEUE_SIZE);
    props.add(MAX_REQUEST_SIZE);
    // ... rest
}
```

#### Step 5: Add SSL Context Field and Retrieve in onEnabled()

**After line 208, add field:**
```java
private String corsMaxAge;
private SSLContextService sslContextService;  // ← ADD THIS LINE

// Endpoint registry - maps pattern to handler and queue
```

**In onEnabled() method after line 242, add:**
```java
this.openapiPath = context.getProperty(OPENAPI_PATH).getValue();
this.sslContextService = context.getProperty(SSL_CONTEXT_SERVICE)
        .asControllerService(SSLContextService.class);  // ← ADD THIS

startServer();
```

#### Step 6: Replace startServer() Method (Lines 455-498)

**Current problematic implementation uses `new Server(address)` which conflicts with SSL approach.**

**COMPLETE REPLACEMENT for startServer() method:**

```java
private void startServer() throws Exception {
    // Create server
    server = new Server();

    // Configure connector (HTTP or HTTPS based on SSL configuration)
    ServerConnector connector;

    if (sslContextService != null) {
        // HTTPS mode
        try {
            SSLContext sslContext = sslContextService.createContext();
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setSslContext(sslContext);

            connector = new ServerConnector(server, sslContextFactory);
            getLogger().info("Gateway configured with HTTPS using SSL Context Service");
        } catch (Exception e) {
            getLogger().error("Failed to create SSL context, falling back to HTTP: {}", e.getMessage());
            throw new RuntimeException("SSL configuration failed: " + e.getMessage(), e);
        }
    } else {
        // HTTP mode
        connector = new ServerConnector(server);
        getLogger().warn("Gateway configured with HTTP only - SSL Context Service not specified. " +
                "This is NOT recommended for production deployments as data will be transmitted in plaintext.");
    }

    // Configure connector host and port
    connector.setHost(gatewayHost);
    connector.setPort(gatewayPort);
    server.addConnector(connector);

    // Create servlet context
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");

    // Register core servlets
    context.addServlet(new ServletHolder(new InternalApiServlet(this)), "/_internal/*");
    context.addServlet(new ServletHolder(new MetricsServlet(this)), "/_metrics");

    // Register Swagger UI servlets if enabled
    if (swaggerEnabled) {
        openapiGenerator = new OpenAPIGenerator(getGatewayUrl());
        context.addServlet(new ServletHolder(new OpenAPIServlet(openapiGenerator, this)), openapiPath);
        context.addServlet(new ServletHolder(new SwaggerServlet(this, openapiPath)), swaggerPath + "/*");
        getLogger().debug("Registered Swagger UI servlets: {} and {}", swaggerPath, openapiPath);
    }

    // Register Gateway servlet last to avoid catching Swagger paths
    context.addServlet(new ServletHolder(new GatewayServlet(this)), "/*");

    server.setHandler(context);
    server.start();
}
```

**Key Changes:**
1. Removed `new Server(address)` - incompatible with SSL
2. Use `ServerConnector` approach (works with both HTTP and HTTPS)
3. Proper error handling with fallback message
4. Updated SwaggerServlet instantiation to pass `this` reference

#### Step 7: Update getGatewayUrl() to Return HTTPS When SSL Enabled

**Current implementation at line 267-271:**
```java
@Override
public String getGatewayUrl() {
    // Return localhost if bound to 0.0.0.0, otherwise use the configured host
    String host = "0.0.0.0".equals(gatewayHost) ? "localhost" : gatewayHost;
    return "http://" + host + ":" + gatewayPort;
}
```

**Updated:**
```java
@Override
public String getGatewayUrl() {
    // Return localhost if bound to 0.0.0.0, otherwise use the configured host
    String host = "0.0.0.0".equals(gatewayHost) ? "localhost" : gatewayHost;
    String protocol = (sslContextService != null) ? "https" : "http";
    return protocol + "://" + host + ":" + gatewayPort;
}
```

---

## GAT-12-16: Python Error Handling - ✅ CLARIFIED

### Understanding the Question

You asked: *"I am not sure what you mean. Do you mean the form of an error that is put into an error attribute?"*

**My Concern Was:** The PR report shows adding error attributes to `flowFile`, but in the Python processor's `transform()` method, when an exception occurs, no output FlowFile has been created yet, so there's no `flowFile` object to add attributes to.

**Looking at the actual Python code more carefully:**

```python
def transform(self, context, flowFile):  # ← flowFile is the INPUT parameter
    try:
        # ... code that might fail ...

        # Only at the END do we create output FlowFile
        return FlowFileTransformResult(
            relationship="success",
            contents=body_bytes,
            attributes=attributes  # ← NEW attributes dict
        )
    except Exception as e:  # Line 162
        return FlowFileTransformResult(relationship="failure")  # ← No attributes!
```

### The Solution: Create Attributes Dict for Errors

For NiFi Python processors using `FlowFileTransform`, when returning failure, you can pass an `attributes` parameter with error details:

```python
except urllib.error.HTTPError as e:  # Line 95
    if e.code == 404:
        error_attrs = {
            'error.message': f"Endpoint pattern '{self.endpoint_pattern}' not registered with gateway",
            'error.type': 'EndpointNotFound',
            'error.code': '404'
        }
        self.logger.error(f"Endpoint pattern '{self.endpoint_pattern}' not registered with gateway")
        return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
    else:
        error_attrs = {
            'error.message': f"HTTP error polling gateway: {e.code} - {e.reason}",
            'error.type': 'HTTPError',
            'error.code': str(e.code)
        }
        self.logger.error(f"HTTP error polling gateway: {e.code} - {e.reason}")
        return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
```

### Complete Fix for All 5 Locations

**Line 89: requests.exceptions.RequestException → urllib.error.HTTPError (404)**
```python
except urllib.error.HTTPError as e:  # Line 95
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
```

**Line 98: After status 200 check (implicit JSONDecodeError location)**

Actually, looking at line 93, there's a `json.loads()` call that could fail:
```python
response_data = response.read().decode('utf-8')
request_json = json.loads(response_data)  # ← Could raise JSONDecodeError
```

Wrap this in try-catch:
```python
try:
    response_data = response.read().decode('utf-8')
    request_json = json.loads(response_data)
except json.JSONDecodeError as e:
    error_attrs = {
        'error.message': f"Invalid JSON response from gateway: {str(e)}",
        'error.type': 'JSONDecodeError',
        'error.response': response_data[:500] if len(response_data) <= 500 else response_data[:500] + "..."
    }
    self.logger.error(error_attrs['error.message'])
    return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
```

**Line 101: Non-200 status code**

The report mentions line 101 but actual code at line 87-89 has:
```python
if response.status != 200:
    self.logger.error(f"Gateway returned status {response.status}")
    return FlowFileTransformResult(relationship="failure")
```

Should be:
```python
if response.status != 200:
    error_attrs = {
        'error.message': f"Gateway returned unexpected status: {response.status}",
        'error.type': 'UnexpectedStatus',
        'error.http.code': str(response.status)
    }
    self.logger.error(error_attrs['error.message'])
    return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
```

**Line 105: urllib.error.URLError**
```python
except urllib.error.URLError as e:  # Line 103
    error_attrs = {
        'error.message': f"URL error polling gateway: {str(e.reason)}",
        'error.type': 'URLError',
        'error.gateway.url': poll_url
    }
    self.logger.error(error_attrs['error.message'])
    return FlowFileTransformResult(relationship="failure", attributes=error_attrs)
```

**Line 166: Generic Exception**
```python
except Exception as e:  # Line 162
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

## Summary of Changes

### GAT-2: SwaggerServlet (2 files)
1. **SwaggerServlet.java** - Add gateway field, update constructor, add error handling
2. **StandardNodeJSAppAPIGateway.java** line 489 - Update SwaggerServlet instantiation

### GAT-3: SSL/TLS Support (2 files)
1. **pom.xml** - Add nifi-ssl-context-service-api dependency
2. **StandardNodeJSAppAPIGateway.java** - 7 changes:
   - Uncomment imports (lines 28-32)
   - Uncomment SSL_CONTEXT_SERVICE PropertyDescriptor (lines 80-87)
   - Add to PROPERTY_DESCRIPTORS list (line 182)
   - Add sslContextService field (after line 208)
   - Retrieve SSL service in onEnabled() (after line 242)
   - Replace startServer() method completely (lines 455-498)
   - Update getGatewayUrl() to return https when SSL enabled (lines 267-271)

### GAT-12-16: Python Error Messages (1 file)
1. **ReceiveFromNodeJSApp.py** - Update 5 error handling locations with attributes

---

## Implementation Checklist

- [ ] GAT-2: Modify SwaggerServlet.java
- [ ] GAT-2: Update SwaggerServlet instantiation in StandardNodeJSAppAPIGateway.java
- [ ] GAT-3: Add SSL dependency to pom.xml
- [ ] GAT-3: Uncomment SSL imports
- [ ] GAT-3: Uncomment SSL_CONTEXT_SERVICE PropertyDescriptor
- [ ] GAT-3: Add SSL_CONTEXT_SERVICE to PROPERTY_DESCRIPTORS
- [ ] GAT-3: Add sslContextService field
- [ ] GAT-3: Retrieve SSL service in onEnabled()
- [ ] GAT-3: Replace startServer() method
- [ ] GAT-3: Update getGatewayUrl() method
- [ ] GAT-12-16: Add error attributes to all 5 exception handlers
- [ ] Test HTTP mode (no SSL configured)
- [ ] Test HTTPS mode (with SSL Context Service)
- [ ] Test error paths in Python processor

---

## GAT-7: RespondWithTimestamp Example Processor - ✅ COMPLETE

**Complete specification provided in:** [GAT-7_RespondWithTimestamp_COMPLETE_SPEC.md](GAT-7_RespondWithTimestamp_COMPLETE_SPEC.md)

### Summary

Full implementation of example processor demonstrating:
- Synchronous EndpointHandler usage (Java-only)
- No FlowFile creation - direct HTTP responses
- Complete with all imports, properties, lifecycle methods
- Educational comparison table with ReceiveFromNodeJSApp
- JSON response generation with error handling
- Configurable time zones and formatting

**File Location:** `liquid-library/src/java_extensions/nodejs-app-gateway/nodejs-app-gateway-processors/src/main/java/org/nocodenation/nifi/nodejsapp/gateway/RespondWithTimestamp.java`

**Key Features:**
- 220 lines of complete, ready-to-compile code
- 4 properties: Gateway Service, Endpoint Pattern, Include Formatted, Time Zone
- Proper @Tags, @CapabilityDescription, @SeeAlso annotations
- OnScheduled registration with error handling
- OnStopped cleanup
- Comprehensive JavaDoc with use case examples

---

## GAT-17: Add no_data Relationship - ✅ COMPLETE

**Complete specification provided in:** [GAT-17_NoDataRelationship_COMPLETE_SPEC.md](GAT-17_NoDataRelationship_COMPLETE_SPEC.md)

### Summary

Breaking change implementation for v1.0.0:

**Changes Required:**
1. Add `from nifiapi.relationship import Relationship` import
2. Add `getRelationships()` method returning 3 relationships
3. Update 204 status handler to route to `no_data` (line 82-85)
4. Update TimeoutError handler to route to `no_data` (line 107-109)

**Breaking Change Impact:**
- Old: 2 relationships (success, failure)
- New: 3 relationships (success, failure, no_data)
- Timeouts now route to `no_data` instead of `success`

**Migration Guide Included:**
- User action required: Add connection from `no_data` relationship
- Recommended flow patterns provided
- Release notes entry template included

**Why This Is Better:**
- Semantic clarity: timeout ≠ success
- Enables proper flow metrics
- Allows different handling for "no data" vs "processed request"

---

## Implementation Priority Update

### CRITICAL (Must Fix Before Merge) - 9 items
1. ✅ GAT-2: SwaggerServlet error handling
2. ✅ GAT-3: SSL/TLS implementation (complete 7-step spec)
3. ✅ GAT-4, GAT-5, GAT-6: Add nocodenation tags
4. ✅ GAT-12-16: Python error messages with attributes
5. ✅ MGR-1, MGR-3: Follow FIX_INSTRUCTIONS.md

### MEDIUM (Should Fix Before Merge) - 2 items
6. ✅ GAT-7: RespondWithTimestamp example processor (complete spec)
7. ✅ GAT-17: Add no_data relationship (complete spec with migration guide)

### Total: 11 items with COMPLETE specifications ✅

---

*All specifications are complete and ready for implementation*