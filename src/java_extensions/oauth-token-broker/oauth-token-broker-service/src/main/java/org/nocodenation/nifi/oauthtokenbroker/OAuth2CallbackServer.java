package org.nocodenation.nifi.oauthtokenbroker;

import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.ssl.SSLContextService;
import org.apache.nifi.jetty.configuration.connector.StandardServerConnectorFactory;
import org.apache.nifi.jetty.configuration.connector.ApplicationLayerProtocol;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.net.ssl.SSLContext;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight HTTP server that handles OAuth 2.0 authorization code callbacks.
 * <p>
 * This server listens for incoming requests at the specified redirect URI and processes
 * OAuth 2.0 authorization code callbacks. When an authorization code is received,
 * it delegates the processing to an {@link OAuth2CallbackServlet} which extracts the
 * authorization code and state parameters, validates them, and forwards them to the
 * appropriate {@link OAuth2AccessTokenProvider} for token acquisition.
 * <p>
 * The server supports the complete OAuth 2.0 authorization code flow with PKCE (Proof Key for Code Exchange)
 * according to RFC 7636 for enhanced security against authorization code interception attacks. PKCE values
 * are generated once during service enablement and reused consistently throughout the entire OAuth flow.
 * <p>
 * The callback server supports multiple processors sharing a single OAuth2AccessTokenService instance
 * by using the state parameter to identify which processor the callback is for. This allows each processor
 * to maintain its own OAuth state and tokens while sharing the same callback server.
 * <p>
 * The server uses Jetty for HTTP/HTTPS support with optional SSL Context Service integration
 * for secure communication. It supports customizable port configuration for testing
 * purposes through the system property {@code org.nocodenation.nifi.oauthtokenbroker.test.port}.
 * <p>
 * Error handling includes proper HTTP response codes, content-type headers, and
 * HTML templates for user-friendly error messages.
 * 
 * @see OAuth2CallbackServlet
 * @see OAuth2AccessTokenProvider
 */
public class OAuth2CallbackServer {

    private final Server server;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ComponentLog logger;
    private final OAuth2CallbackServlet servlet;
    private int port;
    private final String path;

