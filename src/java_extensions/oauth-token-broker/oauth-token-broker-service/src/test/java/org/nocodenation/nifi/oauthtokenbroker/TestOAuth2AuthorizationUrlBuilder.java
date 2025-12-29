package org.nocodenation.nifi.oauthtokenbroker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for the OAuth2AuthorizationUrlBuilder utility class.
 */
public class TestOAuth2AuthorizationUrlBuilder {

    private static final String BASE_URL = "https://auth.example.com/oauth2/auth";
    private static final String CLIENT_ID = "test-client-id";
    // Note: In OAuth2AccessTokenProvider, REDIRECT_URI has been replaced with LISTEN_PORT and EXTERNAL_REDIRECT_URI,
    // but the builder still accepts a complete redirect URI which is constructed by the provider
    private static final String REDIRECT_URI = "http://localhost:8080/oauth/callback";
    private static final String STATE = "test-state-123";
    private static final String SCOPE = "openid email profile";
    
    private PkceUtils.PkceValues pkceValues;
    
    @BeforeEach
    public void setUp() {
        // Create a fixed set of PKCE values for testing
        pkceValues = new PkceUtils.PkceValues(
                "test-code-verifier-abcdefghijklmnopqrstuvwxyz123456789",
                "test-code-challenge-ABCDEFGHIJKLMNOPQRSTUVWXYZ987654321"
        );
    }
    
    /**
     * Test that the buildAuthorizationUrl method correctly builds a URL with all required parameters.
     */
    @Test
    public void testBuildAuthorizationUrlWithAllParameters() {
        // Set up additional parameters
        Map<String, String> additionalParams = new HashMap<>();
        additionalParams.put("access_type", "offline");
        additionalParams.put("prompt", "consent");
        
        // Build the URL
        String url = OAuth2AuthorizationUrlBuilder.buildAuthorizationUrl(
                BASE_URL,
                CLIENT_ID,
                REDIRECT_URI,
                STATE,
                SCOPE,
                pkceValues,
                additionalParams
        );
        
        // Verify the URL contains all expected parameters
        assertTrue(url.startsWith(BASE_URL + "?"), "URL should start with the base URL");
        assertTrue(url.contains("client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)), 
                "URL should contain the client ID");
        assertTrue(url.contains("response_type=code"), "URL should contain response_type=code");
        assertTrue(url.contains("redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)), 
                "URL should contain the redirect URI");
        assertTrue(url.contains("scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8)), 
                "URL should contain the scope");
        assertTrue(url.contains("state=" + URLEncoder.encode(STATE, StandardCharsets.UTF_8)), 
                "URL should contain the state");
        assertTrue(url.contains("code_challenge=" + URLEncoder.encode(pkceValues.getCodeChallenge(), StandardCharsets.UTF_8)), 
                "URL should contain the code challenge");
        assertTrue(url.contains("code_challenge_method=S256"), 
                "URL should contain code_challenge_method=S256");
        assertTrue(url.contains("access_type=offline"), 
                "URL should contain the additional access_type parameter");
        assertTrue(url.contains("prompt=consent"), 
                "URL should contain the additional prompt parameter");
    }
    
    /**
     * Test that the buildAuthorizationUrl method works correctly with minimal parameters.
     */
    @Test
    public void testBuildAuthorizationUrlWithMinimalParameters() {
        // Build the URL with minimal parameters
        String url = OAuth2AuthorizationUrlBuilder.buildAuthorizationUrl(
                BASE_URL,
                CLIENT_ID,
                REDIRECT_URI,
                STATE,
                null, // No scope
                pkceValues,
                null // No additional parameters
        );
        
        // Verify the URL contains all required parameters
        assertTrue(url.startsWith(BASE_URL + "?"), "URL should start with the base URL");
        assertTrue(url.contains("client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)), 
                "URL should contain the client ID");
        assertTrue(url.contains("response_type=code"), "URL should contain response_type=code");
        assertTrue(url.contains("redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)), 
                "URL should contain the redirect URI");
        assertTrue(url.contains("state=" + URLEncoder.encode(STATE, StandardCharsets.UTF_8)), 
                "URL should contain the state");
        assertTrue(url.contains("code_challenge=" + URLEncoder.encode(pkceValues.getCodeChallenge(), StandardCharsets.UTF_8)), 
                "URL should contain the code challenge");
        assertTrue(url.contains("code_challenge_method=S256"), 
                "URL should contain code_challenge_method=S256");
        
        // Verify the URL does not contain optional parameters
        assertFalse(url.contains("scope="), "URL should not contain scope parameter");
        assertFalse(url.contains("access_type="), "URL should not contain access_type parameter");
        assertFalse(url.contains("prompt="), "URL should not contain prompt parameter");
    }
    
    /**
     * Test that the buildAuthorizationUrl method handles null PKCE values gracefully.
     */
    @Test
    public void testBuildAuthorizationUrlWithNullPkceValues() {
        // Build the URL with null PKCE values
        String url = OAuth2AuthorizationUrlBuilder.buildAuthorizationUrl(
                BASE_URL,
                CLIENT_ID,
                REDIRECT_URI,
                STATE,
                SCOPE,
                null, // Null PKCE values
                null
        );
        
        // Verify the URL contains all required parameters
        assertTrue(url.startsWith(BASE_URL + "?"), "URL should start with the base URL");
        assertTrue(url.contains("client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)), 
                "URL should contain the client ID");
        assertTrue(url.contains("response_type=code"), "URL should contain response_type=code");
        assertTrue(url.contains("redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)), 
                "URL should contain the redirect URI");
        assertTrue(url.contains("state=" + URLEncoder.encode(STATE, StandardCharsets.UTF_8)), 
                "URL should contain the state");
        
        // Verify the URL does not contain PKCE parameters
        assertFalse(url.contains("code_challenge="), "URL should not contain code_challenge parameter");
        assertFalse(url.contains("code_challenge_method="), "URL should not contain code_challenge_method parameter");
    }
    
    /**
     * Test that the addParametersFromString method correctly parses and adds parameters.
     */
    @Test
    public void testAddParametersFromString() {
        StringBuilder urlBuilder = new StringBuilder("https://example.com/oauth2/auth?param1=value1");
        String additionalParameters = "param2=value2&param3=value with spaces";
        
        OAuth2AuthorizationUrlBuilder.addParametersFromString(urlBuilder, additionalParameters);
        
        String url = urlBuilder.toString();
        assertTrue(url.contains("param1=value1"), "URL should contain the original parameter");
        assertTrue(url.contains("param2=value2"), "URL should contain the first additional parameter");
        assertTrue(url.contains("param3=" + URLEncoder.encode("value with spaces", StandardCharsets.UTF_8)), 
                "URL should contain the second additional parameter with spaces properly encoded");
    }
    
    /**
     * Test that the addParametersFromString method handles empty or null parameters gracefully.
     */
    @Test
    public void testAddParametersFromStringWithEmptyOrNullParameters() {
        StringBuilder urlBuilder = new StringBuilder("https://example.com/oauth2/auth?param1=value1");
        
        // Test with empty string
        OAuth2AuthorizationUrlBuilder.addParametersFromString(urlBuilder, "");
        String url = urlBuilder.toString();
        assertEquals("https://example.com/oauth2/auth?param1=value1", url, 
                "URL should not be modified with empty parameters");
        
        // Test with null
        OAuth2AuthorizationUrlBuilder.addParametersFromString(urlBuilder, null);
        url = urlBuilder.toString();
        assertEquals("https://example.com/oauth2/auth?param1=value1", url, 
                "URL should not be modified with null parameters");
    }
}
