package org.nocodenation.nifi.nodejsapp.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates OpenAPI 3.0 specification from registered Gateway endpoints.
 *
 * This generator creates a complete OpenAPI specification document by analyzing
 * the endpoint registry and converting NiFi-style path patterns to OpenAPI format.
 */
public class OpenAPIGenerator {

    private static final String OPENAPI_VERSION = "3.0.0";
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile(":([a-zA-Z][a-zA-Z0-9_]*)");

    private final ObjectMapper objectMapper;
    private final String gatewayUrl;

    // Cache for generated spec
    private volatile String cachedSpec = null;
    private volatile long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 5000; // 5 seconds

    public OpenAPIGenerator(String gatewayUrl) {
        this.objectMapper = new ObjectMapper();
        this.gatewayUrl = gatewayUrl;
    }

    /**
     * Generate complete OpenAPI specification from endpoint registry.
     * Results are cached for 5 seconds to improve performance.
     */
    public String generateSpec(Map<String, StandardNodeJSAppAPIGateway.EndpointRegistration> endpoints) {
        long now = System.currentTimeMillis();

        // Return cached spec if still valid
        if (cachedSpec != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedSpec;
        }

        // Generate fresh spec
        String spec = doGenerateSpec(endpoints);

        // Update cache
        cachedSpec = spec;
        cacheTimestamp = now;

        return spec;
    }

    /**
     * Clear the cache (called when endpoints are registered/unregistered)
     */
    public void invalidateCache() {
        cachedSpec = null;
        cacheTimestamp = 0;
    }

    /**
     * Internal method to generate OpenAPI spec
     */
    private String doGenerateSpec(Map<String, StandardNodeJSAppAPIGateway.EndpointRegistration> endpoints) {
        try {
            ObjectNode root = objectMapper.createObjectNode();

            // OpenAPI version
            root.put("openapi", OPENAPI_VERSION);

            // Info section
            ObjectNode info = root.putObject("info");
            info.put("title", "NiFi Gateway API");
            info.put("description", "Auto-generated API documentation for registered endpoints");
            info.put("version", "1.0.0");

            ObjectNode contact = info.putObject("contact");
            contact.put("name", "NiFi Gateway");
            contact.put("url", gatewayUrl);

            // Servers section
            ArrayNode servers = root.putArray("servers");
            ObjectNode server = servers.addObject();
            server.put("url", gatewayUrl);
            server.put("description", "Gateway Server");

            // Paths section
            ObjectNode paths = root.putObject("paths");
            for (Map.Entry<String, StandardNodeJSAppAPIGateway.EndpointRegistration> entry : endpoints.entrySet()) {
                String nifiPattern = entry.getKey();
                String openapiPath = convertPathPattern(nifiPattern);

                ObjectNode pathItem = paths.putObject(openapiPath);
                ObjectNode operation = pathItem.putObject("post"); // Default to POST

                // Summary and description
                operation.put("summary", "Endpoint: " + nifiPattern);
                operation.put("description", "Submit data to " + nifiPattern);

                // Parameters (path parameters from pattern)
                List<String> pathParams = extractPathParameters(nifiPattern);
                if (!pathParams.isEmpty()) {
                    ArrayNode parameters = operation.putArray("parameters");
                    for (String paramName : pathParams) {
                        ObjectNode param = parameters.addObject();
                        param.put("name", paramName);
                        param.put("in", "path");
                        param.put("required", true);
                        param.put("description", "Path parameter: " + paramName);

                        ObjectNode schema = param.putObject("schema");
                        schema.put("type", "string");
                    }
                }

                // Request body
                ObjectNode requestBody = operation.putObject("requestBody");
                requestBody.put("required", true);
                requestBody.put("description", "Request payload");

                ObjectNode content = requestBody.putObject("content");
                ObjectNode jsonContent = content.putObject("application/json");
                ObjectNode requestSchema = jsonContent.putObject("schema");
                requestSchema.put("type", "object");
                requestSchema.put("description", "Request data");

                // Responses
                ObjectNode responses = operation.putObject("responses");

                // 202 Accepted
                ObjectNode response202 = responses.putObject("202");
                response202.put("description", "Request accepted and queued for processing");

                // 404 Not Found
                ObjectNode response404 = responses.putObject("404");
                response404.put("description", "Endpoint not registered");

                // 503 Service Unavailable
                ObjectNode response503 = responses.putObject("503");
                response503.put("description", "Gateway queue is full - retry later");
            }

            // Components section (reusable schemas)
            ObjectNode components = root.putObject("components");
            ObjectNode schemas = components.putObject("schemas");

            // Add common response schemas
            ObjectNode acceptedResponse = schemas.putObject("AcceptedResponse");
            acceptedResponse.put("type", "object");
            ObjectNode acceptedProps = acceptedResponse.putObject("properties");
            acceptedProps.putObject("status").put("type", "string").put("example", "accepted");
            acceptedProps.putObject("message").put("type", "string");

            // Convert to JSON string
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate OpenAPI specification", e);
        }
    }

    /**
     * Convert NiFi path pattern to OpenAPI path format.
     * Example: /api/event/:id -> /api/event/{id}
     */
    private String convertPathPattern(String nifiPattern) {
        Matcher matcher = PATH_PARAM_PATTERN.matcher(nifiPattern);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1);
            matcher.appendReplacement(result, "{" + paramName + "}");
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Extract path parameter names from NiFi pattern.
     * Example: /api/event/:id -> ["id"]
     */
    private List<String> extractPathParameters(String nifiPattern) {
        List<String> params = new ArrayList<>();
        Matcher matcher = PATH_PARAM_PATTERN.matcher(nifiPattern);

        while (matcher.find()) {
            params.add(matcher.group(1));
        }

        return params;
    }
}
