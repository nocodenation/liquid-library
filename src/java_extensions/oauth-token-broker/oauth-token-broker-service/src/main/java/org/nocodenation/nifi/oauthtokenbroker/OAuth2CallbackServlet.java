package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.logging.ComponentLog;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;

/**
 * Handles OAuth 2.0 authorization code callbacks from OAuth providers.
 * <p>
 * This servlet processes incoming OAuth 2.0 callbacks, extracts the authorization code
 * and state parameters, and forwards them to the appropriate OAuth2AccessTokenService
 * for token exchange. It supports the complete OAuth 2.0 authorization code flow with 
 * PKCE (Proof Key for Code Exchange) according to RFC 7636 for enhanced security.
 * <p>
 * The servlet uses the state parameter to identify which processor or service instance
 * the callback is for, enabling multi-processor support where multiple processors can
 * share a single OAuth2AccessTokenService instance while maintaining separate OAuth states
 * and tokens. The state parameter is passed through the entire OAuth flow and is used to
 * retrieve the correct PKCE values when exchanging the authorization code for tokens.
 * <p>
 * The servlet renders HTML responses to the user's browser using Mustache templates:
 * <ul>
 *   <li>A success page when authorization is successful</li>
 *   <li>An error page when authorization fails</li>
 * </ul>
 * <p>
 * This class is designed to work with {@link OAuth2CallbackServer} which provides
 * the HTTP server functionality, while this class focuses on processing the callback
 * logic and maintaining consistent PKCE values throughout the entire OAuth flow.
 *
 * @see OAuth2CallbackServer
 * @see OAuth2AccessTokenService
 */
