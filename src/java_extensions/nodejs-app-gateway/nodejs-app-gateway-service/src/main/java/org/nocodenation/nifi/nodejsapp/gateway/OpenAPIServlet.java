package org.nocodenation.nifi.nodejsapp.gateway;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Servlet that serves the dynamically generated OpenAPI specification.
 *
 * This servlet generates the OpenAPI JSON specification on-the-fly from the
 * current endpoint registry, ensuring documentation is always up-to-date.
 */
public class OpenAPIServlet extends HttpServlet {

    private final OpenAPIGenerator generator;
    private final StandardNodeJSAppAPIGateway gateway;

    public OpenAPIServlet(OpenAPIGenerator generator, StandardNodeJSAppAPIGateway gateway) {
        this.generator = generator;
        this.gateway = gateway;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        try {
            // Generate OpenAPI spec from current endpoint registry
            String spec = generator.generateSpec(gateway.getEndpointRegistry());

            // Set response headers
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");

            // Add CORS headers to allow Swagger UI to fetch the spec
            addCorsHeaders(resp);

            // Write spec to response
            resp.getWriter().write(spec);
            resp.setStatus(HttpServletResponse.SC_OK);

        } catch (Exception e) {
            resp.sendError(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to generate OpenAPI specification: " + e.getMessage()
            );
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        // Handle CORS preflight
        addCorsHeaders(resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private void addCorsHeaders(HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setHeader("Access-Control-Max-Age", "3600");
    }
}
