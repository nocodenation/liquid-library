package org.nocodenation.nifi.nodejsapp.gateway;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Servlet that serves the Swagger UI interface and static assets.
 *
 * This servlet serves the Swagger UI HTML/CSS/JS files from bundled resources
 * and injects the OpenAPI specification URL into the HTML template.
 */
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

        // Serve index.html for root path
        if (pathInfo == null || pathInfo.equals("/") || pathInfo.equals("/index.html")) {
            serveIndexHtml(resp);
        } else {
            // Serve static resources (CSS, JS, etc.)
            serveStaticResource(pathInfo, resp);
        }
    }

    /**
     * Serve the main Swagger UI HTML page with injected OpenAPI spec URL
     */
    private void serveIndexHtml(HttpServletResponse resp) throws IOException {
        resp.setContentType("text/html");
        resp.setCharacterEncoding("UTF-8");

        // Load index.html template
        String html = loadResourceAsString("index.html");

        // Inject OpenAPI spec URL
        html = html.replace("{{OPENAPI_SPEC_URL}}", openapiSpecUrl);

        resp.getWriter().write(html);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Serve static resources (CSS, JS, images)
     */
    private void serveStaticResource(String path, HttpServletResponse resp) throws IOException {
        // Remove leading slash
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        String resourcePath = RESOURCE_BASE + path;
        InputStream resource = getClass().getResourceAsStream(resourcePath);

        if (resource == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Resource not found: " + path);
            return;
        }

        // Set appropriate content type
        String contentType = getContentType(path);
        resp.setContentType(contentType);

        // Add caching headers for static assets
        if (!path.endsWith(".html")) {
            resp.setHeader("Cache-Control", "public, max-age=31536000"); // 1 year
        }

        // Stream resource to response
        try (InputStream in = resource;
             OutputStream out = resp.getOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        resp.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Load a resource as a String
     */
    private String loadResourceAsString(String name) throws IOException {
        String resourcePath = RESOURCE_BASE + name;
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Determine content type based on file extension
     */
    private String getContentType(String path) {
        if (path.endsWith(".css")) {
            return "text/css";
        } else if (path.endsWith(".js")) {
            return "application/javascript";
        } else if (path.endsWith(".json")) {
            return "application/json";
        } else if (path.endsWith(".html")) {
            return "text/html";
        } else if (path.endsWith(".png")) {
            return "image/png";
        } else if (path.endsWith(".ico")) {
            return "image/x-icon";
        } else if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