public class OAuth2CallbackServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    
    private final OAuth2AccessTokenService tokenProvider;
    
    /** Logger for recording events and errors */
    private final ComponentLog logger;
    
    /** Factory for creating Mustache template instances */
    private final MustacheFactory mustacheFactory;
    
    /** Template for rendering the success response page */
    private Mustache successTemplate;
    
    /** Template for rendering the error response page */
    private Mustache errorTemplate;
    
    /** Flag indicating whether templates were successfully loaded */
    private boolean templatesLoaded = false;
    
    /**
     * Constructs a new OAuth2CallbackServlet with the specified logger.
     * <p>
     * Initializes the Mustache template engine and loads the success and error templates.
     * If template loading fails, it will fall back to simple HTML responses.
     *
     * @param provider The token provider instance to register
     * @param logger The component logger to use for logging events and errors
     */
    public OAuth2CallbackServlet(OAuth2AccessTokenService provider, ComponentLog logger) {
        this.logger = logger;
        this.mustacheFactory = new DefaultMustacheFactory();
        this.tokenProvider = provider;
        
        // Load templates
        try {
            this.successTemplate = mustacheFactory.compile("templates/success.mustache");
            this.errorTemplate = mustacheFactory.compile("templates/error.mustache");
            templatesLoaded = true;
            logger.debug("Successfully loaded HTML templates");
        } catch (Exception e) {
            logger.warn("Failed to load HTML templates, will use fallback responses: {}", e.getMessage());
            // Don't throw exception, just use fallback responses
            templatesLoaded = false;
        }
    }
    
    /**
     * Parses query parameters from the request URI.
     * <p>
     * Extracts and URL-decodes key-value pairs from the query string. This is used
     * to extract the authorization code, state parameter, and any error messages
     * from the OAuth 2.0 callback URL.
     * <p>
     * The state parameter is particularly important as it identifies which processor
     * the callback is for, enabling multi-processor support.
     *
     * @param query The query string portion of the URI (after the '?')
     * @return A map of parameter names to their values
     */
    public Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return params;
        }
        
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx > 0) {
                String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        
        return params;
    }
    
    /**
     * Handles HTTP GET requests for the OAuth 2.0 callback.
     * <p>
     * This method is called by the servlet container when an OAuth provider redirects
     * the user back to the callback URL after authorization. It processes the OAuth 2.0
     * authorization code along with the state parameter to identify which processor
     * the callback is for.
     * <p>
     * The method extracts the authorization code and forwards it to the OAuth2AccessTokenService
     * along with the state parameter. The service uses the state to retrieve the correct
     * PKCE code verifier that was generated during the initial authorization request,
     * ensuring consistent PKCE values throughout the entire OAuth flow.
     *
     * @param request The HTTP servlet request
     * @param response The HTTP servlet response
     * @throws ServletException If a servlet-specific error occurs
     * @throws IOException If an I/O error occurs
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        logger.debug("Handling OAuth2 callback GET request");
        
        // Set response headers
        response.setContentType("text/html; charset=UTF-8");
        response.setStatus(HttpServletResponse.SC_OK);
        
        // Get query parameters
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values != null && values.length > 0) {
                params.put(key, values[0]);
            }
        });
        
        // Process the callback
        try (PrintWriter writer = response.getWriter()) {
            handleCallback(request.getRequestURI(), params, writer);
            writer.flush();
        } catch (Exception e) {
            logger.error("Error handling callback", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("Error processing OAuth callback: " + e.getMessage());
        }
    }
    
    /**
     * Legacy method to handle callbacks directly from output stream.
     * <p>
     * This method is kept for backward compatibility with existing code. It parses
     * the query parameters from the request URI, extracts the authorization code and
     * state parameter, and forwards them to the internal handleCallback method.
     * <p>
     * The state parameter is particularly important as it identifies which processor
     * the callback is for, enabling the correct PKCE values to be retrieved for token exchange.
     *
     * @param requestUri The full request URI including path and query string
     * @param out The output stream to write the HTML response to
     * @throws IOException If an I/O error occurs while writing the response
     */
    public void handleCallback(String requestUri, OutputStream out) throws IOException {
        logger.debug("Handling OAuth2 callback via legacy method");
        
        // Parse the query string from the request URI
        int queryIdx = requestUri.indexOf('?');
        String query = queryIdx > 0 ? requestUri.substring(queryIdx + 1) : "";
        Map<String, String> params = parseQueryParams(query);
        
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true)) {
            // Process the callback parameters
            handleCallback(requestUri, params, writer);
            
            // Ensure output is flushed
            writer.flush();
        } catch (Exception e) {
            logger.error("Error handling callback", e);
        }
    }
    
    /**
     * Internal method to process the callback parameters and generate a response.
     * <p>
     * This method:
     * <ol>
     *   <li>Validates the state parameter and identifies which processor the callback is for</li>
     *   <li>Checks for error responses from the OAuth server</li>
     *   <li>Extracts the authorization code</li>
     *   <li>Forwards the code and state to the token provider for processing with the correct PKCE code verifier</li>
     *   <li>Generates an appropriate HTML response for the user's browser</li>
     * </ol>
     * <p>
     * The state parameter is crucial for both security and multi-processor support. It allows
     * the service to retrieve the correct PKCE values that were generated during the initial
     * authorization request, ensuring consistent PKCE values throughout the entire OAuth flow.
     * It also identifies which processor the callback is for when multiple processors share
     * a single OAuth2AccessTokenService instance.
     *
     * @param requestUri The original request URI (for logging)
     * @param params The parsed query parameters
     * @param writer The writer to output the HTML response
     */
    private void handleCallback(String requestUri, Map<String, String> params, PrintWriter writer) {
        // Log the received request URI and parameters
        logger.debug("OAuth2 callback received with URI: {}", requestUri);
        logger.debug("OAuth2 callback parameters: {}", params);
        
        // Get the state parameter which contains the provider ID
        String state = params.get("state");
        if (state == null || state.isEmpty()) {
            logger.error("Missing state parameter in OAuth2 callback");
            writeErrorResponse(writer, "Missing state parameter");
            return;
        }
        
        // Check for error response
        String error = params.get("error");
        if (error != null && !error.isEmpty()) {
            String errorDescription = params.get("error_description");
            logger.error("OAuth2 authorization error: {}", error);
            writeErrorResponse(writer, "Authorization failed: " + error + (errorDescription != null ? ": " + errorDescription : ""));
            return;
        }
        
        // Get the authorization code
        String code = params.get("code");
        if (code == null || code.isEmpty()) {
            logger.error("Missing authorization code in OAuth2 callback");
            writeErrorResponse(writer, "Missing authorization code");
            return;
        }
        
        try {
            // Exchange the code for tokens
            tokenProvider.processAuthorizationCode(state, code);
            logger.info("Successfully processed authorization code");
            writeSuccessResponse(writer);
        } catch (Exception e) {
            logger.error("Token exchange failed", e);
            writeErrorResponse(writer, "Failed to exchange authorization code for tokens: " + e.getMessage());
        }
    }
    
    /**
     * Renders the success response HTML page.
     * <p>
     * Uses the success Mustache template to generate a user-friendly success page.
     * Falls back to a simple HTML message if template rendering fails.
     * <p>
     * This method is called when the OAuth 2.0 authorization flow completes successfully,
     * indicating that the authorization code has been exchanged for tokens using the
     * correct PKCE code verifier. The success page informs the user that they can close
     * the browser window and return to the application.
     *
     * @param writer The writer to output the HTML response
     */
    private void writeSuccessResponse(PrintWriter writer) {
        if (templatesLoaded) {
            try {
                // Create the context (empty for success as it doesn't have variables)
                Map<String, Object> context = new HashMap<>();
                
                // Execute the template with the context
                successTemplate.execute(writer, context);
                writer.flush();
                return;
            } catch (Exception e) {
                logger.error("Failed to render success template", e);
                // Fall through to fallback
            }
        }
        
        // Fallback to simple message if templates weren't loaded or rendering fails
        writer.println("<html><body><h1>Authorization Successful</h1><p>You can close this window now.</p></body></html>");
    }
    
    /**
     * Renders the error response HTML page.
     * <p>
     * Uses the error Mustache template to generate a user-friendly error page
     * with the specified error message. Falls back to a simple HTML message
     * if template rendering fails.
     * <p>
     * This method is called when there's an error in the OAuth 2.0 authorization flow,
     * such as:
     * <ul>
     *   <li>The user denied the authorization request</li>
     *   <li>The OAuth provider returned an error response</li>
     *   <li>The authorization code exchange failed due to invalid PKCE values</li>
     *   <li>The state parameter was invalid or missing</li>
     * </ul>
     * <p>
     * The error page displays the specific error message to help diagnose and resolve
     * the issue. Common errors include "invalid_grant" when PKCE values are inconsistent
     * between the authorization request and token exchange.
     *
     * @param writer The writer to output the HTML response
     * @param errorMessage The error message to display to the user
     */
    private void writeErrorResponse(PrintWriter writer, String errorMessage) {
        if (templatesLoaded) {
            try {
                // Create context with error message
                Map<String, Object> context = new HashMap<>();
                context.put("errorMessage", errorMessage);
                
                // Execute the template with the context
                errorTemplate.execute(writer, context);
                writer.flush();
                return;
            } catch (Exception e) {
                logger.error("Failed to render error template", e);
                // Fall through to fallback
            }
        }
        
        // Fallback to simple message if templates weren't loaded or rendering fails
        writer.println("<html><body><h1>Authorization Error</h1><p>" + errorMessage + "</p></body></html>");
    }
}
