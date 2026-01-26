package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.logging.ComponentLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestOAuth2CallbackServlet {

    @Mock
    private ComponentLog mockLogger;

    @Mock
    private OAuth2AccessTokenService mockTokenProvider;

    private OAuth2CallbackServlet servlet;
    private ByteArrayOutputStream outputStream;
    private String providerId = "test-provider";
    
    @BeforeEach
    public void setup() throws IOException {
        servlet = new OAuth2CallbackServlet(mockTokenProvider, mockLogger);
        outputStream = new ByteArrayOutputStream();
    }

    @Test
    public void testHandleCallbackWithAuthorizationCode() throws IOException, AccessTokenAcquisitionException {
        String code = "test-auth-code";
        
        // Execute request with authorization code
        String requestUri = "/oauth/callback?state=" + providerId + "&code=" + code;
        servlet.handleCallback(requestUri, outputStream);
        
        // Verify interactions
        verify(mockTokenProvider).processAuthorizationCode(eq(providerId), eq(code));
        
        // Verify success response
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Authorization Successful"));
    }
    
    @Test
    public void testHandleCallbackWithError() throws IOException, AccessTokenAcquisitionException {
        // Create request with error
        String requestUri = "/oauth/callback?state=" + providerId + "&error=access_denied&error_description=User+denied+access";
        servlet.handleCallback(requestUri, outputStream);
        
        // Verify provider not called
        verify(mockTokenProvider, never()).processAuthorizationCode(anyString(), anyString());
        
        // Verify error response
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Authorization Error"));
        assertTrue(response.contains("access_denied"));
    }
    
    @Test
    public void testHandleCallbackWithMissingCode() throws IOException, AccessTokenAcquisitionException {
        // Create request without code
        String requestUri = "/oauth/callback?state=" + providerId;
        servlet.handleCallback(requestUri, outputStream);
        
        // Verify provider not called
        verify(mockTokenProvider, never()).processAuthorizationCode(anyString(), anyString());
        
        // Verify error response
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Missing authorization code"));
    }
    
    @Test
    public void testHandleCallbackWithMissingState() throws IOException, AccessTokenAcquisitionException {
        // Create request without state
        String requestUri = "/oauth/callback?code=test-code";
        servlet.handleCallback(requestUri, outputStream);
        
        // Verify error response
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Missing state parameter"));
    }
    
    @Test
    public void testHandleCallbackWithInvalidState() throws IOException, AccessTokenAcquisitionException {
        // Setup provider to throw exception for invalid state
        doThrow(new AccessTokenAcquisitionException("State mismatch in OAuth callback"))
            .when(mockTokenProvider).processAuthorizationCode(eq("invalid-state"), anyString());
            
        // Create request with invalid state
        String requestUri = "/oauth/callback?state=invalid-state&code=test-code";
        servlet.handleCallback(requestUri, outputStream);
        
        // Verify error response
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Failed to exchange authorization code for tokens: State mismatch"));
    }
    
    @Test
    public void testHandleCallbackWithTokenException() throws IOException, AccessTokenAcquisitionException {
        // Setup provider to throw exception
        doThrow(new AccessTokenAcquisitionException("Test error")).when(mockTokenProvider)
                .processAuthorizationCode(eq(providerId), eq("test-code"));
        
        // Create request with code
        String requestUri = "/oauth/callback?state=" + providerId + "&code=test-code";
        servlet.handleCallback(requestUri, outputStream);
        
        // Verify error response
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Failed to exchange authorization code"));
        assertTrue(response.contains("Test error"));
    }
    
    @Test
    public void testParseQueryParams() {
        // Test with simple parameter
        String query = "param1=value1&param2=value2";
        Map<String, String> params = servlet.parseQueryParams(query);
        
        assertEquals(2, params.size());
        assertEquals("value1", params.get("param1"));
        assertEquals("value2", params.get("param2"));
    }
    
    @Test
    public void testParseQueryParamsWithUrlEncoding() {
        // Test with URL encoded parameters
        String query = "param1=value%201&param2=value%26with%3Dspecial%2Bchars";
        Map<String, String> params = servlet.parseQueryParams(query);
        
        assertEquals(2, params.size());
        assertEquals("value 1", params.get("param1"));
        assertEquals("value&with=special+chars", params.get("param2"));
    }
    
    @Test
    public void testParseQueryParamsWithNoParams() {
        // Test with no parameters
        String uri = "/oauth/callback";
        Map<String, String> params = servlet.parseQueryParams(uri);
        
        assertTrue(params.isEmpty());
    }
    
    @Test
    public void testParseQueryParamsWithEmptyParams() {
        // Test with empty parameters
        String query = "param1=&param2=";
        Map<String, String> params = servlet.parseQueryParams(query);
        
        assertEquals(2, params.size());
        assertEquals("", params.get("param1"));
        assertEquals("", params.get("param2"));
    }
    
    @Test
    public void testParseQueryParamsWithInvalidUri() {
        // Test with invalid URI
        String uri = null;
        Map<String, String> params = servlet.parseQueryParams(uri);
        
        assertTrue(params.isEmpty());
    }
}
