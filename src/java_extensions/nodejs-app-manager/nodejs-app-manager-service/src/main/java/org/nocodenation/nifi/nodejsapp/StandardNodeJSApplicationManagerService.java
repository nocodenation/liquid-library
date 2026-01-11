/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.nocodenation.nifi.nodejsapp;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.components.Validator;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.ssl.SSLContextService;

import java.io.File;
import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Tags({"nocodenation", "nodejs", "node", "javascript", "application", "process", "management"})
@CapabilityDescription("Manages the lifecycle of a Node.js application, providing start/stop control, " +
        "health monitoring, and log capture capabilities. The service launches the Node.js application " +
        "as a child process and monitors its health, with optional auto-restart on failure.")
public class StandardNodeJSApplicationManagerService extends AbstractControllerService
        implements NodeJSApplicationManagerService {

    // Application Configuration Properties
    public static final PropertyDescriptor APPLICATION_PATH = new PropertyDescriptor.Builder()
            .name("application-path")
            .displayName("Application Path")
            .description("Absolute path to the Node.js application directory (containing package.json)")
            .required(true)
            .addValidator(new DirectoryExistsValidator())
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor NODE_EXECUTABLE = new PropertyDescriptor.Builder()
            .name("node-executable")
            .displayName("Node Executable Path")
            .description("Path to the Node.js executable. If not specified, 'node' from system PATH will be used.")
            .required(false)
            .defaultValue("node")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor PACKAGE_MANAGER = new PropertyDescriptor.Builder()
            .name("package-manager")
            .displayName("Package Manager")
            .description("Package manager to use for running the application. If set to 'Auto', the service " +
                    "will detect the package manager from lock files (bun.lock, pnpm-lock.yaml, yarn.lock, " +
                    "or package-lock.json in that order).")
            .required(true)
            .defaultValue("Auto")
            .allowableValues("Auto", "npm", "yarn", "pnpm", "bun")
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor START_COMMAND = new PropertyDescriptor.Builder()
            .name("start-command")
            .displayName("Start Command")
            .description("The npm/yarn/pnpm/bun script to execute (e.g., 'start', 'dev', 'prod'). " +
                    "This should match a script defined in package.json.")
            .required(true)
            .defaultValue("start")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor APPLICATION_PORT = new PropertyDescriptor.Builder()
            .name("application-port")
            .displayName("Application Port")
            .description("Port number on which the Node.js application listens. Used for health checks " +
                    "and constructing the application URL.")
            .required(true)
            .defaultValue("3000")
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor APPLICATION_HOST = new PropertyDescriptor.Builder()
            .name("application-host")
            .displayName("Application Host")
            .description("Hostname or IP address on which the Node.js application listens.")
            .required(true)
            .defaultValue("localhost")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    // Environment Variables
    public static final PropertyDescriptor ENVIRONMENT_VARIABLES = new PropertyDescriptor.Builder()
            .name("environment-variables")
            .displayName("Environment Variables")
            .description("Environment variables to set for the Node.js process, in the format: " +
                    "KEY1=value1;KEY2=value2. These are merged with system environment variables.")
            .required(false)
            .addValidator(Validator.VALID)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    public static final PropertyDescriptor NODE_ENV = new PropertyDescriptor.Builder()
            .name("node-env")
            .displayName("NODE_ENV")
            .description("Value for the NODE_ENV environment variable (development, production, test, etc.)")
            .required(false)
            .defaultValue("production")
            .allowableValues("development", "production", "test")
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .build();

    // Health Check Configuration
    public static final PropertyDescriptor ENABLE_HEALTH_CHECK = new PropertyDescriptor.Builder()
            .name("enable-health-check")
            .displayName("Enable Health Check")
            .description("Enable HTTP-based health checking of the Node.js application")
            .required(true)
            .defaultValue("true")
            .allowableValues("true", "false")
            .build();

    public static final PropertyDescriptor HEALTH_CHECK_PATH = new PropertyDescriptor.Builder()
            .name("health-check-path")
            .displayName("Health Check Path")
            .description("HTTP endpoint path for health checks (e.g., '/health', '/api/health'). " +
                    "The endpoint should return HTTP 200 when healthy.")
            .required(false)
            .defaultValue("/health")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
            .dependsOn(ENABLE_HEALTH_CHECK, "true")
            .build();

    public static final PropertyDescriptor HEALTH_CHECK_INTERVAL = new PropertyDescriptor.Builder()
            .name("health-check-interval")
            .displayName("Health Check Interval")
            .description("How frequently to perform health checks")
            .required(false)
            .defaultValue("30 sec")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .dependsOn(ENABLE_HEALTH_CHECK, "true")
            .build();

    public static final PropertyDescriptor HEALTH_CHECK_TIMEOUT = new PropertyDescriptor.Builder()
            .name("health-check-timeout")
            .displayName("Health Check Timeout")
            .description("Maximum time to wait for a health check response before considering it failed")
            .required(false)
            .defaultValue("5 sec")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .dependsOn(ENABLE_HEALTH_CHECK, "true")
            .build();

    // Process Lifecycle Configuration
    public static final PropertyDescriptor STARTUP_TIMEOUT = new PropertyDescriptor.Builder()
            .name("startup-timeout")
            .displayName("Startup Timeout")
            .description("Maximum time to wait for the application to start and become healthy")
            .required(true)
            .defaultValue("60 sec")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor SHUTDOWN_TIMEOUT = new PropertyDescriptor.Builder()
            .name("shutdown-timeout")
            .displayName("Shutdown Timeout")
            .description("Maximum time to wait for graceful shutdown (SIGTERM) before forcing termination (SIGKILL)")
            .required(true)
            .defaultValue("30 sec")
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .build();

    public static final PropertyDescriptor AUTO_RESTART = new PropertyDescriptor.Builder()
            .name("auto-restart")
            .displayName("Auto Restart on Failure")
            .description("Automatically restart the application if it crashes or becomes unhealthy")
            .required(true)
            .defaultValue("true")
            .allowableValues("true", "false")
            .build();

    public static final PropertyDescriptor MAX_RESTART_ATTEMPTS = new PropertyDescriptor.Builder()
            .name("max-restart-attempts")
            .displayName("Max Restart Attempts")
            .description("Maximum number of restart attempts before giving up. Restart counter resets " +
                    "after the application runs successfully for 5 minutes.")
            .required(false)
            .defaultValue("3")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .dependsOn(AUTO_RESTART, "true")
            .build();

    // Logging Configuration
    public static final PropertyDescriptor LOG_BUFFER_SIZE = new PropertyDescriptor.Builder()
            .name("log-buffer-size")
            .displayName("Log Buffer Size")
            .description("Maximum number of log lines to buffer from the Node.js application (stdout/stderr)")
            .required(true)
            .defaultValue("1000")
            .addValidator(StandardValidators.POSITIVE_INTEGER_VALIDATOR)
            .build();

    // SSL Configuration for Health Checks
    public static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("ssl-context-service")
            .displayName("SSL Context Service")
            .description("SSL Context Service for HTTPS health checks. If configured, health checks will use " +
                    "HTTPS instead of HTTP to monitor the Node.js application.")
            .required(false)
            .identifiesControllerService(SSLContextService.class)
            .dependsOn(ENABLE_HEALTH_CHECK, "true")
            .build();

    private static final List<PropertyDescriptor> PROPERTY_DESCRIPTORS;

    static {
        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(APPLICATION_PATH);
        props.add(NODE_EXECUTABLE);
        props.add(PACKAGE_MANAGER);
        props.add(START_COMMAND);
        props.add(APPLICATION_PORT);
        props.add(APPLICATION_HOST);
        props.add(ENVIRONMENT_VARIABLES);
        props.add(NODE_ENV);
        props.add(ENABLE_HEALTH_CHECK);
        props.add(HEALTH_CHECK_PATH);
        props.add(HEALTH_CHECK_INTERVAL);
        props.add(HEALTH_CHECK_TIMEOUT);
        props.add(STARTUP_TIMEOUT);
        props.add(SHUTDOWN_TIMEOUT);
        props.add(AUTO_RESTART);
        props.add(MAX_RESTART_ATTEMPTS);
        props.add(LOG_BUFFER_SIZE);
        props.add(SSL_CONTEXT_SERVICE);
        PROPERTY_DESCRIPTORS = Collections.unmodifiableList(props);
    }

    // Service state
    private volatile ProcessLifecycleManager lifecycleManager;
    private volatile ProcessMonitor processMonitor;
    private volatile LogCapture logCapture;

    // Configuration cache
    private volatile int applicationPort;
    private volatile String applicationHost;

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTY_DESCRIPTORS;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws ProcessManagementException {
        getLogger().info("Enabling NodeJS Application Manager Service");

        try {
            // Store configuration for later access
            this.applicationPort = context.getProperty(APPLICATION_PORT).evaluateAttributeExpressions().asInteger();
            this.applicationHost = context.getProperty(APPLICATION_HOST).evaluateAttributeExpressions().getValue();

            // Initialize log capture
            final int logBufferSize = context.getProperty(LOG_BUFFER_SIZE).asInteger();
            this.logCapture = new LogCapture(logBufferSize);

            // Initialize lifecycle manager
            this.lifecycleManager = new ProcessLifecycleManager(context, logCapture, getLogger());

            // Start the Node.js application
            lifecycleManager.startApplication();

            // Initialize and start process monitor if health checks are enabled
            if (context.getProperty(ENABLE_HEALTH_CHECK).asBoolean()) {
                final long healthCheckIntervalMs = context.getProperty(HEALTH_CHECK_INTERVAL)
                        .asTimePeriod(TimeUnit.MILLISECONDS);
                final long healthCheckTimeoutMs = context.getProperty(HEALTH_CHECK_TIMEOUT)
                        .asTimePeriod(TimeUnit.MILLISECONDS);
                final boolean autoRestart = context.getProperty(AUTO_RESTART).asBoolean();
                final int maxRestartAttempts = context.getProperty(MAX_RESTART_ATTEMPTS).asInteger();

                // Get SSL context if configured
                final SSLContext sslContext;
                if (context.getProperty(SSL_CONTEXT_SERVICE).isSet()) {
                    final SSLContextService sslContextService = context.getProperty(SSL_CONTEXT_SERVICE)
                            .asControllerService(SSLContextService.class);
                    sslContext = sslContextService.createContext();
                    getLogger().info("Health checks will use HTTPS with SSL Context Service");
                } else {
                    sslContext = null;
                    getLogger().info("Health checks will use HTTP (no SSL Context Service configured)");
                }

                this.processMonitor = new ProcessMonitor(
                        lifecycleManager,
                        context.getProperty(APPLICATION_HOST).evaluateAttributeExpressions().getValue(),
                        context.getProperty(APPLICATION_PORT).evaluateAttributeExpressions().asInteger(),
                        context.getProperty(HEALTH_CHECK_PATH).evaluateAttributeExpressions().getValue(),
                        healthCheckIntervalMs,
                        healthCheckTimeoutMs,
                        autoRestart,
                        maxRestartAttempts,
                        getLogger(),
                        sslContext
                );

                processMonitor.start();
            }

            getLogger().info("NodeJS Application Manager Service enabled successfully");

        } catch (Exception e) {
            getLogger().error("Failed to enable NodeJS Application Manager Service", e);
            cleanup();
            throw new ProcessManagementException("Failed to start Node.js application", e);
        }
    }

    @OnDisabled
    public void onDisabled() {
        getLogger().info("Disabling NodeJS Application Manager Service");
        cleanup();
        getLogger().info("NodeJS Application Manager Service disabled");
    }

    @OnShutdown
    public void onShutdown() {
        getLogger().info("Shutting down NodeJS Application Manager Service");
        cleanup();
        getLogger().info("NodeJS Application Manager Service shutdown complete");
    }

    /**
     * Cleanup resources. Synchronized to prevent concurrent cleanup from onDisabled() and onShutdown().
     * This method is idempotent - can be called multiple times safely.
     */
    private synchronized void cleanup() {
        // Stop process monitor
        if (processMonitor != null) {
            try {
                processMonitor.stop();
            } catch (Exception e) {
                getLogger().warn("Error stopping process monitor", e);
            }
            processMonitor = null;
        }

        // Stop the Node.js application
        if (lifecycleManager != null) {
            try {
                lifecycleManager.stopApplication();
            } catch (Exception e) {
                getLogger().warn("Error stopping Node.js application", e);
            }
            lifecycleManager = null;
        }

        // Clear log capture
        if (logCapture != null) {
            logCapture.clear();
            logCapture = null;
        }
    }

    @Override
    public boolean isApplicationRunning() {
        return lifecycleManager != null && lifecycleManager.isRunning();
    }

    @Override
    public ProcessStatus getApplicationStatus() {
        if (lifecycleManager == null) {
            return ProcessStatus.builder()
                    .state(ProcessStatus.State.STOPPED)
                    .build();
        }
        return lifecycleManager.getStatus();
    }

    @Override
    public List<String> getApplicationLogs(int maxLines) {
        if (logCapture == null) {
            return Collections.emptyList();
        }
        return logCapture.getRecentLogs(maxLines);
    }

    @Override
    public void restartApplication() throws ProcessManagementException {
        if (lifecycleManager == null) {
            throw new ProcessManagementException("Service is not enabled");
        }
        getLogger().info("Manual restart requested for Node.js application");
        lifecycleManager.restartApplication();
    }

    @Override
    public int getApplicationPort() {
        return applicationPort;
    }

    @Override
    public String getApplicationUrl() {
        if (!isApplicationRunning()) {
            return "";
        }
        return "http://" + applicationHost + ":" + applicationPort;
    }

    /**
     * Custom validator to check if a directory exists.
     */
    private static class DirectoryExistsValidator implements Validator {
        @Override
        public ValidationResult validate(String subject, String input, ValidationContext context) {
            if (input == null || input.trim().isEmpty()) {
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(false)
                        .explanation("Directory path cannot be empty")
                        .build();
            }

            // Evaluate expression language if present
            String evaluatedPath = input;
            if (context.isExpressionLanguageSupported(subject) && context.isExpressionLanguagePresent(input)) {
                try {
                    evaluatedPath = context.newPropertyValue(input)
                            .evaluateAttributeExpressions()
                            .getValue();
                } catch (Exception e) {
                    return new ValidationResult.Builder()
                            .subject(subject)
                            .input(input)
                            .valid(false)
                            .explanation("Failed to evaluate expression: " + e.getMessage())
                            .build();
                }
            }

            File dir = new File(evaluatedPath);
            if (!dir.exists()) {
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(false)
                        .explanation("Directory does not exist: " + evaluatedPath)
                        .build();
            }

            if (!dir.isDirectory()) {
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(false)
                        .explanation("Path is not a directory: " + evaluatedPath)
                        .build();
            }

            // Check for package.json
            File packageJson = new File(dir, "package.json");
            if (!packageJson.exists()) {
                return new ValidationResult.Builder()
                        .subject(subject)
                        .input(input)
                        .valid(false)
                        .explanation("Directory does not contain package.json: " + evaluatedPath)
                        .build();
            }

            return new ValidationResult.Builder()
                    .subject(subject)
                    .input(input)
                    .valid(true)
                    .build();
        }
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>();

        // Validate application port is in valid range (1-65535)
        final Integer port = validationContext.getProperty(APPLICATION_PORT)
                .evaluateAttributeExpressions().asInteger();
        if (port != null && (port < 1 || port > 65535)) {
            results.add(new ValidationResult.Builder()
                    .subject(APPLICATION_PORT.getName())
                    .valid(false)
                    .explanation("Port must be between 1 and 65535, got: " + port)
                    .build());
        }

        // Validate that health check dependencies are met
        final boolean healthCheckEnabled = validationContext.getProperty(ENABLE_HEALTH_CHECK).asBoolean();
        if (healthCheckEnabled) {
            final String healthCheckPath = validationContext.getProperty(HEALTH_CHECK_PATH)
                    .evaluateAttributeExpressions().getValue();
            if (healthCheckPath == null || healthCheckPath.trim().isEmpty()) {
                results.add(new ValidationResult.Builder()
                        .subject(HEALTH_CHECK_PATH.getName())
                        .valid(false)
                        .explanation("Health check path is required when health checks are enabled")
                        .build());
            }
        }

        return results;
    }
}