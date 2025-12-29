package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.logging.ComponentLog;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Helper class for mocking the OAuth2CallbackServer in tests.
 * This prevents tests from starting actual HTTP servers.
 */
public class MockOAuth2ServerHelper {

    /**
     * Creates a mock OAuth2CallbackServer that doesn't actually start an HTTP server.
     * This is useful for testing OAuth2AccessTokenService without binding to ports.
     *
     * @param provider The OAuth2AccessTokenService to associate with the mock server
     * @param port The port to pretend to listen on
     * @param callbackPath The callback path
     * @param logger The component logger
     * @return A mock OAuth2CallbackServer that doesn't actually start
     * @throws Exception If there's an error creating the mock
     */
    public static OAuth2CallbackServer createMockServer(
            OAuth2AccessTokenService provider,
            int port,
            String callbackPath,
            ComponentLog logger) throws Exception {
        
        // Create a mock server that doesn't actually start
        OAuth2CallbackServer mockServer = Mockito.mock(OAuth2CallbackServer.class);
        
        // Make isRunning() return true when called
        Mockito.when(mockServer.isRunning()).thenReturn(true);
        
        // Make start() and stop() do nothing
        Mockito.doNothing().when(mockServer).start();
        Mockito.doNothing().when(mockServer).stop();
        
        return mockServer;
    }
    
    /**
     * Injects a mock OAuth2CallbackServer into an OAuth2AccessTokenService instance.
     * This prevents the service from starting a real HTTP server during tests.
     *
     * @param service The OAuth2AccessTokenService to modify
     * @param port The port to pretend to listen on
     * @param callbackPath The callback path
     * @throws Exception If there's an error injecting the mock
     */
    public static void injectMockServerIntoService(
            OAuth2AccessTokenService service,
            int port,
            String callbackPath) throws Exception {
        
        // Get the logger using reflection
        Method getLoggerMethod = OAuth2AccessTokenService.class.getDeclaredMethod("getLogger");
        getLoggerMethod.setAccessible(true);
        ComponentLog logger = (ComponentLog) getLoggerMethod.invoke(service);
        
        // Create a mock server
        OAuth2CallbackServer mockServer = createMockServer(
                service, 
                port, 
                callbackPath, 
                logger);
        
        // Use reflection to set the callbackServer field
        Field callbackServerField = OAuth2AccessTokenService.class.getDeclaredField("callbackServer");
        callbackServerField.setAccessible(true);
        callbackServerField.set(service, mockServer);
    }
    
    /**
     * Completes the initialization of an OAuth2AccessTokenService without starting a real server.
     * This method should be called after the service properties are set but before it's enabled.
     *
     * @param service The OAuth2AccessTokenService to initialize
     * @throws Exception If there's an error initializing the service
     */
    public static void initializeServiceWithoutServer(OAuth2AccessTokenService service) throws Exception {
        // Generate PKCE values for the service
        String providerId = service.getIdentifier();
        
        // Use reflection to access the pkceValuesMap field
        Field pkceValuesMapField = OAuth2AccessTokenService.class.getDeclaredField("pkceValuesMap");
        pkceValuesMapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, PkceUtils.PkceValues> pkceValuesMap = 
                (java.util.Map<String, PkceUtils.PkceValues>) pkceValuesMapField.get(service);
        
        // Add PKCE values for the provider ID
        PkceUtils.PkceValues pkceValues = PkceUtils.generatePkceValues();
        pkceValuesMap.put(providerId, pkceValues);
        
        // Also add PKCE values for the service state
        Method getServiceStateMethod = OAuth2AccessTokenService.class.getDeclaredMethod("getServiceState");
        getServiceStateMethod.setAccessible(true);
        String serviceState = (String) getServiceStateMethod.invoke(service);
        pkceValuesMap.put(serviceState, pkceValues);
    }
}
