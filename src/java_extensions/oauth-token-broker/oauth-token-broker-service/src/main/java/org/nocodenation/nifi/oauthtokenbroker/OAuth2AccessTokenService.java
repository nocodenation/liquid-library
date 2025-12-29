package org.nocodenation.nifi.oauthtokenbroker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.nocodenation.nifi.oauthtokenbroker.PkceUtils.PkceValues;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A NiFi Controller Service that provides OAuth 2.0 access token management for use with HTTP-based APIs.
 * <p>
 * This service implements the complete OAuth 2.0 authorization code flow with PKCE (Proof Key for Code Exchange)
 * according to RFC 7636 for enhanced security against authorization code interception attacks. The service
 * handles all aspects of the OAuth 2.0 flow, including:
 * <ul>
 *   <li>Authorization URL generation with proper PKCE parameters</li>
 *   <li>Local callback server for handling authorization code redirects</li>
 *   <li>Token acquisition using the authorization code and PKCE code verifier</li>
 *   <li>Token storage and automatic refresh when tokens expire</li>
 * </ul>
 * <p>
 * The service maintains consistent PKCE values throughout the entire OAuth 2.0 authorization flow:
 * <ol>
 *   <li>The code challenge is sent with the initial authorization request</li>
 *   <li>The code verifier is sent when exchanging the authorization code for tokens</li>
 * </ol>
 * <p>
 * According to RFC 7636, the code verifier must be a random string between 43-128 characters
 * containing only the characters: A-Z, a-z, 0-9, "-", ".", "_", "~".
 * <p>
 * This implementation supports various OAuth 2.0 providers including Google, Microsoft, and other standard
 * OAuth 2.0 implementations. It can be configured with different authorization and token endpoints, client
 * credentials, and scopes to support a wide range of APIs.
 * <p>
 * <strong>Multi-Processor Support:</strong>
 * This service supports multiple processors sharing a single controller service instance by using
 * processor-specific methods that take a processorId parameter. Each processor can maintain its own
 * OAuth state and tokens while sharing the same service configuration and callback server.
 * <p>
 * Usage:
 * <ol>
 *   <li>Configure the service with appropriate OAuth 2.0 endpoints and credentials</li>
 *   <li>Enable the service, which will start a local callback server</li>
 *   <li>Obtain the authorization URL from {@link #getAuthorizationUrl()} or {@link #getAuthorizationUrl(String)}</li>
 *   <li>Direct the user to visit this URL in a browser to grant permission</li>
 *   <li>The callback server will automatically process the authorization code</li>
 *   <li>Once authorized, tokens can be obtained using {@link #getAccessToken()} or {@link #getAccessToken(String)}</li>
 * </ol>
 * 
 * @see OAuth2AccessTokenProvider
 * @see AccessToken
 * @see AccessTokenAcquisitionException
 */
@Tags({"oauth", "oauth2", "authentication", "authorization", "token", "security", "http", "web", "api", "nocodenation"})
@CapabilityDescription("Provides OAuth 2.0 access tokens for use in HTTP requests. This service handles the complete " +
        "OAuth 2.0 authorization flow, including authorization code exchange, token refresh, and token storage. " +
        "It always uses PKCE (Proof Key for Code Exchange) for enhanced security according to RFC 7636. " +
        "PKCE values are generated once during service enablement and reused consistently throughout the entire OAuth flow. " +
        "The service supports multiple processors sharing a single controller service instance through processor-specific methods. " +
        "It can be used with various OAuth 2.0 providers including Google, Microsoft, and other standard " +
        "OAuth 2.0 implementations. Once authorized, the service manages token lifecycle including automatic " +
        "refreshing of expired tokens.")
public class OAuth2AccessTokenService extends AbstractControllerService implements OAuth2AccessTokenProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2AccessTokenService.class);
    
    /**
     * Property descriptor for the OAuth 2.0 authorization endpoint URL.
     * <p>
     * This is the URL where users will be redirected to grant permission to the application.
     * It initiates the OAuth 2.0 authorization flow and should be configured according to
     * the specific OAuth provider being used.
     */
    public static final PropertyDescriptor AUTHORIZATION_URL = new PropertyDescriptor.Builder()
            .name("Authorization URL")
            .description("The OAuth 2.0 authorization endpoint URL where users will be redirected to grant permission. " +
                    "This is the URL that initiates the OAuth 2.0 authorization flow. For Google, use " +
                    "https://accounts.google.com/o/oauth2/v2/auth. For Microsoft, use " +
                    "https://login.microsoftonline.com/common/oauth2/v2.0/authorize. Ensure the URL is correctly " +
                    "formatted and accessible.")
            .required(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    /**
     * Property descriptor for the OAuth 2.0 token endpoint URL.
     * <p>
     * This is the URL used to exchange authorization codes for access tokens and to refresh
     * expired tokens. It should be configured according to the specific OAuth provider being used.
     */
    public static final PropertyDescriptor TOKEN_URL = new PropertyDescriptor.Builder()
            .name("Token URL")
            .description("The OAuth 2.0 token endpoint URL used to exchange authorization codes for access tokens " +
                    "and to refresh expired tokens. For Google, use https://oauth2.googleapis.com/token. For Microsoft, " +
                    "use https://login.microsoftonline.com/common/oauth2/v2.0/token. Ensure the URL is correctly " +
                    "formatted and accessible.")
            .required(true)
            .addValidator(StandardValidators.URL_VALIDATOR)
            .build();

    /**
     * Property descriptor for the OAuth 2.0 client identifier.
     * <p>
     * This is the client ID issued to your application by the authorization server when
     * registering your application with the OAuth provider. It uniquely identifies your
     * application to the authorization server.
     */
    public static final PropertyDescriptor CLIENT_ID = new PropertyDescriptor.Builder()
            .name("Client ID")
            .description("The OAuth 2.0 client identifier issued to your application by the authorization server. " +
                    "This is obtained when registering your application with the OAuth provider. This value uniquely " +
                    "identifies your application to the authorization server. Keep this value confidential.")
            .sensitive(true)
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * Property descriptor for the OAuth 2.0 client secret.
     * <p>
     * This is the client secret issued to your application by the authorization server when
     * registering your application with the OAuth provider. It is used to authenticate your
     * application to the authorization server and should be kept confidential.
     */
    public static final PropertyDescriptor CLIENT_SECRET = new PropertyDescriptor.Builder()
            .name("Client Secret")
            .description("The OAuth 2.0 client secret issued to your application by the authorization server. " +
                    "This is obtained when registering your application with the OAuth provider. Keep this value " +
                    "confidential as it is used to authenticate your application to the authorization server. " +
                    "This is optional for some OAuth providers that support public clients.")
            .required(false)
            .sensitive(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    /**
     * Property descriptor for the OAuth 2.0 scopes.
     * <p>
     * Scopes define the access privileges being requested from the OAuth provider.
     * They are space-separated strings that vary depending on the OAuth provider and
     * the APIs being accessed.
     */
    public static final PropertyDescriptor SCOPE = new PropertyDescriptor.Builder()
            .name("Scope")
            .description("Space-separated list of OAuth 2.0 scopes that define the access privileges being requested. " +
                    "The 'openid' scope is required for OpenID Connect authentication. Other common scopes include: " +
                    "'email', 'profile', 'https://www.googleapis.com/auth/drive' (for Google Drive access), etc. " +
                    "The available scopes depend on the OAuth provider and the APIs you're accessing. Ensure the scopes " +
                    "are correctly formatted and supported by the provider.")
            .defaultValue("openid") 
            .required(true)
            .addValidator(new OAuth2ScopeValidator())
            .build();

    /**
     * Property descriptor for the port on which the service listens for OAuth 2.0 callbacks.
     * <p>
     * This is the port on which the service will listen for OAuth 2.0 callbacks. Ensure that this port is available
     * and not blocked by firewalls. The service will automatically start a local web server to handle callbacks on
     * this port.
     */
    public static final PropertyDescriptor LISTEN_PORT = new PropertyDescriptor.Builder()
            .name("Listen Port")
            .description("The port on which the service will listen for OAuth 2.0 callbacks. " +
                    "Ensure that this port is available and not blocked by firewalls. " +
                    "The service will automatically start a local web server to handle callbacks on this port.")
            .required(true)
            .defaultValue("8080")
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .build();

    /**
     * Property descriptor for the external redirect URI when behind a load balancer.
     * <p>
     * This URI is used in the authorization URL when the service is behind a load balancer,
     * while the service still listens on the local port for callbacks.
     */
    public static final PropertyDescriptor REDIRECT_URI = new PropertyDescriptor.Builder()
            .name("Redirect URI")
            .description("The external URI that the OAuth 2.0 server will redirect to after authorization " +
                    "when the service is behind a load balancer or proxy. This URI should be publicly accessible " +
                    "and configured to forward requests to the local callback server. " +
                    "If not behind a load balancer, this is also used to determine the callback path.")
            .addValidator(StandardValidators.URI_VALIDATOR)
            .required(true)
            .defaultValue("http://localhost:8080/oauth/callback")
            .build();

    /**
     * Property descriptor for the token refresh window.
     * <p>
     * This is the number of seconds before an access token expires when a refresh should be attempted.
     * It creates a buffer to ensure a new token is obtained before the current one expires.
     */
    public static final PropertyDescriptor TOKEN_REFRESH_WINDOW = new PropertyDescriptor.Builder()
            .name("Token Refresh Window")
            .description("The number of seconds before an access token expires when a refresh should be attempted. " +
                    "This creates a buffer to ensure a new token is obtained before the current one expires. " +
                    "For example, if set to 300 (5 minutes) and a token expires in 1 hour, the service will " +
                    "attempt to refresh the token after 55 minutes of use. A larger value provides more buffer " +
                    "against network latency but may result in shorter effective token lifetimes.")
            .required(true)
            .defaultValue("300")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    /**
     * Property descriptor for the SSL Context Service.
     * <p>
     * This service provides SSL context for secure communications with the callback server.
     * When configured, the callback server will use HTTPS instead of HTTP.
     */
    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .description("The SSL Context Service to use for secure communications with the callback server. " +
                    "If specified, the callback server will use HTTPS instead of HTTP.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .build();

    /**
     * Property descriptor for additional OAuth 2.0 authorization parameters.
     * <p>
     * These parameters will be added to the authorization URL. The format should be key=value pairs,
     * with each pair separated by an ampersand (&). For example: "access_type=offline&prompt=consent"
     * for Google OAuth to request offline access and force the consent screen to appear.
     */
    public static final PropertyDescriptor ADDITIONAL_PARAMETERS = new PropertyDescriptor.Builder()
            .name("Additional Parameters")
            .description("Additional parameters to include in the authorization URL. Format as key=value pairs " +
                    "separated by ampersands (&). For example: 'access_type=offline&prompt=consent' for Google OAuth " +
                    "to request offline access and force the consent screen to appear. These parameters will be " +
                    "URL-encoded automatically.")
            .required(false)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .build();

    private static final List<PropertyDescriptor> properties = Collections.unmodifiableList(Arrays.asList(
            AUTHORIZATION_URL,
            TOKEN_URL,
            CLIENT_ID,
            CLIENT_SECRET,
            SCOPE,
            LISTEN_PORT,
            REDIRECT_URI,
            TOKEN_REFRESH_WINDOW,
            SSL_CONTEXT_SERVICE
            // ADDITIONAL_PARAMETERS
    ));

    // Map to store PKCE values per processor or service ID
    private static final ConcurrentHashMap<String, PkceValues> pkceValuesMap = new ConcurrentHashMap<>();

    // Map to store access token values per processor or service ID
    private static final ConcurrentHashMap<String, AtomicReference<AccessToken>> accessTokenMap = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, String> requestedScopes = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, ReentrantLock> tockenLocks = new ConcurrentHashMap<>();
    
    /** JSON object mapper for parsing token responses */
    protected final ObjectMapper objectMapper = new ObjectMapper();
    
    /** HTTP client for making token requests */
    protected CloseableHttpClient httpClient;
    
    /** The authorization endpoint URL */
    protected volatile String authorizationUrl;
    
    /** The token endpoint URL */
    protected volatile String tokenUrl;
    
    /** The OAuth client ID */
    protected volatile String clientId;
    
    /** The OAuth client secret */
    protected volatile String clientSecret;
    
    /** The requested OAuth scopes */
    protected volatile String scope;
    
    /** The port on which the callback server listens */
    protected volatile int listenPort;
    
    /** The external redirect URI when behind a load balancer */
    protected volatile String redirectUri;
    
    /** The callback path extracted from the external redirect URI */
    protected volatile String callbackPath;
    
    /** The number of seconds before token expiry to attempt refresh */
    protected volatile int tokenRefreshWindow;
    
    /** Additional parameters to include in the authorization URL */
    protected volatile String additionalParameters;
    
    /** Callback server for handling OAuth redirects */
    private OAuth2CallbackServer callbackServer;

    /**
     * Returns a list of the processor's supported property descriptors.
     *
     * @return a List of PropertyDescriptor objects supported by this processor
     */
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    /**
     * Initializes the controller service when it is enabled.
     * <p>
     * This method:
     * <ol>
     *   <li>Reads and stores all property values</li>
     *   <li>Initializes the HTTP client</li>
     *   <li>Registers this provider with the callback servlet</li>
     *   <li>Starts the callback server to handle OAuth redirects</li>
     *   <li>Generates PKCE values for enhanced security</li>
     * </ol>
     * <p>
     * PKCE (Proof Key for Code Exchange) values are generated once during service enablement
     * and reused consistently throughout the entire OAuth flow to prevent authorization code
     * interception attacks.
     *
     * @param context the configuration context
     */
    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        LOGGER.debug("Enabling OAuth2 token provider");
        
        try {
            clientId = context.getProperty(CLIENT_ID).getValue();
            clientSecret = context.getProperty(CLIENT_SECRET).getValue();
            
            String tokenEndpoint = context.getProperty(TOKEN_URL).getValue();
            String authEndpoint = context.getProperty(AUTHORIZATION_URL).getValue();
            listenPort = context.getProperty(LISTEN_PORT).asInteger();
            redirectUri = context.getProperty(REDIRECT_URI).getValue();
            
            authorizationUrl = authEndpoint;
            tokenUrl = tokenEndpoint;
            
            scope = context.getProperty(SCOPE).getValue();
            
            tokenRefreshWindow = context.getProperty(TOKEN_REFRESH_WINDOW).asInteger();
            additionalParameters = context.getProperty(ADDITIONAL_PARAMETERS).getValue();
            
            httpClient = HttpClientBuilder.create().build();

            // Remove Token if scopes are changed and store requested scope
            String combinedScope = combineScopes(scope);
            String state = getServiceState();
            boolean scopeChanged = requestedScopesChanged(state, combinedScope);
            LOGGER.debug("Scope comparison result for state {}: scopeChanged={}, current scopes='{}', new scopes='{}'", state, scopeChanged, getRequestedScopes(state), combinedScope);
            
            if (scopeChanged) {
                LOGGER.debug("Scopes changed for state {}, resetting token", state);
                setTokenForState(state, null);
            } else {
                LOGGER.debug("Scopes unchanged for state {}, keeping existing token", state);
            }
            setRequestedScopes(state, combinedScope);
                
            
            try {
                // Extract port from the listenPort property
                int port = Integer.parseInt(context.getProperty(LISTEN_PORT).getValue());
                
                // Use the path from redirectUri if behind a load balancer, otherwise use default path
                String callbackPath = "/oauth/callback";
                if (redirectUri != null && redirectUri.trim().length() > 0) {
                    try {
                        URI externalUri = new URI(redirectUri);
                        if (externalUri.getPath() != null && !externalUri.getPath().isEmpty()) {
                            callbackPath = externalUri.getPath();
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to parse external redirect URI path, using default: {}", e.getMessage());
                    }
                }
                
                SSLContextService sslContextService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
                
                callbackServer = new OAuth2CallbackServer(this, port, callbackPath, getLogger(), sslContextService);
                callbackServer.start();
                LOGGER.debug("OAuth2 callback server started on port {} with path {}", port, callbackPath);
            } catch (Exception e) {
                LOGGER.error("Failed to start OAuth2 callback server", e);
                throw new RuntimeException(e);
            }
            
            LOGGER.debug("OAuth2 Token Provider enabled: clientId={}, providerId={}", clientId, getIdentifier());
        } catch (Exception e) {
            LOGGER.error("Error enabling OAuth2 token provider", e);
            throw new RuntimeException("Failed to enable OAuth2 token provider: " + e.getMessage(), e);
        }
    }
    
    /**
     * Cleans up resources when the controller service is disabled.
     * <p>
     * This method:
     * <ol>
     *   <li>Unregisters this provider from the callback servlet</li>
     *   <li>Removes all PKCE values</li>
     *   <li>Stops the callback server</li>
     *   <li>Closes the HTTP client to free resources</li>
     *   <li>Clears the current token</li>
     * </ol>
     */
    @OnDisabled
    public void onDisabled() {
        pkceValuesMap.clear();
        
        if (callbackServer != null) {
            try {
                callbackServer.stop();
                LOGGER.debug("OAuth2 callback server stopped");
            } catch (Exception e) {
                LOGGER.error("Failed to stop OAuth2 callback server", e);
            }
        }
        
        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOGGER.warn("Error closing HTTP client", e);
            }
        }
        
        accessTokenMap.clear(); // TODO: decide if required to clear
        LOGGER.debug("OAuth2 Token Provider disabled: providerId={}", getIdentifier());
    }

    public AccessToken getAccessToken() throws AccessTokenAcquisitionException {
        String state = getServiceState();
        return getAccessTokenForState(state);
    }

    public AccessToken getAccessToken(String processorId) throws AccessTokenAcquisitionException {
        String state = getProcessorState(processorId);
        return getAccessTokenForState(state);
    }
        
        
    /**
     * Retrieves the current access token, refreshing it if necessary.
     * <p>
     * This method:
     * <ol>
     *   <li>Checks if a current token exists</li>
     *   <li>If no token exists, throws an exception indicating authorization is required</li>
     *   <li>If the token exists but is expired or near expiry, attempts to refresh it</li>
     *   <li>Returns the current valid token</li>
     * </ol>
     * <p>
     * The token refresh window (configured via {@link #TOKEN_REFRESH_WINDOW}) determines
     * how many seconds before expiry a token should be refreshed. This creates a buffer
     * to ensure a new token is obtained before the current one expires.
     * 
     * @return the current valid access token
     * @throws AccessTokenAcquisitionException if no token exists or if token refresh fails
     */
    protected AccessToken getAccessTokenForState(String state) throws AccessTokenAcquisitionException {
        AccessToken token = getTokenForState(state).get();
        
        if (token == null) {
            LOGGER.debug("No access token available. Authorization required");
            throw new AccessTokenAcquisitionException("No access token available. Authorization required.");
        }
        
        if (shouldRefreshToken(token)) {
            ReentrantLock tokenLock = getTokenLock(state);
            try {
                tokenLock.lock();
                setTokenLock(state, tokenLock);
                LOGGER.debug("Token refresh needed");
                token = refreshToken(state, token);
                setTokenForState(state, token);
                LOGGER.debug("Token successfully refreshed");
            } finally {
                tokenLock.unlock();
                setTokenLock(state, tokenLock);
            }
        }
        
        LOGGER.debug("Returning valid access token");
        return token;
    }

    public String getAuthorizationUrl() {
        String state = getServiceState();
        String scope = getScope();
        return getAuthorizationUrlForState(state, scope);
    }

    public String getAuthorizationUrl(String processorId, String scope) {
        String state = getProcessorState(processorId);
        String serviceScope = getScope();
        if (scope == null || scope.trim().isEmpty()) {
            scope = serviceScope;
        }
        return getAuthorizationUrlForState(state, scope);
    }

    /**
     * Generates the authorization URL for the OAuth 2.0 flow.
     * <p>
     * This method constructs a complete authorization URL with all required parameters:
     * <ul>
     *   <li>client_id - The OAuth client identifier</li>
     *   <li>response_type - Set to "code" for authorization code flow</li>
     *   <li>redirect_uri - Where the authorization server will redirect after user grants permission</li>
     *   <li>scope - The requested access scopes</li>
     *   <li>state - A random value to prevent CSRF attacks</li>
     *   <li>code_challenge - The PKCE code challenge</li>
     *   <li>code_challenge_method - Set to "S256" for SHA-256 hashing</li>
     * </ul>
     * <p>
     * The generated URL should be opened in a web browser to allow the user to grant
     * permission to the application. After permission is granted, the authorization server
     * will redirect to the specified redirect URI with an authorization code.
     * 
     * @return the complete authorization URL to be opened in a browser
     * @throws AccessTokenAcquisitionException if there is an error generating the URL
     */
    protected String getAuthorizationUrlForState(String state, String scope) {
        LOGGER.debug("Generating authorization URL for state {} with scope: '{}'", state, scope);
        
        String combinedScope = combineScopes(scope);
        LOGGER.debug("Using combined scope for authorization URL: '{}'", combinedScope);
        
        Map<String, String> additionalParams = getAdditionalParams();
        return getAuthorizationUrlForState(state, getBaseAuthorizationUrl(), combinedScope, additionalParams);
    }

    /**
     * Extended version of getAuthorizationUrlForState that allows specifying custom parameters.
     * <p>
     * This method provides more flexibility for constructing the authorization URL by
     * allowing custom state, scope, and additional parameters to be specified.
     * <p>
     * It ensures that PKCE values are properly associated with the state parameter
     * to maintain consistency throughout the OAuth flow.
     *
     * @param baseUrl The base authorization URL
     * @param state The state parameter to use for CSRF protection
     * @param scope The scope parameter to use for access privileges
     * @param additionalParams Additional parameters to include in the URL
     * @return The complete authorization URL
     */
    protected String getAuthorizationUrlForState(String state, String baseUrl, String scope, Map<String, String> additionalParams) {
        try {
            // Get PKCE values for the current state
            PkceValues pkceValues = getOrGeneratePkceValues(state);
            
            // Combine all additional parameters
            Map<String, String> allAdditionalParams = combineAdditionalParameters(additionalParams);

            LOGGER.debug("Setting requested scopes during authorization URL generation for state {}: '{}'", state, scope);
            setRequestedScopes(state, scope);
            
            // Delegate to the OAuth2AuthorizationUrlBuilder
            return OAuth2AuthorizationUrlBuilder.buildAuthorizationUrl(
                    baseUrl,
                    clientId,
                    redirectUri,
                    state,
                    scope,
                    pkceValues,
                    allAdditionalParams
            );
        } catch (Exception e) {
            LOGGER.error("Error generating authorization URL", e);
            throw new RuntimeException("Failed to generate authorization URL: " + e.getMessage(), e);
        }
    }
    
    /**
     * Combines additional parameters from the property and method argument.
     * Method parameters will override property parameters with the same key.
     * 
     * @param additionalParams Additional parameters from the method argument
     * @return A map containing all additional parameters
     */
    protected Map<String, String> combineAdditionalParameters(Map<String, String> additionalParams) {
        Map<String, String> allAdditionalParams = new HashMap<>();
        
        // Add additional parameters from property
        if (this.additionalParameters != null && !this.additionalParameters.isEmpty()) {
            String[] params = this.additionalParameters.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    allAdditionalParams.put(keyValue[0], keyValue[1]);
                }
            }
        }
        
        // Add additional parameters from method argument (will override property parameters with the same key)
        if (additionalParams != null && !additionalParams.isEmpty()) {
            allAdditionalParams.putAll(additionalParams);
        }
        
        return allAdditionalParams;
    }

    /**
     * Duplicates scopes that passed into function with scopes returned by getAdditionalScopes() function
     * 
     * Passed scopes have higher priority that scopes from getAdditionalScopes()
     * Scopes are separated by space
     * Scopes are compared case-insensitive to prevent duplicates
     * When getAdditionalScopes() returns null, passed scopes will be returned
     * 
     * @param scopes scopes to deduplicate
     * @return scopes without duplicates
     */
    protected String deduplicateScopes(String scopes) {
        if (scopes == null || scopes.trim().isEmpty()) {
            return "";
        }
        
        // Use TreeSet with case-insensitive comparator to handle case-insensitive uniqueness
        Set<String> uniqueScopes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        
        // Split, trim, and filter out empty scopes
        Arrays.stream(scopes.split(" "))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(uniqueScopes::add);
        
        return String.join(" ", uniqueScopes);
    }

    /**
     * Combines scopes that passed into function with scopes returned by getAdditionalScopes() function     * 
     * Passed scopes have higher priority that scopes from getAdditionalScopes()
     * Scopes are separated by space
     * Scopes are compared case-insensitive to prevent duplicates
     * When getAdditionalScopes() returns null, passed scopes will be returned
     */
    /**
     * Combines scopes that passed into function with scopes returned by getAdditionalScopes() function
     * 
     * Passed scopes have higher priority that scopes from getAdditionalScopes()
     * Scopes are separated by space
     * Scopes are compared case-insensitive to prevent duplicates
     * When getAdditionalScopes() returns null, passed scopes will be returned
     * 
     * @param scopes scopes to combine
     * @return combined scopes
     */
    protected String combineScopes(String scopes) {
        LOGGER.debug("Combining scopes - input scopes: '{}'", scopes);
        String additionalScopes = getAdditionalScopes();
        LOGGER.debug("Additional scopes from getAdditionalScopes(): '{}'", additionalScopes);
        
        if (additionalScopes == null || additionalScopes.trim().isEmpty()) {
            LOGGER.debug("No additional scopes to combine, returning original scopes: '{}'", scopes);
            return scopes;
        }

        if (scopes == null || scopes.trim().isEmpty()) {
            LOGGER.debug("Input scopes empty, returning only additional scopes: '{}'", additionalScopes);
            return additionalScopes;
        }
        
        LOGGER.debug("Processing scope combination - original: '{}', additional: '{}'", scopes, additionalScopes);
        
        String[] passedScopesArray = Arrays.stream(deduplicateScopes(scopes).split(" "))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        String[] additionalScopesArray = Arrays.stream(deduplicateScopes(additionalScopes).split(" "))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        
        LOGGER.debug("Processed scope arrays - passed: {}, additional: {}", 
                Arrays.toString(passedScopesArray), Arrays.toString(additionalScopesArray));
        
        Set<String> combinedScopes = new HashSet<>(Arrays.asList(passedScopesArray));
        Set<String> lowerCasePassedScopes = new HashSet<>(Arrays.asList(Arrays.stream(passedScopesArray)
                .map(String::toLowerCase)
                .toArray(String[]::new)
                ));
        
        LOGGER.debug("Initial combined scopes (from passed): {}", combinedScopes);
        LOGGER.debug("Lower-case passed scopes for comparison: {}", lowerCasePassedScopes);
        
        for (String scope : additionalScopesArray) {
            if (!lowerCasePassedScopes.contains(scope.toLowerCase())) {
                LOGGER.debug("Adding additional scope '{}' (not in passed scopes)", scope);
                combinedScopes.add(scope);
            } else {
                LOGGER.debug("Skipping additional scope '{}' (already in passed scopes)", scope);
            }
        }
        
        String result = String.join(" ", combinedScopes);
        LOGGER.debug("Final combined scopes result: '{}'", result);
        return result;
    }

    public boolean isAuthorized() {
        String state = getServiceState();
        return isAuthorizedForState(state);
    }

    public boolean isAuthorized(String processorId, String scopes) {
        String state = getProcessorState(processorId);
        if (requestedScopesChanged(state, scopes)) {
            setTokenForState(state, null);
            return false;
        }
        return isAuthorizedForState(state);
    }

    /**
     * Checks if the service is currently authorized with a valid access token.
     * <p>
     * This method checks if a current token exists and is not expired or near expiry.
     * It does not attempt to refresh the token if it is expired.
     * 
     * @return true if a valid token exists, false otherwise
     */
    protected boolean isAuthorizedForState(String state) {
        try {
            AccessToken token = getAccessTokenForState(state);
            return token != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Processes an authorization code received from the OAuth 2.0 authorization server.
     * <p>
     * This method is called by the callback server when an authorization code is received.
     * It exchanges the authorization code for an access token and refresh token by making
     * a request to the token endpoint with the following parameters:
     * <ul>
     *   <li>grant_type - Set to "authorization_code"</li>
     *   <li>code - The authorization code received from the authorization server</li>
     *   <li>redirect_uri - Must match the redirect URI used in the authorization request</li>
     *   <li>client_id - The OAuth client identifier</li>
     *   <li>client_secret - The OAuth client secret</li>
     *   <li>code_verifier - The PKCE code verifier generated during authorization</li>
     * </ul>
     * <p>
     * The received tokens are stored for future use and the current token is updated.
     * 
     * @param receivedState the state parameter received from the authorization server
     * @param code the authorization code received from the authorization server
     * @throws AccessTokenAcquisitionException if there is an error processing the code or acquiring tokens
     */
    protected void processAuthorizationCode(String receivedState, String code) throws AccessTokenAcquisitionException {
        LOGGER.debug("Processing authorization code with state: {}", receivedState);
        
        if (!pkceValuesMap.containsKey(receivedState)) {
            LOGGER.warn("State mismatch: state {} does not exist", receivedState);
            throw new AccessTokenAcquisitionException("State mismatch in OAuth callback");
        }
        
        PkceValues pkceValues = pkceValuesMap.get(receivedState);
        if (pkceValues == null) {
            LOGGER.error("No PKCE values found for state {} in provider {}", receivedState, getIdentifier());
            throw new AccessTokenAcquisitionException("No PKCE values found for state: " + receivedState);
        }
        
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "authorization_code"));
        params.add(new BasicNameValuePair("code", code));
        params.add(new BasicNameValuePair("redirect_uri", redirectUri));
        
        params.add(new BasicNameValuePair("client_id", clientId));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            params.add(new BasicNameValuePair("client_secret", clientSecret));
        }
        
        params.add(new BasicNameValuePair("code_verifier", pkceValues.getCodeVerifier()));
        LOGGER.debug("Added code_verifier to token request");
        
        try {
            AccessToken token = executeTokenRequest(params);
            setTokenForState(receivedState, token);
            LOGGER.debug("Access token acquired successfully");
        } catch (Exception e) {
            LOGGER.error("Error processing authorization code", e);
            throw new AccessTokenAcquisitionException("Error processing authorization code: " + e.getMessage(), e);
        }
    }

    /**
     * Refreshes an expired access token using the refresh token.
     * <p>
     * This method makes a request to the token endpoint with the following parameters:
     * <ul>
     *   <li>grant_type - Set to "refresh_token"</li>
     *   <li>refresh_token - The refresh token from the expired access token</li>
     *   <li>client_id - The OAuth client identifier</li>
     *   <li>client_secret - The OAuth client secret</li>
     * </ul>
     * <p>
     * If the refresh token is valid, the authorization server will return a new
     * access token and possibly a new refresh token.
     * 
     * @param token the expired access token containing a refresh token
     * @return a new valid access token, or null if refresh failed
     * @throws AccessTokenAcquisitionException if there is an error refreshing the token
     */
    protected AccessToken refreshToken(String state, AccessToken currentToken) throws AccessTokenAcquisitionException {
        LOGGER.debug("Refreshing access token");
        
        if (currentToken == null) {
            throw new AccessTokenAcquisitionException("Cannot refresh null token");
        }
        
        String refreshToken = currentToken.getRefreshToken();

        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new AccessTokenAcquisitionException("No refresh token available");
        }
        
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "refresh_token"));
        params.add(new BasicNameValuePair("refresh_token", refreshToken));
        params.add(new BasicNameValuePair("client_id", clientId));
        if (clientSecret != null && !clientSecret.isEmpty()) {
            params.add(new BasicNameValuePair("client_secret", clientSecret));
        }
        
        try {
            AccessToken token = executeTokenRequest(params);
            if (token.getRefreshToken() == null) {
                token.setRefreshToken(refreshToken);
            }
            return token;
        } catch (Exception e) {
            setTokenForState(state, null);
            LOGGER.error("Error refreshing token", e);
            throw new AccessTokenAcquisitionException("Error refreshing token: " + e.getMessage(), e);
        }
    }

    /**
     * Executes a token request to the OAuth 2.0 token endpoint.
     * <p>
     * This method:
     * <ol>
     *   <li>Sends the HTTP request to the token endpoint</li>
     *   <li>Parses the JSON response to extract token information</li>
     *   <li>Creates and returns an AccessToken object with the parsed information</li>
     * </ol>
     * <p>
     * The response is expected to contain at least:
     * <ul>
     *   <li>access_token - The access token string</li>
     *   <li>token_type - The type of token (usually "Bearer")</li>
     *   <li>expires_in - The token lifetime in seconds</li>
     * </ul>
     * <p>
     * It may also contain:
     * <ul>
     *   <li>refresh_token - A token that can be used to obtain new access tokens</li>
     *   <li>scope - The granted scopes (may differ from requested scopes)</li>
     *   <li>id_token - An ID token if OpenID Connect was used</li>
     * </ul>
     * 
     * @param params the parameters to include in the token request
     * @return an AccessToken object containing the token information
     * @throws AccessTokenAcquisitionException if there is an error executing the request or parsing the response
     */
    protected AccessToken executeTokenRequest(List<NameValuePair> params) throws AccessTokenAcquisitionException {
        String tokenUrl = getTokenUrl();
        LOGGER.debug("Executing token request to: {}", tokenUrl);
        
        HttpPost tokenRequest = new HttpPost(tokenUrl);
        tokenRequest.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
        
        try {
            return httpClient.execute(tokenRequest, response -> {
                int statusCode = response.getCode();
                LOGGER.debug("Token response status: {}", statusCode);
                
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new IOException(new AccessTokenAcquisitionException("Token response contained no entity"));
                }
                
                String responseBody;
                try {
                    responseBody = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                    LOGGER.debug("Token response body: {}", responseBody);
                } catch (ParseException e) {
                    throw new IOException(new AccessTokenAcquisitionException("Error parsing token response: " + e.getMessage(), e));
                }
                
                if (statusCode != 200) {
                    LOGGER.error("Token request failed: status={}", statusCode);
                    throw new IOException(new AccessTokenAcquisitionException("Token request failed with status code " + statusCode));
                }
                
                try {
                    LOGGER.debug("Token response received");
                    return parseTokenResponse(responseBody);
                } catch (AccessTokenAcquisitionException e) {
                    throw new IOException(e);
                }
            });
        } catch (IOException e) {
            LOGGER.error("Error executing token request", e);
            if (e.getCause() instanceof AccessTokenAcquisitionException) {
                throw (AccessTokenAcquisitionException) e.getCause();
            }
            throw new AccessTokenAcquisitionException("Error executing token request: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a JSON token response from the OAuth 2.0 token endpoint.
     * <p>
     * This method extracts the token information from the JSON response and
     * creates an AccessToken object with the parsed information.
     * 
     * @param tokenResponse the JSON response from the token endpoint
     * @return an AccessToken object containing the token information
     * @throws AccessTokenAcquisitionException if there is an error parsing the response
     */
    protected AccessToken parseTokenResponse(String tokenResponse) throws AccessTokenAcquisitionException {
        try {
            JsonNode jsonNode = objectMapper.readTree(tokenResponse);
            
            if (!jsonNode.has("access_token")) {
                throw new AccessTokenAcquisitionException("Token response missing access_token field");
            }
            
            String accessToken = jsonNode.get("access_token").asText();
            String tokenType = jsonNode.has("token_type") ? jsonNode.get("token_type").asText() : "Bearer";
            
            long expiresIn = jsonNode.has("expires_in") ? jsonNode.get("expires_in").asLong() : 3600;
            
            String refreshToken = jsonNode.has("refresh_token") ? jsonNode.get("refresh_token").asText() : null;
            String scope = jsonNode.has("scope") ? jsonNode.get("scope").asText() : null;
            
            LOGGER.debug("Parsed token response: expires_in={}, has_refresh_token={}", 
                    expiresIn, refreshToken != null);
            
            AccessToken token = new AccessToken();
            token.setAccessToken(accessToken);
            token.setTokenType(tokenType);
            token.setExpiresInSeconds(expiresIn);
            if (refreshToken != null) token.setRefreshToken(refreshToken);
            token.setScope(scope);
            
            return token;
        } catch (IOException e) {
            LOGGER.error("Error parsing token response", e);
            throw new AccessTokenAcquisitionException("Error parsing token response: " + e.getMessage(), e);
        }
    }

    /**
     * Determines whether a token should be refreshed based on its expiration time.
     * <p>
     * A token should be refreshed if it is expired or will expire within the
     * configured token refresh window.
     * 
     * @param token the token to check
     * @return true if the token should be refreshed, false otherwise
     */
    protected boolean shouldRefreshToken(AccessToken token) {
        if (token == null) {
            return false;
        }
        
        return token.isExpiredOrExpiring(tokenRefreshWindow);
    }
    
    /**
     * Gets the base authorization URL.
     * <p>
     * This is a convenience method that returns the authorization URL
     * configured for this provider.
     * 
     * @return the base authorization URL
     */
    protected String getBaseAuthorizationUrl() {
        return authorizationUrl;
    }

    /**
     * Gets the token URL for the OAuth 2.0 token endpoint.
     * <p>
     * This is a convenience method that returns the token endpoint URL
     * configured for this provider. The token endpoint is used for:
     * <ul>
     *   <li>Exchanging authorization codes for access tokens</li>
     *   <li>Refreshing expired access tokens using refresh tokens</li>
     * </ul>
     * <p>
     * The token URL is configured via the {@link #TOKEN_URL} property.
     * 
     * @return the token endpoint URL
     */
    protected String getTokenUrl() {
        return tokenUrl;
    }

    /**
     * Gets the OAuth 2.0 scope configured for this provider.
     * <p>
     * The scope defines the access privileges requested during the OAuth flow.
     * It is a space-separated list of scope values that specify the resources
     * and operations the client is requesting permission to access.
     * <p>
     * Common scope values include:
     * <ul>
     *   <li>"openid" - For OpenID Connect authentication</li>
     *   <li>"email" - For access to the user's email address</li>
     *   <li>"profile" - For access to the user's basic profile information</li>
     *   <li>API-specific scopes (e.g., "https://www.googleapis.com/auth/drive")</li>
     * </ul>
     * <p>
     * The scope is configured via the {@link #SCOPE} property.
     * 
     * @return the configured OAuth 2.0 scope
     */
    protected String getScope() {
        return scope;
    }

    /**
     * Gets additional parameters for OAuth 2.0 authorization requests.
     * <p>
     * This method can be overridden by subclasses to provide custom parameters
     * to include in authorization requests. The default implementation returns null,
     * indicating no additional parameters beyond those configured via the
     * {@link #ADDITIONAL_PARAMETERS} property.
     * <p>
     * Additional parameters can be used to customize the OAuth flow, such as:
     * <ul>
     *   <li>"access_type=offline" - To request a refresh token from Google</li>
     *   <li>"prompt=consent" - To force the consent screen to appear</li>
     *   <li>"response_mode=form_post" - To specify how the response should be delivered</li>
     * </ul>
     * <p>
     * Parameters returned by this method will override any parameters with the same
     * name specified in the {@link #ADDITIONAL_PARAMETERS} property.
     * 
     * @return a map of additional parameters, or null if none
     */
    protected Map<String, String> getAdditionalParams() {
        return null;
    }

    /**
     * Gets additional OAuth 2.0 scopes to be combined with explicitly requested scopes.
     * <p>
     * This method can be overridden by subclasses to provide additional scopes that
     * should be included in all OAuth 2.0 authorization requests. The default
     * implementation returns null, indicating no additional scopes beyond those
     * explicitly requested.
     * <p>
     * Scopes returned by this method will be combined with explicitly requested
     * scopes, with explicitly requested scopes taking precedence in case of conflicts.
     * 
     * @return a space-separated list of additional OAuth 2.0 scopes, or null if none
     */
    protected String getAdditionalScopes() {
        return null;
    }

    /**
     * Generates PKCE (Proof Key for Code Exchange) values for the current provider.
     * <p>
     * This method:
     * <ol>
     *   <li>Checks if PKCE values already exist for this provider to avoid regeneration</li>
     *   <li>Generates a random code verifier according to RFC 7636 (43-128 chars with specific character set)</li>
     *   <li>Generates a code challenge as the BASE64URL-encoded SHA-256 hash of the code verifier</li>
     *   <li>Stores the values both in instance variables (for backward compatibility) and in the static map</li>
     * </ol>
     * <p>
     * PKCE enhances security for the OAuth 2.0 authorization code flow by preventing
     * authorization code interception attacks. The code verifier is kept secret and
     * later used when exchanging the authorization code for tokens.
     * <p>
     * The values are generated once during service enablement and reused throughout
     * the entire OAuth flow to ensure consistency.
     * 
     * @throws RuntimeException if PKCE value generation fails
     */
    protected PkceValues getOrGeneratePkceValues(String state) {
        try {
            if (!pkceValuesMap.containsKey(state)) {
                pkceValuesMap.put(state, PkceUtils.generatePkceValues());
                
                LOGGER.debug("Generated PKCE values and stored with providerId: {}", getIdentifier());
            } else {
                LOGGER.debug("Reusing existing PKCE values");
            }
            return pkceValuesMap.get(state);
        } catch (NoSuchAlgorithmException e) {
            LOGGER.error("Failed to generate PKCE values", e);
            throw new RuntimeException("Failed to generate PKCE values: " + e.getMessage(), e);
        }
    }

    /**
     * Ensures that a token reference exists for the specified state.
     * <p>
     * This method checks if a token reference exists in the accessTokenMap for the
     * given state. If not, it creates a new AtomicReference and adds it to the map.
     * <p>
     * This is a helper method used internally to ensure that token operations have
     * a valid reference to work with, preventing NullPointerExceptions.
     * 
     * @param state the state identifier for which to ensure a token reference exists
     */
    protected void ensureTokenRef(String state) {
        if (!accessTokenMap.containsKey(state)) {
            accessTokenMap.put(state, new AtomicReference<>());
        }
    }

    /**
     * Sets the access token for the specified state.
     * <p>
     * This method stores an access token in the accessTokenMap for the given state.
     * It ensures that a token reference exists before setting the token and updates
     * both the state-specific token and the service-wide token (using getIdentifier()).
     * <p>
     * This method is used when a new token is acquired or refreshed, to make it
     * available for future requests.
     * 
     * @param state the state identifier for which to set the token
     * @param token the access token to store, or null to clear the token
     */
    protected void setTokenForState(String state, AccessToken token) {
        ensureTokenRef(state);
        AtomicReference<AccessToken> tokenRef = accessTokenMap.get(state);
        tokenRef.set(token);
        accessTokenMap.put(state, tokenRef);
    }

    /**
     * Gets the access token reference for the specified state.
     * <p>
     * This method retrieves the AtomicReference containing the access token for the
     * given state from the accessTokenMap. It ensures that a token reference exists
     * before attempting to retrieve it.
     * <p>
     * The returned reference may contain a null token if no token has been set yet.
     * 
     * @param state the state identifier for which to get the token reference
     * @return an AtomicReference containing the access token, or an empty reference if no token exists
     */
    protected AtomicReference<AccessToken> getTokenForState(String state) {
        ensureTokenRef(state);
        return accessTokenMap.get(state);
    }

    /**
     * Gets the state identifier for the service.
     * <p>
     * This method generates a unique state identifier for the service by prefixing
     * the service identifier with "s-". This state identifier is used to store and
     * retrieve service-wide tokens and PKCE values.
     * <p>
     * The service state is distinct from processor-specific states, allowing the
     * service to maintain its own OAuth state separate from processors that use it.
     * 
     * @return the state identifier for the service
     */
    protected String getServiceState() {
        return "s-" + getIdentifier();
    }

    /**
     * Gets the state identifier for a processor.
     * <p>
     * This method generates a unique state identifier for a processor by prefixing
     * the processor identifier with "p-". This state identifier is used to store and
     * retrieve processor-specific tokens and PKCE values.
     * <p>
     * Processor-specific states allow multiple processors to share a single service
     * instance while maintaining their own separate OAuth states.
     * 
     * @param processorId the identifier of the processor
     * @return the state identifier for the processor
     */
    protected String getProcessorState(String processorId) {
        return "p-" + processorId;
    }

    /**
     * Gets the requested OAuth 2.0 scopes for the specified state.
     * <p>
     * This method retrieves the previously requested scopes for the given state
     * from the requestedScopes map. These scopes are stored when an authorization
     * URL is generated and are used to determine if scope changes require
     * reauthorization.
     * 
     * @param state the state identifier for which to get the requested scopes
     * @return the requested scopes as a space-separated string, or null if no scopes have been requested
     */
    protected String getRequestedScopes(String state) {
        String scopes = requestedScopes.get(state);
        LOGGER.debug("Retrieved requested scopes for state {}: '{}'", state, scopes);
        return scopes;
    }

    /**
     * Sets the requested OAuth 2.0 scopes for the specified state.
     * <p>
     * This method stores the requested scopes for the given state in the
     * requestedScopes map. These scopes are used to determine if scope changes
     * require reauthorization.
     * <p>
     * The method first removes any existing scopes for the state to ensure a clean
     * update, then adds the new scopes.
     * 
     * @param state the state identifier for which to set the requested scopes
     * @param scopes the OAuth 2.0 scopes as a space-separated string
     */
    protected void setRequestedScopes(String state, String scopes) {
        String previousScopes = requestedScopes.get(state);
        LOGGER.debug("Setting requested scopes for state {} - previous: '{}', new: '{}'", state, previousScopes, scopes);
        
        requestedScopes.remove(state);
        requestedScopes.put(state, scopes);
        LOGGER.debug("Updated requested scopes for state {} to: '{}'", state, scopes);
    }

    /**
     * Gets the token lock for the specified state.
     * <p>
     * This method retrieves the ReentrantLock for the given state from the tokenLocks
     * map. If no lock exists for the state, a new one is created and added to the map.
     * <p>
     * Token locks are used to ensure thread safety when refreshing tokens, preventing
     * multiple concurrent refresh attempts for the same state.
     * 
     * @param state the state identifier for which to get the token lock
     * @return the ReentrantLock for the specified state
     */
    protected ReentrantLock getTokenLock(String state) {
        if (!tockenLocks.containsKey(state)) {
            tockenLocks.put(state, new ReentrantLock());
        }
        return tockenLocks.get(state);
    }

    /**
     * Sets the token lock for the specified state.
     * <p>
     * This method stores a ReentrantLock for the given state in the tokenLocks map.
     * It is used to update or replace the lock for a state, typically after a lock
     * operation has completed.
     * <p>
     * Token locks are used to ensure thread safety when refreshing tokens, preventing
     * multiple concurrent refresh attempts for the same state.
     * 
     * @param state the state identifier for which to set the token lock
     * @param lock the ReentrantLock to store for the state
     */
    protected void setTokenLock(String state, ReentrantLock lock) {
        tockenLocks.put(state, lock);
    }
    
    /**
     * Checks if the requested scopes have changed compared to previously stored scopes.
     * <p>
     * This method compares the given scopes with the previously stored scopes for the
     * specified state. The comparison is case-insensitive and whitespace-insensitive,
     * treating scopes as a set rather than a string.
     * <p>
     * If the scopes have changed, reauthorization may be required to obtain a token
     * with the new scopes.
     * 
     * @param state the state identifier for which to check scopes
     * @param scopes the new scopes to compare with stored scopes
     * @return true if the scopes have changed or if no scopes were previously stored, false otherwise
     */
    protected boolean requestedScopesChanged(String state, String scopes) {
        String currentScopes = getRequestedScopes(state);
        
        LOGGER.debug("Comparing scopes for state {}: current='{}', new='{}'", state, currentScopes, scopes);
        
        if (currentScopes == null) {
            LOGGER.debug("No current scopes for state {}, treating as changed", state);
            return true;
        }
        
        // Log the raw scope strings before processing
        LOGGER.debug("Raw scope strings - current: '{}', new: '{}'", currentScopes, scopes);
        
        String[] passedScopesArray = Arrays.stream(scopes.split(" "))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        String[] currentScopesArray = Arrays.stream(currentScopes.split(" "))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        
        // Log the processed scope arrays
        LOGGER.debug("Processed scope arrays - current: {}, new: {}", Arrays.toString(currentScopesArray), Arrays.toString(passedScopesArray));
        
        Set<String> passedScopesSet = new HashSet<>(Arrays.asList(passedScopesArray));
        Set<String> currentScopesSet = new HashSet<>(Arrays.asList(currentScopesArray));
        
        // Log the final scope sets being compared
        LOGGER.debug("Final scope sets - current: {}, new: {}", currentScopesSet, passedScopesSet);
        
        boolean changed = !passedScopesSet.equals(currentScopesSet);
        LOGGER.debug("Scope comparison result: {} (sets {} equal)", changed ? "CHANGED" : "UNCHANGED", changed ? "NOT" : "ARE");
        
        return changed;
    }
}
