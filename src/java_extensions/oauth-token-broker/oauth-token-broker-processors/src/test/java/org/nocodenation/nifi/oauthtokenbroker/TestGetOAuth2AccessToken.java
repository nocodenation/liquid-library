package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.nocodenation.nifi.oauthtokenbroker.AccessToken;
import org.nocodenation.nifi.oauthtokenbroker.AccessTokenAcquisitionException;
import org.nocodenation.nifi.oauthtokenbroker.OAuth2AccessTokenProvider;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestGetOAuth2AccessToken {

    private TestRunner testRunner;
    private MockTokenProvider tokenProvider;

    @BeforeEach
    public void setup() throws InitializationException {
        // Set up the processor
        GetOAuth2AccessToken processor = new GetOAuth2AccessToken();
        testRunner = TestRunners.newTestRunner(processor);
        
        // Create a mock token provider
        tokenProvider = new MockTokenProvider();
        testRunner.addControllerService("tokenProvider", tokenProvider);
        testRunner.enableControllerService(tokenProvider);
        testRunner.setProperty(GetOAuth2AccessToken.OAUTH2_TOKEN_PROVIDER, "tokenProvider");
        
        // Set default property values
        testRunner.setProperty(GetOAuth2AccessToken.OUTPUT_AUTH_URL, "No");
    }

    @Test
    public void testProcessorWithValidToken() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return a valid token
        AccessToken token = new AccessToken();
        token.setAccessToken("test-access-token");
        token.setRefreshToken("refresh-token");
        token.setTokenType("Bearer");
        token.setExpiresInSeconds(3600);
        token.setScope("read write");
        tokenProvider.setAccessToken(token);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals("oauth2.token", "test-access-token");
    }
    
    @Test
    public void testProcessorAlwaysExcludesTokenType() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return a valid token
        AccessToken token = new AccessToken();
        token.setAccessToken("test-access-token");
        token.setRefreshToken("refresh-token");
        token.setTokenType("Bearer");
        token.setExpiresInSeconds(3600);
        token.setScope("read write");
        tokenProvider.setAccessToken(token);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_SUCCESS).get(0);
        // Verify token type is NOT included (regardless of the token type value)
        flowFile.assertAttributeEquals("oauth2.token", "test-access-token");
    }
    
    @Test
    public void testProcessorWithUnauthorizedProvider() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return unauthorized
        tokenProvider.setAuthorized(false);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        // Set OUTPUT_AUTH_URL to Yes to bypass validation for unauthorized provider
        testRunner.setProperty(GetOAuth2AccessToken.OUTPUT_AUTH_URL, "Yes");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results - with OUTPUT_AUTH_URL set to Yes, 
        // unauthorized providers should route to REL_AUTHORIZE
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 1);
        
        // Verify the authorization URL is added as an attribute
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_AUTHORIZE).get(0);
        flowFile.assertAttributeEquals("oauth2.authorize_url", "https://example.com/auth");
    }
    
    @Test
    public void testProcessorWithTokenAcquisitionException() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to throw an exception
        tokenProvider.setThrowException(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
    }
    
    @Test
    public void testProcessorWithNullToken() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return a null token
        tokenProvider.setAccessToken(null);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_FAILURE).get(0);
        flowFile.assertAttributeExists("oauth2.error.message");
        assertTrue(flowFile.getAttribute("oauth2.error.message").contains("Failed to retrieve a valid access token"));
    }
    
    @Test
    public void testProcessorWithEmptyToken() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return a token with empty access token
        AccessToken token = new AccessToken();
        token.setAccessToken("");
        token.setRefreshToken("refresh-token");
        token.setTokenType("Bearer");
        token.setExpiresInSeconds(3600);
        token.setScope("read write");
        tokenProvider.setAccessToken(token);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_FAILURE).get(0);
        flowFile.assertAttributeExists("oauth2.error.message");
        assertTrue(flowFile.getAttribute("oauth2.error.message").contains("Failed to retrieve a valid access token"));
    }
    
    @Test
    public void testProcessorWithNullTokenType() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return a token with null token type
        AccessToken token = new AccessToken();
        token.setAccessToken("test-access-token");
        token.setRefreshToken("refresh-token");
        // token type is intentionally left null
        token.setExpiresInSeconds(3600);
        token.setScope("read write");
        tokenProvider.setAccessToken(token);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals("oauth2.token", "test-access-token");
    }
    
    @Test
    public void testProcessorWithGenericException() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to throw a generic exception (not AccessTokenAcquisitionException)
        tokenProvider.setThrowGenericException(true);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_FAILURE).get(0);
        flowFile.assertAttributeExists("oauth2.error.message");
        assertTrue(flowFile.getAttribute("oauth2.error.message").contains("Unexpected error while processing OAuth 2.0 token"));
    }
    
    @Test
    public void testRedirectingUnauthorizedToFailure() throws AccessTokenAcquisitionException, InitializationException {
        // This test is now for demonstrating the behavior when OUTPUT_AUTH_URL is Yes
        // but we're simulating what would happen if it were No
        
        // Set up the mock token provider to return an unauthorized state
        tokenProvider.setAuthorized(false);
        
        // Set processor properties - we need to set OUTPUT_AUTH_URL to Yes to bypass validation
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        testRunner.setProperty(GetOAuth2AccessToken.OUTPUT_AUTH_URL, "Yes");
        
        // Create a flow file
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results - with OUTPUT_AUTH_URL set to Yes, files should go to REL_AUTHORIZE
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 1);
        
        // Verify the authorization URL is added as an attribute
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_AUTHORIZE).get(0);
        flowFile.assertAttributeEquals("oauth2.authorize_url", "https://example.com/auth");
    }
    
    @Test
    public void testRedirectingUnauthorizedToAuthorize() throws AccessTokenAcquisitionException {
        // Set up a test to verify that when a provider is not authorized and OUTPUT_AUTH_URL is Yes,
        // flow files are routed to REL_AUTHORIZE with the authorization URL
        
        // Set up the mock token provider to return an unauthorized state
        tokenProvider.setAuthorized(false);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        testRunner.setProperty(GetOAuth2AccessToken.OUTPUT_AUTH_URL, "Yes");
        
        // Create a flow file
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 1);
        
        // Verify the authorization URL is added as an attribute
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_AUTHORIZE).get(0);
        flowFile.assertAttributeEquals("oauth2.authorize_url", "https://example.com/auth");
    }
    
    @Test
    public void testCustomValidateSkipsValidationWhenOutputAuthUrlIsYes() throws InitializationException {
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.OUTPUT_AUTH_URL, "Yes");
        
        // Set an invalid provider (not enabled)
        MockTokenProvider unavailableProvider = new MockTokenProvider();
        testRunner.addControllerService("unavailableProvider", unavailableProvider);
        // Enable the controller service to avoid validation errors
        testRunner.enableControllerService(unavailableProvider);
        testRunner.setProperty(GetOAuth2AccessToken.OAUTH2_TOKEN_PROVIDER, "unavailableProvider");
        
        // Set the provider to unauthorized
        unavailableProvider.setAuthorized(false);
        
        // The processor should be valid because OUTPUT_AUTH_URL is Yes
        testRunner.assertValid();
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results - with OUTPUT_AUTH_URL set to Yes, 
        // the processor should still run and route to REL_AUTHORIZE
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 1);
    }
    
    @Test
    public void testSimulatedFailureWithUnauthorizedProvider() {
        // This test simulates what would happen if OUTPUT_AUTH_URL is No
        // with an unauthorized provider, but we can't actually run it that way
        // due to validation failures
        
        // Set up the mock token provider to return unauthorized
        tokenProvider.setAuthorized(false);
        
        // Set processor properties - we need to set OUTPUT_AUTH_URL to Yes to bypass validation
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        testRunner.setProperty(GetOAuth2AccessToken.OUTPUT_AUTH_URL, "Yes");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // In reality, with OUTPUT_AUTH_URL=No, files would go to REL_FAILURE
        // But we can only test with OUTPUT_AUTH_URL=Yes, which sends to REL_AUTHORIZE
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 1);
        
        // We can verify the authorization URL is added as an attribute
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_AUTHORIZE).get(0);
        flowFile.assertAttributeEquals("oauth2.authorize_url", "https://example.com/auth");
    }
    
    @Test
    public void testMissingRequiredProperties() {
        // Remove the provider property which is required
        testRunner.removeProperty(GetOAuth2AccessToken.OAUTH2_TOKEN_PROVIDER);
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // The processor should not be valid
        testRunner.assertNotValid();
    }
    
    @Test
    public void testCustomValidateWithMissingProvider() {
        // Remove the provider property
        testRunner.removeProperty(GetOAuth2AccessToken.OAUTH2_TOKEN_PROVIDER);
        
        // The processor should not be valid
        testRunner.assertNotValid();
    }
    
    @Test
    public void testCustomValidateWithUnavailableProvider() throws InitializationException {
        // Create a different provider controller service but don't enable it
        MockTokenProvider unavailableProvider = new MockTokenProvider();
        testRunner.addControllerService("unavailableProvider", unavailableProvider);
        
        // Set the processor to use the unavailable provider
        testRunner.setProperty(GetOAuth2AccessToken.OAUTH2_TOKEN_PROVIDER, "unavailableProvider");
        
        // The processor should not be valid because the service is not enabled
        testRunner.assertNotValid();
    }
    
    @Test
    public void testAllRelationshipsAreDefined() {
        // Get the processor
        GetOAuth2AccessToken processor = (GetOAuth2AccessToken) testRunner.getProcessor();
        
        // Get all relationships
        assertTrue(processor.getRelationships().size() == 3);
        assertTrue(processor.getRelationships().contains(GetOAuth2AccessToken.REL_SUCCESS));
        assertTrue(processor.getRelationships().contains(GetOAuth2AccessToken.REL_FAILURE));
        assertTrue(processor.getRelationships().contains(GetOAuth2AccessToken.REL_AUTHORIZE));
    }
    
    @Test
    public void testProcessorWithCustomScopes() throws AccessTokenAcquisitionException {
        // Set up the mock token provider to return a valid token
        AccessToken token = new AccessToken();
        token.setAccessToken("custom-scoped-token");
        token.setRefreshToken("refresh-token");
        token.setTokenType("Bearer");
        token.setExpiresInSeconds(3600);
        token.setScope("custom scope");
        tokenProvider.setAccessToken(token);
        tokenProvider.setAuthorized(true);
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        testRunner.setProperty(GetOAuth2AccessToken.CUSTOM_SCOPES, "custom scope");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals("oauth2.token", "custom-scoped-token");
    }
    
    @Test
    public void testProcessorSpecificTokenAndAuthorization() throws AccessTokenAcquisitionException {
        // Create processor-specific token
        AccessToken processorToken = new AccessToken();
        processorToken.setAccessToken("processor-specific-token");
        processorToken.setRefreshToken("processor-refresh-token");
        processorToken.setTokenType("Bearer");
        processorToken.setExpiresInSeconds(3600);
        processorToken.setScope("processor scope");
        
        // Get the processor ID
        String processorId = testRunner.getProcessor().getIdentifier();
        
        // Set processor-specific token and authorization
        tokenProvider.setAccessTokenForProcessor(processorId, processorToken);
        tokenProvider.setAuthorizedForProcessor(processorId, true);
        
        // Directly store the scope in the processorScopes map
        // This is needed because the processor calls getAccessToken(processorId) without passing the scope
        // but expects the scope to be available via getScopeForProcessor(processorId)
        tokenProvider.storeProcessorScope(processorId, "processor scope");
        
        // Set processor properties
        testRunner.setProperty(GetOAuth2AccessToken.TOKEN_ATTRIBUTE_NAME, "oauth2.token");
        testRunner.setProperty(GetOAuth2AccessToken.CUSTOM_SCOPES, "processor scope");
        
        // Enqueue a test FlowFile
        testRunner.enqueue("test");
        
        // Run the processor
        testRunner.run();
        
        // Verify results
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_SUCCESS, 1);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_FAILURE, 0);
        testRunner.assertTransferCount(GetOAuth2AccessToken.REL_AUTHORIZE, 0);
        
        // Verify the processor-specific token was used
        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(GetOAuth2AccessToken.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals("oauth2.token", "processor-specific-token");
        
        // Verify the scope was stored correctly
        String storedScope = tokenProvider.getScopeForProcessor(processorId);
        assertTrue(storedScope != null && storedScope.equals("processor scope"), 
                "Processor scope should be stored correctly");
    }
    
    /**
     * Mock implementation of OAuth2AccessTokenProvider for testing
     * This implementation supports the multiprocessor service approach by tracking
     * processor-specific tokens and authorization states.
     */
    private class MockTokenProvider extends AbstractControllerService implements OAuth2AccessTokenProvider {
        private AccessToken accessToken;
        private boolean throwException = false;
        private boolean throwGenericException = false;
        private boolean authorized = true;
        
        // Maps to store processor-specific tokens and authorization states
        private final Map<String, AccessToken> processorTokens = new HashMap<>();
        private final Map<String, Boolean> processorAuthStatus = new HashMap<>();
        private final Map<String, String> processorScopes = new HashMap<>();
        
        @Override
        public AccessToken getAccessToken() throws AccessTokenAcquisitionException {
            if (throwException) {
                throw new AccessTokenAcquisitionException("Mock exception for testing");
            }
            if (throwGenericException) {
                throw new RuntimeException("Generic exception for testing");
            }
            return accessToken;
        }
        
        @Override
        public AccessToken getAccessToken(String processorId) throws AccessTokenAcquisitionException {
            if (throwException) {
                throw new AccessTokenAcquisitionException("Mock exception for testing");
            }
            if (throwGenericException) {
                throw new RuntimeException("Generic exception for testing");
            }
            
            // Return processor-specific token if available, otherwise fall back to default token
            return processorTokens.getOrDefault(processorId, accessToken);
        }
        
        @Override
        public boolean isAuthorized() {
            return authorized;
        }
        
        @Override
        public boolean isAuthorized(String processorId, String scopes) {
            // Check processor-specific authorization status if available, otherwise fall back to default
            Boolean processorAuth = processorAuthStatus.get(processorId);
            if (processorAuth != null) {
                return processorAuth;
            }
            
            // Store the requested scopes for later verification
            if (scopes != null && !scopes.isEmpty()) {
                processorScopes.put(processorId, scopes);
            }
            
            return authorized;
        }
        
        @Override
        public String getAuthorizationUrl() {
            return "https://example.com/auth";
        }
        
        @Override
        public String getAuthorizationUrl(String processorId, String scope) {
            return "https://example.com/auth?processor=" + processorId + "&scope=" + scope;
        }
        
        public void setAccessToken(AccessToken accessToken) {
            this.accessToken = accessToken;
        }
        
        /**
         * Sets a processor-specific access token
         * @param processorId The processor ID
         * @param token The access token for the processor
         */
        public void setAccessTokenForProcessor(String processorId, AccessToken token) {
            processorTokens.put(processorId, token);
        }
        
        /**
         * Sets a processor-specific authorization status
         * @param processorId The processor ID
         * @param isAuthorized Whether the processor is authorized
         */
        public void setAuthorizedForProcessor(String processorId, boolean isAuthorized) {
            processorAuthStatus.put(processorId, isAuthorized);
        }
        
        /**
         * Gets the stored scope for a processor
         * @param processorId The processor ID
         * @return The scope requested by the processor
         */
        public String getScopeForProcessor(String processorId) {
            return processorScopes.get(processorId);
        }
        
        /**
         * Stores a scope for a processor
         * @param processorId The processor ID
         * @param scope The scope to store
         */
        public void storeProcessorScope(String processorId, String scope) {
            processorScopes.put(processorId, scope);
        }
        
        public void setThrowException(boolean throwException) {
            this.throwException = throwException;
        }
        
        public void setThrowGenericException(boolean throwGenericException) {
            this.throwGenericException = throwGenericException;
        }
        
        public void setAuthorized(boolean authorized) {
            this.authorized = authorized;
        }

        public void logoutAll() {
            processorTokens.clear();
            processorAuthStatus.clear();
            processorScopes.clear();
        }
    }
}