    /**
     * Creates a new OAuth 2.0 callback server bound to the specified port and path.
     * <p>
     * The server will listen on the specified port and handle requests at the specified path.
     * It processes OAuth 2.0 authorization code callbacks and forwards them to the appropriate
     * OAuth2AccessTokenService for token acquisition.
     * <p>
     * The server extracts the state parameter from the callback URL to identify which processor
     * the callback is for, allowing multiple processors to share a single OAuth2AccessTokenService
     * instance while maintaining separate OAuth states and tokens.
     * <p>
     * For testing purposes, the port can be overridden using the system property
     * {@code org.nocodenation.nifi.oauthtokenbroker.test.port}.
     *
     * @param provider The OAuth2AccessTokenService that will process authorization codes
     * @param port The port to listen on
     * @param callbackPath The path to handle callbacks on (e.g., "/oauth/callback")
     * @param logger The NiFi component logger for logging server events
     * @param sslContextService The SSL Context Service for HTTPS support, or null for HTTP
     * @throws Exception if there's an error creating the server, such as port binding issues
     *                   or invalid URI format
     */
    public OAuth2CallbackServer(OAuth2AccessTokenService provider,
                                int port,
                                String callbackPath,
                                ComponentLog logger,
                                SSLContextService sslContextService) throws Exception {
        this.logger = logger;
        this.servlet = new OAuth2CallbackServlet(provider, logger);
        
        // Allow overriding the port with a system property for testing
        String testPortProperty = System.getProperty("org.nocodenation.nifi.oauthtokenbroker.test.port");
        if (testPortProperty != null && !testPortProperty.isEmpty()) {
            try {
                int testPort = Integer.parseInt(testPortProperty);
                logger.debug("Using test port: {}", testPort);
                this.port = testPort;
            } catch (NumberFormatException e) {
                logger.warn("Invalid test port: {}", testPortProperty);
                this.port = port;
            }
        } else {
            this.port = port;
            logger.debug("Callback server port: {}", this.port);
        }
        
        // Ensure the path starts with a forward slash
        this.path = callbackPath.startsWith("/") ? callbackPath : "/" + callbackPath;
        
        // Create a thread pool for the Jetty server
        final QueuedThreadPool threadPool = new QueuedThreadPool(50);
        threadPool.setName("OAuth2-Callback-Server");
        
        // Create the server instance
        this.server = new Server(threadPool);
        
        // Create and configure the server connector
        final StandardServerConnectorFactory serverConnectorFactory = new StandardServerConnectorFactory(server, this.port);
        
        // Set request header size to 8KB (same as ListenHTTP default)
        serverConnectorFactory.setRequestHeaderSize(8192);
        
        // Configure SSL if a context service is available
        if (sslContextService != null) {
            final SSLContext sslContext = sslContextService.createContext();
            serverConnectorFactory.setSslContext(sslContext);
            
            // Use only HTTP/1.1 for simplicity
            serverConnectorFactory.setApplicationLayerProtocols(
                    Collections.singleton(ApplicationLayerProtocol.HTTP_1_1));
            
            // Get the enabled protocols from the SSL context
            final String[] enabledProtocols = sslContext.getDefaultSSLParameters().getProtocols();
            serverConnectorFactory.setIncludeSecurityProtocols(enabledProtocols);
            
            logger.info("Configured OAuth2 callback server with SSL support");
        }
        
        // Get the server connector
        final ServerConnector connector = serverConnectorFactory.getServerConnector();
        
        // Set the host to 0.0.0.0 to listen on all network interfaces
        connector.setHost("0.0.0.0");
        
        server.addConnector(connector);
        
        // Create a servlet context handler
        final ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("");
        
        // Register our servlet
        final ServletHolder servletHolder = new ServletHolder(servlet);
        contextHandler.addServlet(servletHolder, this.path + "/*");
        
        server.setHandler(contextHandler);
        
        logger.info("Created OAuth2 callback server on port {} with path {}", this.port, this.path);
    }

    /**
     * Starts the callback server if it's not already running.
     * <p>
     * This method is thread-safe and will only start the server if it's not already
     * running. Once started, the server will listen for incoming HTTP requests at the
     * configured port and path.
     * <p>
     * The server handles OAuth 2.0 authorization code callbacks with PKCE support,
     * extracting the state parameter to identify which processor the callback is for.
     * This enables multiple processors to share a single OAuth2AccessTokenService
     * instance while maintaining separate OAuth states and tokens.
     *
     * @throws Exception if there's an error starting the server
     */
    public void start() throws Exception {
        if (started.compareAndSet(false, true)) {
            server.start();
            logger.info("OAuth2 callback server started on port {}", port);
        }
    }

    /**
     * Stops the callback server if it's currently running.
     * <p>
     * This method is thread-safe and will only stop the server if it's currently
     * running. The method will immediately stop the server without waiting for
     * ongoing requests to complete.
     * <p>
     * When the server is stopped, it will no longer accept OAuth 2.0 authorization code
     * callbacks. Any ongoing OAuth 2.0 flows that have not yet received a callback
     * will not be completed until the server is started again.
     *
     * @throws Exception if there's an error stopping the server
     */
    public void stop() throws Exception {
        if (started.compareAndSet(true, false)) {
            server.stop();
            server.destroy();
            logger.info("OAuth2 callback server stopped");
        }
    }

    /**
     * Checks if the server is currently running.
     * <p>
     * This method can be used to determine if the server is available to handle
     * OAuth 2.0 authorization code callbacks. When the server is running, it is
     * ready to process callbacks from OAuth providers as part of the authorization
     * flow with PKCE support.
     *
     * @return {@code true} if the server is running, {@code false} otherwise
     */
    public boolean isRunning() {
        return started.get();
    }
}
