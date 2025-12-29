package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.logging.ComponentLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TestOAuth2CallbackServer {

    @Mock
    private ComponentLog mockLogger;

    @Mock
    private OAuth2AccessTokenService mockTokenProvider;

    private OAuth2CallbackServer server;
    private int availablePort;

    @BeforeEach
    public void setup() throws Exception {
        // Get a random available port for testing
        try (ServerSocket socket = new ServerSocket(0)) {
            availablePort = socket.getLocalPort();
        }
        
        // Clear any previously set system property to ensure test isolation
        System.clearProperty("org.nocodenation.nifi.oauthtokenbroker.test.port");
    }

    @AfterEach
    public void cleanup() throws Exception {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    public void testServerCreation() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider, availablePort, callbackPath, mockLogger, null);
        
        // Verify the logger was called with the correct information
        verify(mockLogger).info(contains("Created OAuth2 callback server"), eq(availablePort), eq(callbackPath));
        
        // Verify the server exists but is not running yet
        assertFalse(server.isRunning());
    }

    @Test
    public void testServerCreationWithDefaultPort() throws Exception {
        // For this test, we use a fixed port (not actually binding) and verify the path handling
        int port = 80;
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,port, callbackPath, mockLogger, null);
        
        // Verify the logger was called with the correct information
        verify(mockLogger).info(contains("Created OAuth2 callback server"), eq(port), eq(callbackPath));
        
        // Verify the server exists but is not running yet
        assertFalse(server.isRunning());
    }

    @Test
    public void testServerCreationWithHttpsPort() throws Exception {
        // For this test, we use a fixed port (not actually binding) and verify the path handling
        int port = 443;
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,port, callbackPath, mockLogger, null);
        
        // Verify the logger was called with the correct information
        verify(mockLogger).info(contains("Created OAuth2 callback server"), eq(port), eq(callbackPath));
        
        // Verify the server exists but is not running yet
        assertFalse(server.isRunning());
    }

    @Test
    public void testServerCreationWithCustomPath() throws Exception {
        // Test with a custom path
        String callbackPath = "/custom/oauth/path";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        // Verify the logger was called with the correct information
        verify(mockLogger).info(contains("Created OAuth2 callback server"), eq(availablePort), eq(callbackPath));
        
        // Verify the server exists but is not running yet
        assertFalse(server.isRunning());
    }

    @Test
    public void testServerCreationWithPathWithoutLeadingSlash() throws Exception {
        // Test with a path that doesn't have a leading slash
        String callbackPath = "oauth/callback";
        String expectedPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        // Verify the logger was called with the correct information
        verify(mockLogger).info(contains("Created OAuth2 callback server"), eq(availablePort), eq(expectedPath));
        
        // Verify the server exists but is not running yet
        assertFalse(server.isRunning());
    }

    @Test
    public void testServerStart() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        server.start();
        
        // Verify the server is running
        assertTrue(server.isRunning());
        
        // Verify the logger was called
        verify(mockLogger).info(eq("OAuth2 callback server started on port {}"), eq(availablePort));
    }

    @Test
    public void testServerStop() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        server.start();
        assertTrue(server.isRunning());
        
        server.stop();
        
        // Verify the server is not running
        assertFalse(server.isRunning());
        
        // Verify the logger was called
        verify(mockLogger).info("OAuth2 callback server stopped");
    }

    @Test
    public void testServerMultipleStartCalls() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        // Start server once
        server.start();
        assertTrue(server.isRunning());
        
        // Start server again (should be a no-op)
        server.start();
        
        // Verify the logger was called only once for server start
        verify(mockLogger, times(1)).info(eq("OAuth2 callback server started on port {}"), eq(availablePort));
    }

    @Test
    public void testServerMultipleStopCalls() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        // Start and stop server once
        server.start();
        server.stop();
        assertFalse(server.isRunning());
        
        // Stop server again (should be a no-op)
        server.stop();
        
        // Verify the logger was called only once for server stop
        verify(mockLogger, times(1)).info("OAuth2 callback server stopped");
    }

    @Test
    public void testInvalidRedirectUri() {
        // Test with a null path which should cause an exception
        assertThrows(Exception.class, () -> {
            new OAuth2CallbackServer(mockTokenProvider,availablePort, null, mockLogger, null);
        });
    }

    @Test
    public void testServerRespondsToRequest() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        server.start();
        
        // Give the server a moment to fully start
        TimeUnit.MILLISECONDS.sleep(500);
        
        try {
            // Test that the server is responding to requests
            URL url = URI.create("http://localhost:" + availablePort + callbackPath).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            
            int responseCode = connection.getResponseCode();
            // We expect some kind of response, even if it's an error response
            // The specific response is handled by the OAuth2CallbackServlet, which is tested separately
            assertTrue(responseCode >= 200 && responseCode < 600);
        } catch (IOException e) {
            fail("Server is not responding to requests: " + e.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/oauth/callback",
        "/callback",
        "/auth/oauth2/callback",
        "/"
    })
    public void testDifferentPathConfigurations(String callbackPath) throws Exception {
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        server.start();
        
        // Verify the server is running
        assertTrue(server.isRunning());
        
        // Verify the logger was called with the correct path
        verify(mockLogger).info(contains("Created OAuth2 callback server"), eq(availablePort), eq(callbackPath));
        
        server.stop();
    }

    @Test
    public void testIsRunningReflectsActualState() throws Exception {
        String callbackPath = "/oauth/callback";
        server = new OAuth2CallbackServer(mockTokenProvider,availablePort, callbackPath, mockLogger, null);
        
        // Check initial state
        assertFalse(server.isRunning());
        
        // Start and check running state
        server.start();
        assertTrue(server.isRunning());
        
        // Stop and check state again
        server.stop();
        assertFalse(server.isRunning());
    }
}
