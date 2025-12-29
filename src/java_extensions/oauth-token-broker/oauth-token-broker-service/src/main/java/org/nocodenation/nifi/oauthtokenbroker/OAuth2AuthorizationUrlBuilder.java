package org.nocodenation.nifi.oauthtokenbroker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.nocodenation.nifi.oauthtokenbroker.PkceUtils.PkceValues;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Utility class for building OAuth 2.0 authorization URLs.
 * <p>
 * This class provides methods for constructing complete authorization URLs with all required
 * parameters for OAuth 2.0 authorization code flow with PKCE support. It handles URL encoding,
 * parameter formatting, and ensures all required OAuth parameters are included.
 */
public class OAuth2AuthorizationUrlBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2AuthorizationUrlBuilder.class);

    /**
     * Builds a complete OAuth 2.0 authorization URL with all required parameters.
     * <p>
     * This method constructs a URL with standard OAuth 2.0 parameters and PKCE extensions:
     * <ul>
     *   <li>client_id - The OAuth client identifier</li>
     *   <li>response_type - Set to "code" for authorization code flow</li>
     *   <li>redirect_uri - Where the authorization server will redirect after user grants permission</li>
     *   <li>scope - The requested access scopes</li>
     *   <li>state - A random value to prevent CSRF attacks</li>
     *   <li>code_challenge - The PKCE code challenge</li>
     *   <li>code_challenge_method - Set to "S256" for SHA-256 hashing</li>
     * </ul>
     *
     * @param baseUrl The base authorization URL
     * @param clientId The OAuth 2.0 client ID
     * @param redirectUri The redirect URI for the callback
     * @param state The state parameter for CSRF protection
     * @param scope The requested scopes
     * @param pkceValues The PKCE values (code verifier and challenge)
     * @param additionalParams Additional parameters to include in the URL
     * @return The complete authorization URL
     */
    public static String buildAuthorizationUrl(
            String baseUrl,
            String clientId,
            String redirectUri,
            String state,
            String scope,
            PkceValues pkceValues,
            Map<String, String> additionalParams) {
        
        try {
            StringBuilder urlBuilder = new StringBuilder(baseUrl);
            urlBuilder.append("?client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
            urlBuilder.append("&response_type=code");
            urlBuilder.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
            
            if (scope != null && !scope.isEmpty()) {
                urlBuilder.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
            }
            
            urlBuilder.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
            
            // Add additional parameters
            if (additionalParams != null && !additionalParams.isEmpty()) {
                for (Map.Entry<String, String> entry : additionalParams.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    
                    urlBuilder.append("&").append(key).append("=")
                            .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            }
            
            // Add PKCE parameters if available
            if (pkceValues != null) {
                urlBuilder.append("&code_challenge=").append(URLEncoder.encode(pkceValues.getCodeChallenge(), StandardCharsets.UTF_8));
                urlBuilder.append("&code_challenge_method=S256");
                LOGGER.debug("Added PKCE parameters to authorization URL");
            } else {
                LOGGER.warn("No PKCE values available for authorization URL");
            }
            
            String url = urlBuilder.toString();
            LOGGER.debug("Generated authorization URL");
            return url;
        } catch (Exception e) {
            LOGGER.error("Error generating authorization URL", e);
            throw new RuntimeException("Failed to generate authorization URL: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parses a string of additional parameters in the format "key1=value1&key2=value2"
     * and adds them to the provided URL builder.
     *
     * @param urlBuilder The StringBuilder to append parameters to
     * @param additionalParameters The string of additional parameters
     */
    public static void addParametersFromString(StringBuilder urlBuilder, String additionalParameters) {
        if (additionalParameters != null && !additionalParameters.isEmpty()) {
            String[] params = additionalParameters.split("&");
            for (String param : params) {
                String[] keyValue = param.split("=", 2);
                if (keyValue.length == 2) {
                    urlBuilder.append("&").append(keyValue[0]).append("=")
                            .append(URLEncoder.encode(keyValue[1], StandardCharsets.UTF_8));
                }
            }
        }
    }
}
