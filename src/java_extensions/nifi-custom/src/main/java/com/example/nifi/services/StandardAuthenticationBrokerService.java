package com.example.nifi.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.state.Scope;
import org.apache.nifi.components.state.StateManager;
import org.apache.nifi.components.state.StateMap;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Tags({"oauth2", "authentication", "google", "microsoft", "token"})
@CapabilityDescription("OAuth2 Authentication Broker Service that manages authentication for Google and Microsoft APIs. " +
        "Provides a web interface for user login and automatically handles token refresh.")
public class StandardAuthenticationBrokerService extends AbstractControllerService
        implements AuthenticationBrokerService {

    private static final String STATE_KEY_ACCESS_TOKEN = "access_token";
    private static final String STATE_KEY_REFRESH_TOKEN = "refresh_token";
    private static final String STATE_KEY_EXPIRES_AT = "expires_at";
    private static final String STATE_KEY_SCOPE = "scope";
    private static final String STATE_KEY_USER = "authenticated_user";

    // Property Descriptors
    public static final PropertyDescriptor PROVIDER = new PropertyDescriptor.Builder()
            .name("OAuth2 Provider")
            .description("Select the OAuth2 authentication provider")
            .required(true)
            .allowableValues("GOOGLE", "MICROSOFT")
            .defaultValue("GOOGLE")
            .build();

    public static final PropertyDescriptor WEB_SERVER_PORT = new PropertyDescriptor.Builder()
            .name("Web Server Port")
            .description("Port for the authentication web interface")
            .required(true)
            .defaultValue("8888")
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .build();

    public static final PropertyDescriptor GOOGLE_CLIENT_ID = new PropertyDescriptor.Builder()
            .name("Google Client ID")
            .description("OAuth2 Client ID from Google Cloud Console")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor GOOGLE_CLIENT_SECRET = new PropertyDescriptor.Builder()
            .name("Google Client Secret")
            .description("OAuth2 Client Secret from Google Cloud Console")
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor GOOGLE_SCOPES = new PropertyDescriptor.Builder()
            .name("Google Scopes")
            .description("Space-separated OAuth2 scopes (e.g., https://www.googleapis.com/auth/spreadsheets)")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor MICROSOFT_CLIENT_ID = new PropertyDescriptor.Builder()
            .name("Microsoft Client ID")
            .description("Application (client) ID from Azure AD")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor MICROSOFT_CLIENT_SECRET = new PropertyDescriptor.Builder()
            .name("Microsoft Client Secret")
            .description("Client secret from Azure AD")
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor MICROSOFT_TENANT_ID = new PropertyDescriptor.Builder()
            .name("Microsoft Tenant ID")
            .description("Azure AD tenant ID (or 'common' for multi-tenant)")
            .required(false)
            .defaultValue("common")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor MICROSOFT_SCOPES = new PropertyDescriptor.Builder()
            .name("Microsoft Scopes")
            .description("Space-separated OAuth2 scopes (e.g., https://graph.microsoft.com/Files.ReadWrite)")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor TOKEN_REFRESH_BUFFER = new PropertyDescriptor.Builder()
            .name("Token Refresh Buffer")
            .description("Seconds before expiration to refresh token automatically")
            .required(true)
            .defaultValue("300")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    public static final PropertyDescriptor ENABLE_PKCE = new PropertyDescriptor.Builder()
            .name("Enable PKCE")
            .description("Enable Proof Key for Code Exchange for enhanced security")
            .required(true)
            .defaultValue("true")
            .allowableValues("true", "false")
            .build();

    private static final List<PropertyDescriptor> PROPERTIES = List.of(
            PROVIDER,
            WEB_SERVER_PORT,
            GOOGLE_CLIENT_ID,
            GOOGLE_CLIENT_SECRET,
            GOOGLE_SCOPES,
            MICROSOFT_CLIENT_ID,
            MICROSOFT_CLIENT_SECRET,
            MICROSOFT_TENANT_ID,
            MICROSOFT_SCOPES,
            TOKEN_REFRESH_BUFFER,
            ENABLE_PKCE
    );

    private Server server;
    private OAuth2Provider provider;
    private String clientId;
    private String clientSecret;
    private String scopes;
    private String tenantId;
    private int webServerPort;
    private int tokenRefreshBuffer;
    private boolean enablePkce;
    private volatile OAuth2Token currentToken;
    private final ReentrantLock tokenRefreshLock = new ReentrantLock();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();
    private ScheduledExecutorService tokenRefreshScheduler;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        List<ValidationResult> results = new ArrayList<>();

        String providerValue = validationContext.getProperty(PROVIDER).getValue();

        if ("GOOGLE".equals(providerValue)) {
            if (!validationContext.getProperty(GOOGLE_CLIENT_ID).isSet()) {
                results.add(new ValidationResult.Builder()
                        .subject("Google Client ID")
                        .valid(false)
                        .explanation("Google Client ID is required when Google provider is selected")
                        .build());
            }
            if (!validationContext.getProperty(GOOGLE_CLIENT_SECRET).isSet()) {
                results.add(new ValidationResult.Builder()
                        .subject("Google Client Secret")
                        .valid(false)
                        .explanation("Google Client Secret is required when Google provider is selected")
                        .build());
            }
            if (!validationContext.getProperty(GOOGLE_SCOPES).isSet()) {
                results.add(new ValidationResult.Builder()
                        .subject("Google Scopes")
                        .valid(false)
                        .explanation("Google Scopes is required when Google provider is selected")
                        .build());
            }
        } else if ("MICROSOFT".equals(providerValue)) {
            if (!validationContext.getProperty(MICROSOFT_CLIENT_ID).isSet()) {
                results.add(new ValidationResult.Builder()
                        .subject("Microsoft Client ID")
                        .valid(false)
                        .explanation("Microsoft Client ID is required when Microsoft provider is selected")
                        .build());
            }
            if (!validationContext.getProperty(MICROSOFT_CLIENT_SECRET).isSet()) {
                results.add(new ValidationResult.Builder()
                        .subject("Microsoft Client Secret")
                        .valid(false)
                        .explanation("Microsoft Client Secret is required when Microsoft provider is selected")
                        .build());
            }
            if (!validationContext.getProperty(MICROSOFT_SCOPES).isSet()) {
                results.add(new ValidationResult.Builder()
                        .subject("Microsoft Scopes")
                        .valid(false)
                        .explanation("Microsoft Scopes is required when Microsoft provider is selected")
                        .build());
            }
        }

        return results;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws Exception {
        provider = OAuth2Provider.valueOf(context.getProperty(PROVIDER).getValue());
        webServerPort = context.getProperty(WEB_SERVER_PORT).asInteger();
        tokenRefreshBuffer = context.getProperty(TOKEN_REFRESH_BUFFER).asInteger();
        enablePkce = context.getProperty(ENABLE_PKCE).asBoolean();

        if (provider == OAuth2Provider.GOOGLE) {
            clientId = context.getProperty(GOOGLE_CLIENT_ID).evaluateAttributeExpressions().getValue();
            clientSecret = context.getProperty(GOOGLE_CLIENT_SECRET).evaluateAttributeExpressions().getValue();
            scopes = context.getProperty(GOOGLE_SCOPES).evaluateAttributeExpressions().getValue();
        } else if (provider == OAuth2Provider.MICROSOFT) {
            clientId = context.getProperty(MICROSOFT_CLIENT_ID).evaluateAttributeExpressions().getValue();
            clientSecret = context.getProperty(MICROSOFT_CLIENT_SECRET).evaluateAttributeExpressions().getValue();
            scopes = context.getProperty(MICROSOFT_SCOPES).evaluateAttributeExpressions().getValue();
            tenantId = context.getProperty(MICROSOFT_TENANT_ID).evaluateAttributeExpressions().getValue();
        }

        // Load existing tokens from StateManager
        loadTokenFromState();

        // Start web server
        startWebServer();

        // Start background token refresh scheduler
        startTokenRefreshScheduler();

        getLogger().info("StandardAuthenticationBrokerService enabled for {} on port {}",
                provider.getDisplayName(), webServerPort);
    }

    @OnDisabled
    public void onDisabled() {
        stopTokenRefreshScheduler();
        stopWebServer();
        getLogger().info("StandardAuthenticationBrokerService disabled");
    }

    @Override
    public String getAccessToken() throws TokenExpiredException {
        if (currentToken == null) {
            throw new TokenExpiredException("Not authenticated. Please log in via the web interface at http://localhost:" + webServerPort);
        }

        if (currentToken.isExpiringSoon(tokenRefreshBuffer)) {
            try {
                return refreshToken();
            } catch (AuthenticationException e) {
                throw new TokenExpiredException("Failed to refresh expired token", e);
            }
        }

        return currentToken.getAccessToken();
    }

    @Override
    public boolean isAuthenticated() {
        return currentToken != null && !currentToken.isExpired();
    }

    @Override
    public String getAuthenticatedUser() {
        return currentToken != null ? currentToken.getAuthenticatedUser() : null;
    }

    @Override
    public long getTokenExpirationTime() {
        return currentToken != null ? currentToken.getExpiresAt() : 0;
    }

    @Override
    public String refreshToken() throws AuthenticationException {
        if (currentToken == null || currentToken.getRefreshToken() == null) {
            throw new AuthenticationException("No refresh token available");
        }

        tokenRefreshLock.lock();
        try {
            // Double-check if token still needs refresh
            if (!currentToken.isExpiringSoon(tokenRefreshBuffer)) {
                return currentToken.getAccessToken();
            }

            String tokenEndpoint = provider == OAuth2Provider.GOOGLE
                    ? provider.getTokenEndpoint()
                    : provider.getTokenEndpoint(tenantId);

            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("client_id", clientId));
            params.add(new BasicNameValuePair("client_secret", clientSecret));
            params.add(new BasicNameValuePair("refresh_token", currentToken.getRefreshToken()));
            params.add(new BasicNameValuePair("grant_type", "refresh_token"));

            JsonNode response = executeTokenRequest(tokenEndpoint, params);

            String newAccessToken = response.get("access_token").asText();
            String newRefreshToken = response.has("refresh_token")
                    ? response.get("refresh_token").asText()
                    : currentToken.getRefreshToken();
            long expiresIn = response.get("expires_in").asLong();
            long expiresAt = System.currentTimeMillis() + (expiresIn * 1000);

            currentToken = new OAuth2Token(
                    newAccessToken,
                    newRefreshToken,
                    expiresAt,
                    currentToken.getScope(),
                    currentToken.getAuthenticatedUser()
            );

            saveTokenToState();
            getLogger().info("Successfully refreshed access token for {}", currentToken.getAuthenticatedUser());

            return newAccessToken;
        } catch (Exception e) {
            throw new AuthenticationException("Failed to refresh token", e);
        } finally {
            tokenRefreshLock.unlock();
        }
    }

    @Override
    public void revokeAuthentication() {
        if (currentToken != null && provider.supportsRevocation()) {
            try {
                revokeTokenWithProvider(currentToken.getAccessToken());
            } catch (Exception e) {
                getLogger().warn("Failed to revoke token with provider", e);
            }
        }

        currentToken = null;
        clearTokenFromState();
        getLogger().info("Authentication revoked");
    }

    private void startWebServer() throws Exception {
        server = new Server(webServerPort);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");

        context.addServlet(new ServletHolder(new LoginServlet()), "/");
        context.addServlet(new ServletHolder(new AuthRedirectServlet()), "/auth/redirect");
        context.addServlet(new ServletHolder(new CallbackServlet()), "/callback");
        context.addServlet(new ServletHolder(new StatusServlet()), "/status");
        context.addServlet(new ServletHolder(new LogoutServlet()), "/logout");

        server.setHandler(context);
        server.start();

        getLogger().info("Web server started on port {}", webServerPort);
    }

    private void stopWebServer() {
        if (server != null) {
            try {
                server.stop();
                getLogger().info("Web server stopped");
            } catch (Exception e) {
                getLogger().error("Error stopping web server", e);
            }
        }
    }

    private void startTokenRefreshScheduler() {
        // Create a scheduled executor service with a single thread
        tokenRefreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "OAuth2-Token-Refresh");
            thread.setDaemon(true);
            return thread;
        });

        // Schedule token refresh task to run every minute
        // This checks if the token is expiring soon and refreshes it proactively
        tokenRefreshScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (currentToken != null && currentToken.isExpiringSoon(tokenRefreshBuffer)) {
                    getLogger().info("Token expiring soon for user {}. Proactively refreshing...",
                            currentToken.getAuthenticatedUser());
                    refreshToken();
                    getLogger().info("Token successfully refreshed for user {}",
                            currentToken.getAuthenticatedUser());
                }
            } catch (Exception e) {
                getLogger().error("Background token refresh failed for user {}: {}",
                        currentToken != null ? currentToken.getAuthenticatedUser() : "unknown",
                        e.getMessage(), e);
            }
        }, 60, 60, TimeUnit.SECONDS);  // Start after 60 seconds, then run every 60 seconds

        getLogger().info("Token refresh scheduler started (checking every 60 seconds)");
    }

    private void stopTokenRefreshScheduler() {
        if (tokenRefreshScheduler != null) {
            try {
                getLogger().info("Stopping token refresh scheduler...");
                tokenRefreshScheduler.shutdown();
                if (!tokenRefreshScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    tokenRefreshScheduler.shutdownNow();
                    getLogger().warn("Token refresh scheduler did not terminate gracefully, forced shutdown");
                } else {
                    getLogger().info("Token refresh scheduler stopped");
                }
            } catch (InterruptedException e) {
                tokenRefreshScheduler.shutdownNow();
                Thread.currentThread().interrupt();
                getLogger().error("Error stopping token refresh scheduler", e);
            }
        }
    }

    private void loadTokenFromState() {
        try {
            StateManager stateManager = getStateManager();
            StateMap stateMap = stateManager.getState(Scope.LOCAL);

            Map<String, String> state = stateMap.toMap();
            if (state.isEmpty() || !state.containsKey(STATE_KEY_ACCESS_TOKEN)) {
                getLogger().info("No existing token found in state");
                return;
            }

            currentToken = new OAuth2Token(
                    state.get(STATE_KEY_ACCESS_TOKEN),
                    state.get(STATE_KEY_REFRESH_TOKEN),
                    Long.parseLong(state.get(STATE_KEY_EXPIRES_AT)),
                    state.get(STATE_KEY_SCOPE),
                    state.get(STATE_KEY_USER)
            );
            getLogger().info("Loaded existing token for {} from state", currentToken.getAuthenticatedUser());
        } catch (Exception e) {
            getLogger().error("Failed to load token from state", e);
        }
    }

    private void saveTokenToState() {
        if (currentToken == null) {
            return;
        }

        try {
            StateManager stateManager = getStateManager();
            Map<String, String> state = new HashMap<>();
            state.put(STATE_KEY_ACCESS_TOKEN, currentToken.getAccessToken());
            state.put(STATE_KEY_REFRESH_TOKEN, currentToken.getRefreshToken());
            state.put(STATE_KEY_EXPIRES_AT, String.valueOf(currentToken.getExpiresAt()));
            state.put(STATE_KEY_SCOPE, currentToken.getScope());
            state.put(STATE_KEY_USER, currentToken.getAuthenticatedUser());

            stateManager.setState(state, Scope.LOCAL);
            getLogger().debug("Saved token to state");
        } catch (Exception e) {
            getLogger().error("Failed to save token to state", e);
        }
    }

    private void clearTokenFromState() {
        try {
            StateManager stateManager = getStateManager();
            stateManager.clear(Scope.LOCAL);
            getLogger().debug("Cleared token from state");
        } catch (Exception e) {
            getLogger().error("Failed to clear token from state", e);
        }
    }

    private JsonNode executeTokenRequest(String endpoint, List<NameValuePair> params) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(endpoint);
            post.setEntity(new UrlEncodedFormEntity(params));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                String responseBody = EntityUtils.toString(response.getEntity());

                if (response.getCode() != 200) {
                    throw new IOException("Token request failed with status " + response.getCode() + ": " + responseBody);
                }

                return objectMapper.readTree(responseBody);
            } catch (ParseException e) {
                throw new IOException("Failed to parse response", e);
            }
        }
    }

    private void revokeTokenWithProvider(String token) throws IOException {
        if (!provider.supportsRevocation()) {
            return;
        }

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("token", token));

            HttpPost post = new HttpPost(provider.getRevocationEndpoint());
            post.setEntity(new UrlEncodedFormEntity(params));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                if (response.getCode() != 200) {
                    getLogger().warn("Token revocation returned status {}", response.getCode());
                }
            }
        }
    }

    private String generateRandomString(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate code challenge", e);
        }
    }

    private String extractUserFromIdToken(String idToken) {
        try {
            DecodedJWT jwt = JWT.decode(idToken);
            String email = jwt.getClaim("email").asString();
            if (email != null) {
                return email;
            }
            String name = jwt.getClaim("name").asString();
            if (name != null) {
                return name;
            }
            String sub = jwt.getClaim("sub").asString();
            return sub != null ? sub : "Unknown User";
        } catch (Exception e) {
            getLogger().warn("Failed to extract user from ID token", e);
            return "Unknown User";
        }
    }

    private String extractUserFromToken(String accessToken) {
        // For Google, call the userinfo endpoint
        if (provider == OAuth2Provider.GOOGLE) {
            return fetchGoogleUserInfo(accessToken);
        }

        // For Microsoft, try to decode the JWT
        try {
            DecodedJWT jwt = JWT.decode(accessToken);
            String email = jwt.getClaim("email").asString();
            if (email != null) {
                return email;
            }
            String upn = jwt.getClaim("upn").asString();
            if (upn != null) {
                return upn;
            }
            String sub = jwt.getClaim("sub").asString();
            return sub != null ? sub : "Unknown User";
        } catch (Exception e) {
            getLogger().warn("Failed to extract user from token", e);
            return "Unknown User";
        }
    }

    private String fetchGoogleUserInfo(String accessToken) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            org.apache.hc.client5.http.classic.methods.HttpGet get =
                new org.apache.hc.client5.http.classic.methods.HttpGet("https://www.googleapis.com/oauth2/v2/userinfo");
            get.setHeader("Authorization", "Bearer " + accessToken);

            try (CloseableHttpResponse response = httpClient.execute(get)) {
                String responseBody = EntityUtils.toString(response.getEntity());

                if (response.getCode() == 200) {
                    JsonNode userInfo = objectMapper.readTree(responseBody);
                    String email = userInfo.has("email") ? userInfo.get("email").asText() : null;
                    if (email != null) {
                        return email;
                    }
                    String name = userInfo.has("name") ? userInfo.get("name").asText() : null;
                    if (name != null) {
                        return name;
                    }
                }
                getLogger().warn("Failed to fetch Google user info: {}", responseBody);
                return "Unknown User";
            } catch (ParseException e) {
                getLogger().warn("Failed to parse Google userinfo response", e);
                return "Unknown User";
            }
        } catch (Exception e) {
            getLogger().warn("Failed to fetch Google user info", e);
            return "Unknown User";
        }
    }

    // Servlet Classes

    private class LoginServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/html");

            if (isAuthenticated()) {
                resp.getWriter().println("<html><body>");
                resp.getWriter().println("<h1>Authentication Status</h1>");
                resp.getWriter().println("<p>Already authenticated as: <strong>" + getAuthenticatedUser() + "</strong></p>");
                resp.getWriter().println("<p><a href='/status'>View Status</a> | <a href='/logout'>Logout</a></p>");
                resp.getWriter().println("</body></html>");
            } else {
                resp.getWriter().println("<html><body>");
                resp.getWriter().println("<h1>" + provider.getDisplayName() + " OAuth2 Authentication</h1>");
                resp.getWriter().println("<p>Click the button below to authenticate with " + provider.getDisplayName() + "</p>");
                resp.getWriter().println("<form action='/auth/redirect' method='get'>");
                resp.getWriter().println("<button type='submit'>Login with " + provider.getDisplayName() + "</button>");
                resp.getWriter().println("</form>");
                resp.getWriter().println("</body></html>");
            }
        }
    }

    private class AuthRedirectServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            HttpSession session = req.getSession(true);

            String state = generateRandomString(32);
            session.setAttribute("oauth_state", state);

            String codeVerifier = null;
            String codeChallenge = null;

            if (enablePkce) {
                codeVerifier = generateRandomString(43);
                codeChallenge = generateCodeChallenge(codeVerifier);
                session.setAttribute("code_verifier", codeVerifier);
            }

            String redirectUri = "http://localhost:" + webServerPort + "/callback";

            StringBuilder authUrl = new StringBuilder();
            if (provider == OAuth2Provider.GOOGLE) {
                authUrl.append(provider.getAuthorizationEndpoint());
            } else {
                authUrl.append(provider.getAuthorizationEndpoint(tenantId));
            }

            // For Google, ensure openid and email scopes are included to get ID token
            String effectiveScopes = scopes;
            if (provider == OAuth2Provider.GOOGLE) {
                if (!scopes.contains("openid")) {
                    effectiveScopes = "openid email " + scopes;
                } else if (!scopes.contains("email")) {
                    effectiveScopes = "email " + scopes;
                }
            }

            authUrl.append("?client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
            authUrl.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
            authUrl.append("&response_type=code");
            authUrl.append("&scope=").append(URLEncoder.encode(effectiveScopes, StandardCharsets.UTF_8));
            authUrl.append("&state=").append(state);

            if (enablePkce) {
                authUrl.append("&code_challenge=").append(codeChallenge);
                authUrl.append("&code_challenge_method=S256");
            }

            if (provider == OAuth2Provider.GOOGLE) {
                authUrl.append("&access_type=offline");
                authUrl.append("&prompt=consent");
            }

            resp.sendRedirect(authUrl.toString());
        }
    }

    private class CallbackServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            HttpSession session = req.getSession(false);

            if (session == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Session expired");
                return;
            }

            String state = req.getParameter("state");
            String storedState = (String) session.getAttribute("oauth_state");

            if (state == null || !state.equals(storedState)) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid state parameter");
                return;
            }

            String code = req.getParameter("code");
            if (code == null) {
                String error = req.getParameter("error");
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Authorization failed: " + error);
                return;
            }

            try {
                String tokenEndpoint = provider == OAuth2Provider.GOOGLE
                        ? provider.getTokenEndpoint()
                        : provider.getTokenEndpoint(tenantId);

                List<NameValuePair> params = new ArrayList<>();
                params.add(new BasicNameValuePair("client_id", clientId));
                params.add(new BasicNameValuePair("client_secret", clientSecret));
                params.add(new BasicNameValuePair("code", code));
                params.add(new BasicNameValuePair("redirect_uri", "http://localhost:" + webServerPort + "/callback"));
                params.add(new BasicNameValuePair("grant_type", "authorization_code"));

                if (enablePkce) {
                    String codeVerifier = (String) session.getAttribute("code_verifier");
                    params.add(new BasicNameValuePair("code_verifier", codeVerifier));
                }

                JsonNode response = executeTokenRequest(tokenEndpoint, params);

                String accessToken = response.get("access_token").asText();
                String refreshToken = response.has("refresh_token") ? response.get("refresh_token").asText() : null;
                long expiresIn = response.get("expires_in").asLong();
                long expiresAt = System.currentTimeMillis() + (expiresIn * 1000);
                String scope = response.has("scope") ? response.get("scope").asText() : scopes;

                // Extract user from ID token if available (Google provides this)
                String authenticatedUser = "Unknown User";
                if (response.has("id_token")) {
                    authenticatedUser = extractUserFromIdToken(response.get("id_token").asText());
                }
                if ("Unknown User".equals(authenticatedUser)) {
                    authenticatedUser = extractUserFromToken(accessToken);
                }

                currentToken = new OAuth2Token(accessToken, refreshToken, expiresAt, scope, authenticatedUser);
                saveTokenToState();

                session.removeAttribute("oauth_state");
                session.removeAttribute("code_verifier");

                getLogger().info("Successfully authenticated user: {}", authenticatedUser);

                resp.sendRedirect("/status");
            } catch (Exception e) {
                getLogger().error("Failed to exchange authorization code for token", e);
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication failed: " + e.getMessage());
            }
        }
    }

    private class StatusServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/html");

            if (!isAuthenticated()) {
                resp.getWriter().println("<html><body>");
                resp.getWriter().println("<h1>Not Authenticated</h1>");
                resp.getWriter().println("<p><a href='/'>Login</a></p>");
                resp.getWriter().println("</body></html>");
                return;
            }

            long expiresAt = getTokenExpirationTime();
            Date expirationDate = new Date(expiresAt);

            resp.getWriter().println("<html><body>");
            resp.getWriter().println("<h1>Authentication Status</h1>");
            resp.getWriter().println("<p><strong>Provider:</strong> " + provider.getDisplayName() + "</p>");
            resp.getWriter().println("<p><strong>Authenticated User:</strong> " + getAuthenticatedUser() + "</p>");
            resp.getWriter().println("<p><strong>Token Expires:</strong> " + expirationDate + "</p>");
            resp.getWriter().println("<p><strong>Scopes:</strong> " + currentToken.getScope() + "</p>");
            resp.getWriter().println("<form action='/logout' method='post'>");
            resp.getWriter().println("<button type='submit'>Logout</button>");
            resp.getWriter().println("</form>");
            resp.getWriter().println("</body></html>");
        }
    }

    private class LogoutServlet extends HttpServlet {
        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            revokeAuthentication();

            resp.setContentType("text/html");
            resp.getWriter().println("<html><body>");
            resp.getWriter().println("<h1>Logged Out</h1>");
            resp.getWriter().println("<p>You have been successfully logged out.</p>");
            resp.getWriter().println("<p><a href='/'>Login Again</a></p>");
            resp.getWriter().println("</body></html>");
        }
    }
}
