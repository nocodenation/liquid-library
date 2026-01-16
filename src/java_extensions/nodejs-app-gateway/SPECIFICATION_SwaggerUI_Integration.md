# Swagger UI Integration for NodeJS App API Gateway

**Version:** 1.0.0
**Status:** Specification Draft
**Author:** Claude Code
**Date:** 2026-01-03

---

## Table of Contents

1. [Overview](#overview)
2. [Goals and Objectives](#goals-and-objectives)
3. [Architecture](#architecture)
4. [OpenAPI Specification Generation](#openapi-specification-generation)
5. [Gateway Service Enhancements](#gateway-service-enhancements)
6. [Processor Enhancements](#processor-enhancements)
7. [User Interface](#user-interface)
8. [Security Considerations](#security-considerations)
9. [Implementation Phases](#implementation-phases)
10. [Examples](#examples)
11. [Future Enhancements](#future-enhancements)

---

## Overview

This specification defines the integration of auto-generated OpenAPI documentation and Swagger UI into the NodeJS App API Gateway. The integration provides self-documenting APIs that update dynamically as NiFi processors register and unregister endpoints.

### Purpose

- **Developer Experience**: Enable developers to discover and test Gateway endpoints without reading NiFi flow documentation
- **Interactive Documentation**: Provide live, browser-based API testing via Swagger UI
- **Real-Time Updates**: Keep documentation synchronized with registered endpoints automatically
- **Integration Support**: Simplify the process of integrating external applications with NiFi flows

### Scope

This specification covers:
- OpenAPI 3.0 specification generation from registered endpoints
- Swagger UI integration and serving
- Gateway configuration properties
- Processor metadata for enhanced documentation
- Security and access control considerations

---

## Goals and Objectives

### Primary Goals

1. **Auto-Documentation**: Automatically generate OpenAPI specs from endpoint registry
2. **Zero-Configuration MVP**: Work out-of-the-box with minimal configuration
3. **Progressive Enhancement**: Support optional metadata for richer documentation
4. **Performance**: Minimal overhead on Gateway request processing
5. **Maintainability**: Clean separation between documentation and core Gateway functionality

### Non-Goals (Out of Scope)

- Request/response validation against schemas (future enhancement)
- Authentication/authorization for API access (handled separately)
- API versioning (future enhancement)
- Custom OpenAPI extensions

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────┐
│           NodeJSAppAPIGateway Service                   │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────┐ │
│  │  Gateway    │  │   Metrics    │  │   Internal    │ │
│  │  Servlet    │  │   Servlet    │  │     API       │ │
│  │  (existing) │  │  (existing)  │  │  (existing)   │ │
│  └─────────────┘  └──────────────┘  └───────────────┘ │
│                                                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │          NEW: OpenAPI/Swagger Components         │  │
│  │                                                   │  │
│  │  ┌────────────────┐      ┌──────────────────┐  │  │
│  │  │  OpenAPI       │      │  Swagger UI      │  │  │
│  │  │  Generator     │──────│  Servlet         │  │  │
│  │  └────────────────┘      └──────────────────┘  │  │
│  │         │                         │             │  │
│  │         │                         │             │  │
│  │         ▼                         ▼             │  │
│  │  Endpoint Registry         Static Assets       │  │
│  │  + Metadata                (Swagger UI)        │  │
│  └──────────────────────────────────────────────────┘  │
│                                                          │
└─────────────────────────────────────────────────────────┘
                          ▲
                          │
                  Metadata from
                          │
        ┌─────────────────┴──────────────────┐
        │  ReceiveFromNodeJSApp Processor    │
        │  (Enhanced with metadata)          │
        └────────────────────────────────────┘
```

### New Components

#### 1. OpenAPIGenerator

**Responsibility**: Generate OpenAPI 3.0 specification from endpoint registry

**Key Methods**:
```java
public class OpenAPIGenerator {
    /**
     * Generate complete OpenAPI specification
     */
    public String generateOpenAPISpec(
        Map<String, EndpointRegistration> endpoints,
        GatewayConfig config
    );

    /**
     * Convert NiFi endpoint pattern to OpenAPI path
     * Example: /api/quality-event/:eventId → /api/quality-event/{eventId}
     */
    private String convertPathPattern(String nifiPattern);

    /**
     * Generate parameter definitions from path pattern
     */
    private List<ParameterObject> extractPathParameters(String pattern);

    /**
     * Generate request/response schemas from metadata
     */
    private SchemaObject generateSchema(EndpointMetadata metadata);
}
```

#### 2. SwaggerServlet

**Responsibility**: Serve Swagger UI and handle UI requests

**Key Methods**:
```java
public class SwaggerServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        String path = req.getPathInfo();

        if (path == null || path.equals("/")) {
            // Serve main Swagger UI HTML
            serveSwaggerUI(resp);
        } else {
            // Serve static assets (CSS, JS)
            serveStaticAsset(path, resp);
        }
    }

    private void serveSwaggerUI(HttpServletResponse resp);
    private void serveStaticAsset(String path, HttpServletResponse resp);
}
```

#### 3. OpenAPIServlet

**Responsibility**: Serve dynamically generated OpenAPI specification

**Key Methods**:
```java
public class OpenAPIServlet extends HttpServlet {
    private final OpenAPIGenerator generator;
    private final NodeJSAppAPIGateway gateway;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        // Generate fresh OpenAPI spec from current endpoint registry
        String spec = generator.generateOpenAPISpec(
            gateway.getEndpointRegistry(),
            gateway.getConfig()
        );

        resp.setContentType("application/json");
        resp.getWriter().write(spec);
    }
}
```

---

## OpenAPI Specification Generation

### Base OpenAPI Structure

```json
{
  "openapi": "3.0.0",
  "info": {
    "title": "NiFi Gateway API",
    "description": "Auto-generated API documentation for registered endpoints",
    "version": "1.0.0",
    "contact": {
      "name": "NiFi Gateway",
      "url": "http://localhost:5050"
    }
  },
  "servers": [
    {
      "url": "http://localhost:5050",
      "description": "Gateway Server"
    }
  ],
  "paths": {},
  "components": {
    "schemas": {},
    "responses": {
      "Accepted": {
        "description": "Request accepted and queued for processing",
        "content": {
          "application/json": {
            "schema": {
              "type": "object",
              "properties": {
                "status": {"type": "string", "example": "accepted"},
                "message": {"type": "string"}
              }
            }
          }
        }
      },
      "QueueFull": {
        "description": "Gateway queue is full - retry later",
        "content": {
          "application/json": {
            "schema": {
              "type": "object",
              "properties": {
                "status": {"type": "string", "example": "queue_full"},
                "message": {"type": "string"}
              }
            }
          }
        }
      },
      "NotFound": {
        "description": "Endpoint not registered",
        "content": {
          "application/json": {
            "schema": {
              "type": "object",
              "properties": {
                "status": {"type": "string", "example": "not_found"},
                "message": {"type": "string"}
              }
            }
          }
        }
      }
    }
  }
}
```

### Path Generation from Endpoint Registry

#### Input: Endpoint Registration
```java
EndpointRegistration {
    pattern: "/api/quality-event/:eventId",
    handler: EndpointHandler,
    metadata: EndpointMetadata {
        description: "Submit quality event data",
        method: "POST",
        requestSchema: "{...}",  // JSON Schema
        responseExample: "{...}",
        tags: ["Quality Events"]
    }
}
```

#### Output: OpenAPI Path Object
```json
{
  "/api/quality-event/{eventId}": {
    "post": {
      "summary": "Submit quality event data",
      "description": "Submit quality event data for processing",
      "tags": ["Quality Events"],
      "parameters": [
        {
          "name": "eventId",
          "in": "path",
          "required": true,
          "schema": {
            "type": "string"
          },
          "description": "Unique event identifier",
          "example": "evt_1767392946540_p59jaeo"
        }
      ],
      "requestBody": {
        "required": true,
        "content": {
          "application/json": {
            "schema": {
              "type": "object",
              "description": "Quality event payload"
            }
          }
        }
      },
      "responses": {
        "202": {
          "$ref": "#/components/responses/Accepted"
        },
        "404": {
          "$ref": "#/components/responses/NotFound"
        },
        "503": {
          "$ref": "#/components/responses/QueueFull"
        }
      }
    }
  }
}
```

### Pattern Conversion Rules

| NiFi Pattern | OpenAPI Path | Notes |
|--------------|--------------|-------|
| `/api/events` | `/api/events` | Literal path - no change |
| `/api/event/:id` | `/api/event/{id}` | Single parameter |
| `/api/:type/:id` | `/api/{type}/{id}` | Multiple parameters |
| `/api/event/:id/status` | `/api/event/{id}/status` | Parameter mid-path |

### Path Parameter Extraction

```java
public List<ParameterObject> extractPathParameters(String pattern) {
    List<ParameterObject> params = new ArrayList<>();

    // Regex to find :paramName patterns
    Pattern regex = Pattern.compile(":([a-zA-Z][a-zA-Z0-9_]*)");
    Matcher matcher = regex.matcher(pattern);

    while (matcher.find()) {
        String paramName = matcher.group(1);
        params.add(new ParameterObject()
            .name(paramName)
            .in("path")
            .required(true)
            .schema(new Schema().type("string"))
            .description("Path parameter: " + paramName)
        );
    }

    return params;
}
```

---

## Gateway Service Enhancements

### New Configuration Properties

Add to `StandardNodeJSAppAPIGateway`:

```java
public static final PropertyDescriptor SWAGGER_ENABLED = new PropertyDescriptor.Builder()
    .name("swagger-enabled")
    .displayName("Enable Swagger UI")
    .description("Enable auto-generated API documentation via Swagger UI")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("true")
    .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
    .build();

public static final PropertyDescriptor SWAGGER_PATH = new PropertyDescriptor.Builder()
    .name("swagger-path")
    .displayName("Swagger UI Path")
    .description("URL path for accessing Swagger UI (e.g., /swagger)")
    .required(true)
    .defaultValue("/swagger")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .dependsOn(SWAGGER_ENABLED, "true")
    .build();

public static final PropertyDescriptor OPENAPI_PATH = new PropertyDescriptor.Builder()
    .name("openapi-path")
    .displayName("OpenAPI Spec Path")
    .description("URL path for accessing OpenAPI JSON specification (e.g., /openapi.json)")
    .required(true)
    .defaultValue("/openapi.json")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .dependsOn(SWAGGER_ENABLED, "true")
    .build();

public static final PropertyDescriptor INCLUDE_EXAMPLES = new PropertyDescriptor.Builder()
    .name("include-examples")
    .displayName("Include Request Examples")
    .description("Include example requests/responses from actual gateway traffic")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("false")
    .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
    .dependsOn(SWAGGER_ENABLED, "true")
    .build();
```

### Enhanced Endpoint Registry

Extend `EndpointRegistration` to include metadata:

```java
public class EndpointRegistration {
    private final String pattern;
    private final EndpointHandler handler;
    private final EndpointMetadata metadata;  // NEW

    // Existing fields
    private final BlockingQueue<GatewayRequest> queue;
    private final EndpointMetrics metrics;

    // Constructor, getters, etc.
}

public class EndpointMetadata {
    private String description;
    private String method;  // POST, GET, etc.
    private String requestSchema;  // JSON Schema
    private String responseExample;  // JSON example
    private List<String> tags;  // For grouping in Swagger UI

    // Builder pattern
    public static class Builder {
        // ...
    }
}
```

### Servlet Registration

Update `StandardNodeJSAppAPIGateway.onEnabled()`:

```java
@Override
public void onEnabled(ConfigurationContext context) throws InitializationException {
    // ... existing code ...

    // Register existing servlets
    servletHandler.addServletWithMapping(gatewayServlet, "/*");
    servletHandler.addServletWithMapping(metricsServlet, "/_metrics");
    servletHandler.addServletWithMapping(internalApiServlet, "/_internal/*");

    // Register Swagger UI servlets if enabled
    if (context.getProperty(SWAGGER_ENABLED).asBoolean()) {
        String swaggerPath = context.getProperty(SWAGGER_PATH).getValue();
        String openapiPath = context.getProperty(OPENAPI_PATH).getValue();

        OpenAPIGenerator generator = new OpenAPIGenerator(this);

        OpenAPIServlet openapiServlet = new OpenAPIServlet(generator, this);
        servletHandler.addServletWithMapping(openapiServlet, openapiPath);

        SwaggerServlet swaggerServlet = new SwaggerServlet(openapiPath);
        servletHandler.addServletWithMapping(swaggerServlet, swaggerPath + "/*");

        getLogger().info("Swagger UI enabled at {}{}", baseUrl, swaggerPath);
        getLogger().info("OpenAPI spec available at {}{}", baseUrl, openapiPath);
    }

    // ... rest of existing code ...
}
```

---

## Processor Enhancements

### New Properties for ReceiveFromNodeJSApp

```java
public static final PropertyDescriptor ENDPOINT_DESCRIPTION = new PropertyDescriptor.Builder()
    .name("endpoint-description")
    .displayName("Endpoint Description")
    .description("Human-readable description of this endpoint for API documentation")
    .required(false)
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .build();

public static final PropertyDescriptor REQUEST_SCHEMA = new PropertyDescriptor.Builder()
    .name("request-schema")
    .displayName("Request Body Schema")
    .description("JSON Schema defining the expected request body structure")
    .required(false)
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .build();

public static final PropertyDescriptor RESPONSE_EXAMPLE = new PropertyDescriptor.Builder()
    .name("response-example")
    .displayName("Response Example")
    .description("Example JSON response for documentation purposes")
    .required(false)
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .build();

public static final PropertyDescriptor API_TAGS = new PropertyDescriptor.Builder()
    .name("api-tags")
    .displayName("API Tags")
    .description("Comma-separated tags for grouping endpoints in Swagger UI (e.g., 'Quality Events, Triage')")
    .required(false)
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .build();
```

### Enhanced Endpoint Registration

Update `onScheduled()` to pass metadata:

```java
@Override
public void onScheduled(ProcessContext context) {
    // ... existing code to get gateway service and pattern ...

    // Build metadata from processor properties
    EndpointMetadata.Builder metadataBuilder = new EndpointMetadata.Builder()
        .method("POST");  // Default to POST

    if (context.getProperty(ENDPOINT_DESCRIPTION).isSet()) {
        metadataBuilder.description(
            context.getProperty(ENDPOINT_DESCRIPTION).getValue()
        );
    }

    if (context.getProperty(REQUEST_SCHEMA).isSet()) {
        metadataBuilder.requestSchema(
            context.getProperty(REQUEST_SCHEMA).getValue()
        );
    }

    if (context.getProperty(RESPONSE_EXAMPLE).isSet()) {
        metadataBuilder.responseExample(
            context.getProperty(RESPONSE_EXAMPLE).getValue()
        );
    }

    if (context.getProperty(API_TAGS).isSet()) {
        String tagsStr = context.getProperty(API_TAGS).getValue();
        List<String> tags = Arrays.asList(tagsStr.split(","))
            .stream()
            .map(String::trim)
            .collect(Collectors.toList());
        metadataBuilder.tags(tags);
    }

    EndpointMetadata metadata = metadataBuilder.build();

    // Register endpoint with metadata
    try {
        gateway.registerEndpoint(endpointPattern, endpointHandler, metadata);
        getLogger().info("Registered endpoint: {} with Swagger documentation", endpointPattern);
    } catch (EndpointAlreadyRegisteredException e) {
        throw new ProcessException("Failed to register endpoint: " + e.getMessage(), e);
    }
}
```

---

## User Interface

### Swagger UI Integration

#### Approach: Embedded Static Files

Bundle Swagger UI static files (HTML, CSS, JS) in the NAR:

```
nodejs-app-gateway-service/
└── src/main/resources/
    └── swagger-ui/
        ├── index.html
        ├── swagger-ui.css
        ├── swagger-ui-bundle.js
        ├── swagger-ui-standalone-preset.js
        └── favicon.ico
```

#### SwaggerServlet Implementation

```java
public class SwaggerServlet extends HttpServlet {
    private static final String RESOURCE_BASE = "/swagger-ui/";
    private final String openapiSpecUrl;

    public SwaggerServlet(String openapiSpecUrl) {
        this.openapiSpecUrl = openapiSpecUrl;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.equals("/")) {
            serveIndexHtml(resp);
        } else {
            serveStaticResource(pathInfo, resp);
        }
    }

    private void serveIndexHtml(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html");

        // Load index.html template and inject OpenAPI URL
        String html = loadResource("index.html");
        html = html.replace("{{OPENAPI_SPEC_URL}}", openapiSpecUrl);

        resp.getWriter().write(html);
    }

    private void serveStaticResource(String path, HttpServletResponse resp)
            throws IOException {

        String resourcePath = RESOURCE_BASE + path;
        InputStream resource = getClass().getResourceAsStream(resourcePath);

        if (resource == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // Set appropriate content type
        String contentType = getContentType(path);
        resp.setContentType(contentType);

        // Stream resource to response
        try (InputStream in = resource;
             OutputStream out = resp.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }

    private String loadResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE_BASE + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String getContentType(String path) {
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }
}
```

#### index.html Template

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>NiFi Gateway API Documentation</title>
    <link rel="stylesheet" type="text/css" href="swagger-ui.css">
    <link rel="icon" type="image/png" href="favicon.ico">
    <style>
        html { box-sizing: border-box; overflow: -moz-scrollbars-vertical; overflow-y: scroll; }
        *, *:before, *:after { box-sizing: inherit; }
        body { margin: 0; padding: 0; }
    </style>
</head>
<body>
    <div id="swagger-ui"></div>
    <script src="swagger-ui-bundle.js" charset="UTF-8"></script>
    <script src="swagger-ui-standalone-preset.js" charset="UTF-8"></script>
    <script>
        window.onload = function() {
            const ui = SwaggerUIBundle({
                url: "{{OPENAPI_SPEC_URL}}",
                dom_id: '#swagger-ui',
                deepLinking: true,
                presets: [
                    SwaggerUIBundle.presets.apis,
                    SwaggerUIStandalonePreset
                ],
                plugins: [
                    SwaggerUIBundle.plugins.DownloadUrl
                ],
                layout: "StandaloneLayout"
            });
            window.ui = ui;
        };
    </script>
</body>
</html>
```

### User Experience

1. **Access Swagger UI**: Navigate to `http://localhost:5050/swagger`
2. **View Endpoints**: See all registered endpoints grouped by tags
3. **Expand Endpoint**: Click to see parameters, request/response schemas
4. **Try It Out**: Click "Try it out" button to test endpoint interactively
5. **Execute**: Fill in parameters, click "Execute" to send real request
6. **View Response**: See actual response from Gateway

---

## Security Considerations

### Access Control

#### Phase 1: Public Access (MVP)
- Swagger UI accessible without authentication
- Same access level as Gateway endpoints
- Suitable for development/internal use

#### Phase 2: Optional Authentication (Future)
```java
public static final PropertyDescriptor SWAGGER_AUTH_ENABLED = new PropertyDescriptor.Builder()
    .name("swagger-auth-enabled")
    .displayName("Require Authentication for Swagger UI")
    .description("Require authentication to access API documentation")
    .allowableValues("true", "false")
    .defaultValue("false")
    .build();
```

### Information Disclosure

#### Concerns
- Endpoint patterns reveal flow structure
- Request/response schemas expose data models
- Examples may contain sensitive data

#### Mitigations
1. **Production Toggle**: Disable Swagger in production environments
2. **Sanitized Examples**: Filter out sensitive fields from examples
3. **Network Isolation**: Run Gateway on private network
4. **Schema Validation**: Only include explicitly configured schemas

### CORS for Swagger UI

Swagger UI makes requests to OpenAPI endpoint - ensure CORS allows:

```java
// In OpenAPIServlet.doGet()
resp.setHeader("Access-Control-Allow-Origin", "*");
resp.setHeader("Access-Control-Allow-Methods", "GET");
resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
```

---

## Implementation Phases

### Phase 1: MVP - Basic OpenAPI Generation (Recommended First PR)

**Goal**: Auto-generate minimal OpenAPI spec from endpoint registry

**Deliverables**:
- OpenAPIGenerator class
- OpenAPIServlet for serving spec
- SwaggerServlet for serving UI
- Swagger UI static files bundled
- Gateway configuration properties
- Basic path/parameter generation
- No processor metadata required

**Acceptance Criteria**:
- Navigate to `/swagger` and see Swagger UI
- See all registered endpoints with path parameters
- Click "Try it out" and successfully call endpoints
- No errors in console or logs

**Estimated Effort**: 2-3 days

### Phase 2: Enhanced Documentation

**Goal**: Support processor metadata for richer documentation

**Deliverables**:
- EndpointMetadata class
- New processor properties (description, schema, etc.)
- Enhanced OpenAPI generation with schemas
- Tags for endpoint grouping
- Request/response examples

**Acceptance Criteria**:
- Processor descriptions appear in Swagger UI
- Endpoints grouped by tags
- Request/response schemas shown
- Example payloads displayed

**Estimated Effort**: 1-2 days

### Phase 3: Advanced Features (Future)

**Goal**: Production-ready features

**Deliverables**:
- Request validation against schemas
- Authentication for Swagger UI
- Example data collection from metrics
- Schema evolution tracking
- API versioning support

**Acceptance Criteria**:
- Invalid requests rejected with schema errors
- Swagger UI requires login
- Examples automatically populated
- Schema changes tracked

**Estimated Effort**: 3-5 days

---

## Examples

### Example 1: Minimal Configuration (Phase 1)

**Gateway Configuration**:
```
Swagger UI Enabled: true
Swagger UI Path: /swagger
OpenAPI Spec Path: /openapi.json
```

**Processor Configuration** (ReceiveFromNodeJSApp):
```
Endpoint Pattern: /api/quality-event/:eventId
```

**Generated OpenAPI**:
```json
{
  "openapi": "3.0.0",
  "info": {
    "title": "NiFi Gateway API",
    "version": "1.0.0"
  },
  "paths": {
    "/api/quality-event/{eventId}": {
      "post": {
        "summary": "Endpoint: /api/quality-event/:eventId",
        "parameters": [
          {
            "name": "eventId",
            "in": "path",
            "required": true,
            "schema": {"type": "string"}
          }
        ],
        "responses": {
          "202": {"$ref": "#/components/responses/Accepted"}
        }
      }
    }
  }
}
```

### Example 2: Enhanced with Metadata (Phase 2)

**Processor Configuration**:
```
Endpoint Pattern: /api/quality-event/:eventId
Endpoint Description: Submit quality event data for LASER workflow processing
API Tags: Quality Events, LASER
Request Schema: {
  "type": "object",
  "required": ["eventId", "timestamp", "currentStage"],
  "properties": {
    "eventId": {"type": "string"},
    "timestamp": {"type": "string", "format": "date-time"},
    "currentStage": {"type": "string", "enum": ["laser", "triage", "resolution", "completed"]},
    "laser": {"type": "object"},
    "triage": {"type": "object"},
    "resolution": {"type": "object"}
  }
}
Response Example: {
  "status": "accepted",
  "eventId": "evt_1767392946540_p59jaeo",
  "message": "Quality event queued for processing"
}
```

**Generated OpenAPI** (excerpt):
```json
{
  "paths": {
    "/api/quality-event/{eventId}": {
      "post": {
        "summary": "Submit quality event data for LASER workflow processing",
        "tags": ["Quality Events", "LASER"],
        "parameters": [
          {
            "name": "eventId",
            "in": "path",
            "required": true,
            "schema": {"type": "string"},
            "example": "evt_1767392946540_p59jaeo"
          }
        ],
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "type": "object",
                "required": ["eventId", "timestamp", "currentStage"],
                "properties": {
                  "eventId": {"type": "string"},
                  "timestamp": {"type": "string", "format": "date-time"},
                  "currentStage": {
                    "type": "string",
                    "enum": ["laser", "triage", "resolution", "completed"]
                  }
                }
              }
            }
          }
        },
        "responses": {
          "202": {
            "description": "Request accepted",
            "content": {
              "application/json": {
                "example": {
                  "status": "accepted",
                  "eventId": "evt_1767392946540_p59jaeo",
                  "message": "Quality event queued for processing"
                }
              }
            }
          }
        }
      }
    }
  }
}
```

### Example 3: Multiple Endpoints

**NiFi Flow**:
```
Processor 1: /api/quality-event/:eventId
Processor 2: /api/users/:userId
Processor 3: /api/metrics
```

**Swagger UI Display**:
```
Quality Events
  POST /api/quality-event/{eventId}

User Management
  POST /api/users/{userId}

Monitoring
  POST /api/metrics
```

---

## Future Enhancements

### 1. Request Validation

Validate incoming requests against JSON Schemas before queuing:

```java
public class SchemaValidator {
    public ValidationResult validate(String jsonPayload, String jsonSchema) {
        // Use org.everit.json.schema or similar
    }
}

// In GatewayServlet
if (endpoint.hasSchema()) {
    ValidationResult result = validator.validate(requestBody, endpoint.getSchema());
    if (!result.isValid()) {
        return sendError(400, "Invalid request: " + result.getErrors());
    }
}
```

### 2. Authentication for Swagger UI

Support basic auth, bearer token, or OAuth for accessing documentation:

```java
public static final PropertyDescriptor SWAGGER_AUTH_TYPE = ...
public static final PropertyDescriptor SWAGGER_USERNAME = ...
public static final PropertyDescriptor SWAGGER_PASSWORD = ...
```

### 3. API Versioning

Support multiple API versions with separate OpenAPI specs:

```
/api/v1/quality-event/:eventId
/api/v2/quality-event/:eventId

/swagger/v1
/swagger/v2
```

### 4. Metrics Integration

Show real-time metrics in Swagger UI:
- Request count per endpoint
- Success rate
- Average response time
- Current queue depth

### 5. Schema Evolution Tracking

Track schema changes over time:
- Version history
- Breaking vs non-breaking changes
- Deprecation notices

### 6. Custom OpenAPI Extensions

Support NiFi-specific extensions:

```json
{
  "paths": {
    "/api/quality-event/{eventId}": {
      "post": {
        "x-nifi-processor": "ReceiveFromNodeJSApp",
        "x-nifi-queue-size": 100,
        "x-nifi-success-rate": 99.5
      }
    }
  }
}
```

### 7. Export Client SDKs

Generate client libraries from OpenAPI spec:
- JavaScript/TypeScript
- Python
- Java
- Go

### 8. Mock Server Mode

Use OpenAPI spec to run mock server for testing:

```java
public static final PropertyDescriptor ENABLE_MOCK_MODE = ...
```

---

## Dependencies

### Swagger UI Static Files

**Source**: https://github.com/swagger-api/swagger-ui/releases

**Version**: 5.x (latest stable)

**Files Required**:
- `swagger-ui.css`
- `swagger-ui-bundle.js`
- `swagger-ui-standalone-preset.js`
- `favicon.ico`

**License**: Apache 2.0 (compatible with NiFi)

**Bundle Location**: `src/main/resources/swagger-ui/`

### OpenAPI Generation

**Option 1: Manual Generation** (Recommended for MVP)
- No external dependencies
- Build JSON manually using StringBuilder or Jackson
- Full control over output
- ~200 lines of code

**Option 2: Swagger Core Library**
```xml
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-core</artifactId>
    <version>2.2.20</version>
</dependency>
```
- Pre-built OpenAPI object model
- JSON/YAML serialization
- More features but heavier dependency

**Recommendation**: Start with Option 1 for MVP, consider Option 2 for Phase 2+

---

## Testing Strategy

### Unit Tests

```java
@Test
public void testPathParameterExtraction() {
    OpenAPIGenerator generator = new OpenAPIGenerator();

    List<Parameter> params = generator.extractPathParameters("/api/event/:id");

    assertEquals(1, params.size());
    assertEquals("id", params.get(0).getName());
    assertEquals("path", params.get(0).getIn());
    assertTrue(params.get(0).getRequired());
}

@Test
public void testPatternConversion() {
    OpenAPIGenerator generator = new OpenAPIGenerator();

    assertEquals("/api/event/{id}",
        generator.convertPathPattern("/api/event/:id"));

    assertEquals("/api/{type}/{id}",
        generator.convertPathPattern("/api/:type/:id"));
}

@Test
public void testOpenAPIGeneration() {
    Map<String, EndpointRegistration> endpoints = new HashMap<>();
    endpoints.put("/api/test/:id", createMockRegistration());

    String spec = generator.generateOpenAPISpec(endpoints, config);

    assertTrue(spec.contains("\"openapi\": \"3.0.0\""));
    assertTrue(spec.contains("\"/api/test/{id}\""));
}
```

### Integration Tests

```java
@Test
public void testSwaggerUIAccessible() {
    HttpResponse response = httpClient.get("http://localhost:5050/swagger");

    assertEquals(200, response.getStatusCode());
    assertTrue(response.getBody().contains("swagger-ui"));
}

@Test
public void testOpenAPISpecValid() {
    HttpResponse response = httpClient.get("http://localhost:5050/openapi.json");

    assertEquals(200, response.getStatusCode());
    assertEquals("application/json", response.getContentType());

    JsonNode spec = objectMapper.readTree(response.getBody());
    assertEquals("3.0.0", spec.get("openapi").asText());
}

@Test
public void testDynamicEndpointUpdate() {
    // Register new endpoint
    gateway.registerEndpoint("/api/new/:id", handler, metadata);

    // Fetch OpenAPI spec
    String spec = httpClient.get("http://localhost:5050/openapi.json").getBody();

    // Verify new endpoint present
    assertTrue(spec.contains("/api/new/{id}"));
}
```

### Manual Testing

1. Start NiFi with Gateway service enabled
2. Add ReceiveFromNodeJSApp processor with endpoint `/api/test/:id`
3. Navigate to `http://localhost:5050/swagger`
4. Verify Swagger UI loads
5. Expand `/api/test/{id}` endpoint
6. Click "Try it out"
7. Enter test values and execute
8. Verify 202 response
9. Check NiFi processor has received FlowFile

---

## Backward Compatibility

### Existing Gateway Functionality

**No Impact**: Swagger integration is completely optional and does not affect:
- Existing endpoint registration
- Gateway request handling
- Metrics collection
- Internal API

### Configuration

**Default Behavior**: Swagger UI enabled by default but can be disabled:

```
Swagger UI Enabled: false  // Completely disabled, no servlets registered
```

### Processor Compatibility

**Optional Metadata**: All new processor properties are optional:
- Existing flows continue to work without changes
- Metadata properties can be added incrementally
- No processor configuration migration required

---

## Performance Considerations

### OpenAPI Generation

**Caching Strategy**:
```java
private volatile String cachedSpec = null;
private volatile long cacheTimestamp = 0;
private static final long CACHE_TTL_MS = 5000; // 5 seconds

public String generateOpenAPISpec(...) {
    long now = System.currentTimeMillis();

    if (cachedSpec != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
        return cachedSpec;
    }

    // Generate fresh spec
    String spec = doGenerateOpenAPISpec(...);

    cachedSpec = spec;
    cacheTimestamp = now;

    return spec;
}
```

**Invalidation**: Clear cache when endpoints are registered/unregistered

### Swagger UI Static Files

**Optimization**:
- Serve with proper caching headers
- Enable gzip compression
- Consider CDN for production (optional)

### Memory Footprint

**Estimated Impact**:
- Swagger UI files: ~2 MB (bundled in NAR)
- OpenAPI generator: ~50 KB (runtime objects)
- Cached spec: ~10-50 KB (depending on endpoint count)

**Total**: < 3 MB additional memory (negligible for NiFi)

---

## Conclusion

This specification defines a comprehensive Swagger UI integration for the NodeJS App API Gateway that:

1. **Enhances Developer Experience**: Self-documenting APIs with interactive testing
2. **Maintains Simplicity**: Zero-configuration MVP with optional enhancements
3. **Preserves Performance**: Minimal overhead on Gateway request processing
4. **Ensures Compatibility**: No impact on existing flows or configurations
5. **Enables Growth**: Clear path from MVP to production-ready features

**Recommended Next Steps**:
1. Review and approve this specification
2. Create implementation branch: `feat/swagger-ui-integration`
3. Implement Phase 1 (MVP) - Basic OpenAPI generation
4. Test with quality-event-system frontend
5. Submit PR for review
6. Iterate with Phase 2+ based on feedback

---

**Document Version**: 1.0.0
**Last Updated**: 2026-01-03
**Status**: Draft - Awaiting Review
