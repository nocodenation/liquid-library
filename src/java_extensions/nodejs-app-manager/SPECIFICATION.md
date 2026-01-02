# NodeJSApplicationManagerService - Technical Specification

**Version:** 1.0.0-SNAPSHOT
**Date:** 2026-01-01
**Status:** Draft - Ready for Implementation
**Pattern:** NiFi Pattern B (Controller Service)

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [API Specification](#api-specification)
4. [Configuration Properties](#configuration-properties)
5. [Lifecycle Management](#lifecycle-management)
6. [Process Management](#process-management)
7. [Health Monitoring](#health-monitoring)
8. [Logging & Error Handling](#logging--error-handling)
9. [Security Considerations](#security-considerations)
10. [Implementation Details](#implementation-details)
11. [Testing Requirements](#testing-requirements)
12. [Deployment Guide](#deployment-guide)

---

## Overview

### Purpose

The **NodeJSApplicationManagerService** is a NiFi Controller Service that manages the lifecycle of Node.js applications from within the NiFi environment. It enables NiFi to start, stop, monitor, and restart Node.js processes, providing centralized management of frontend applications or Node.js-based services alongside data processing flows.

### Primary Use Cases

1. **Frontend Application Management**
   - Host web-based UIs (Next.js, React, Vue, etc.) within NiFi ecosystem
   - Provide user interfaces for quality systems, dashboards, or configuration tools
   - Centralize application deployment and management

2. **Microservice Coordination**
   - Manage Node.js microservices that complement NiFi processors
   - Coordinate REST API services used by NiFi flows
   - Enable event-driven architectures with Node.js event handlers

3. **Development Environment**
   - Run development servers with hot-reload capabilities
   - Provide local testing environments for integrated solutions
   - Rapid prototyping of Node.js services

### Design Goals

- ✅ **Reliability:** Robust process management with automatic recovery
- ✅ **Simplicity:** Easy configuration through NiFi UI
- ✅ **Observability:** Comprehensive logging and health monitoring
- ✅ **Flexibility:** Support multiple package managers and Node.js versions
- ✅ **Security:** Safe process execution with proper isolation
- ✅ **Performance:** Minimal overhead on NiFi operations

---

## Architecture

### Component Structure (Pattern B)

Following NiFi's Pattern B for Controller Services:

```
nodejs-app-manager/
├── pom.xml (parent)
│
├── nodejs-app-manager-service-api/          # API Module (JAR)
│   ├── pom.xml
│   └── src/main/java/org/nocodenation/nifi/nodejsapp/
│       ├── NodeJSApplicationManagerService.java      # Interface
│       └── ProcessStatus.java                        # Data transfer object
│
├── nodejs-app-manager-service-api-nar/      # API NAR
│   ├── pom.xml
│   └── src/main/resources/META-INF/
│       └── LICENSE
│
├── nodejs-app-manager-service/              # Implementation Module (JAR)
│   ├── pom.xml
│   └── src/main/java/org/nocodenation/nifi/nodejsapp/
│       ├── StandardNodeJSApplicationManagerService.java  # Main implementation
│       ├── ProcessMonitor.java                          # Health monitoring
│       ├── LogCapture.java                              # Stream handling
│       ├── ProcessLifecycleManager.java                 # Start/stop logic
│       └── PackageManagerDetector.java                  # Auto-detection
│
└── nodejs-app-manager-service-nar/          # Implementation NAR
    ├── pom.xml
    └── src/main/resources/META-INF/
        └── LICENSE
```

### Class Diagram

```
┌─────────────────────────────────────────────────────┐
│ <<interface>>                                       │
│ NodeJSApplicationManagerService                     │
│ (extends ControllerService)                         │
├─────────────────────────────────────────────────────┤
│ + isApplicationRunning(): boolean                   │
│ + getApplicationStatus(): ProcessStatus             │
│ + getApplicationLogs(int maxLines): List<String>    │
│ + restartApplication(): void                        │
│ + getApplicationPort(): int                         │
│ + getApplicationUrl(): String                       │
└─────────────────────────────────────────────────────┘
                          △
                          │ implements
                          │
┌─────────────────────────────────────────────────────┐
│ StandardNodeJSApplicationManagerService             │
│ (extends AbstractControllerService)                 │
├─────────────────────────────────────────────────────┤
│ - process: Process                                  │
│ - processMonitor: ProcessMonitor                    │
│ - logCapture: LogCapture                            │
│ - lifecycleManager: ProcessLifecycleManager         │
│ - executorService: ScheduledExecutorService         │
├─────────────────────────────────────────────────────┤
│ + @OnEnabled onEnabled(ConfigurationContext)        │
│ + @OnDisabled onDisabled()                          │
│ + @OnShutdown onShutdown()                          │
│ - startApplication()                                │
│ - stopApplication(boolean graceful)                 │
│ - validateConfiguration()                           │
│ - installDependencies()                             │
│ - buildApplication()                                │
└─────────────────────────────────────────────────────┘
```

### Data Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                        NiFi Environment                          │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐     │
│  │  StandardNodeJSApplicationManagerService               │     │
│  │                                                        │     │
│  │  ┌──────────────┐      ┌─────────────────┐            │     │
│  │  │ Configuration│─────▶│ ProcessLifecycle│            │     │
│  │  │  Properties  │      │    Manager      │            │     │
│  │  └──────────────┘      └────────┬────────┘            │     │
│  │                                 │                      │     │
│  │                                 ▼                      │     │
│  │                        ┌─────────────────┐             │     │
│  │                        │  ProcessBuilder │             │     │
│  │                        └────────┬────────┘             │     │
│  │                                 │                      │     │
│  │         ┌───────────────────────┼───────────────┐      │     │
│  │         │                       │               │      │     │
│  │         ▼                       ▼               ▼      │     │
│  │  ┌─────────────┐      ┌──────────────┐  ┌──────────┐  │     │
│  │  │   Process   │      │ ProcessMonitor│  │   Log    │  │     │
│  │  │  (Node.js)  │◀────│  (Health      │  │ Capture  │  │     │
│  │  │             │      │   Checks)     │  │ (Streams)│  │     │
│  │  └─────────────┘      └──────────────┘  └──────────┘  │     │
│  │         │                      │               │       │     │
│  │         │                      └───────┬───────┘       │     │
│  │         │                              │               │     │
│  │         ▼                              ▼               │     │
│  │  ┌─────────────┐              ┌──────────────┐        │     │
│  │  │   Node.js   │              │ NiFi Bulletin│        │     │
│  │  │    App      │              │   / Logs     │        │     │
│  │  │(Port 3000)  │              └──────────────┘        │     │
│  │  └─────────────┘                                      │     │
│  └────────────────────────────────────────────────────────┘     │
│                                                                  │
│  ┌────────────────────────────────────────────────────────┐     │
│  │  NiFi Processors (Optional Integration)               │     │
│  │                                                        │     │
│  │  ┌──────────────┐                                     │     │
│  │  │  InvokeHTTP  │──▶ http://localhost:3000/api/...    │     │
│  │  └──────────────┘                                     │     │
│  └────────────────────────────────────────────────────────┘     │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘

External Access:
  Users → http://localhost:3000 → Node.js Application
```

---

## API Specification

### Interface: NodeJSApplicationManagerService

**Package:** `org.nocodenation.nifi.nodejsapp`
**Extends:** `org.apache.nifi.controller.ControllerService`

```java
package org.nocodenation.nifi.nodejsapp;

import org.apache.nifi.controller.ControllerService;
import java.util.List;

/**
 * Controller Service for managing Node.js application lifecycle.
 *
 * <p>This service manages a single Node.js application process, providing
 * start, stop, restart, and monitoring capabilities. The service integrates
 * with Node.js package managers (npm, yarn, pnpm, bun) and supports both
 * development and production modes.</p>
 *
 * @since 1.0.0
 */
public interface NodeJSApplicationManagerService extends ControllerService {

    /**
     * Checks if the managed Node.js application process is currently running.
     *
     * @return true if the process is alive, false otherwise
     */
    boolean isApplicationRunning();

    /**
     * Gets the current status of the managed application.
     *
     * @return ProcessStatus object containing detailed state information
     */
    ProcessStatus getApplicationStatus();

    /**
     * Retrieves the most recent application log lines.
     *
     * @param maxLines maximum number of log lines to return
     * @return list of log lines (stdout and stderr combined), most recent first
     */
    List<String> getApplicationLogs(int maxLines);

    /**
     * Restarts the Node.js application.
     *
     * <p>This method performs a graceful stop followed by a fresh start.
     * If the application is not currently running, it will simply start it.</p>
     *
     * @throws ProcessManagementException if restart fails
     */
    void restartApplication() throws ProcessManagementException;

    /**
     * Gets the port number the application is configured to use.
     *
     * @return configured port number
     */
    int getApplicationPort();

    /**
     * Gets the full URL where the application is accessible.
     *
     * @return application URL (e.g., "http://localhost:3000")
     */
    String getApplicationUrl();
}
```

### Data Transfer Object: ProcessStatus

**Package:** `org.nocodenation.nifi.nodejsapp`

```java
package org.nocodenation.nifi.nodejsapp;

import java.time.Instant;

/**
 * Represents the current status of a managed Node.js process.
 */
public class ProcessStatus {

    public enum State {
        STOPPED,       // Process not running
        STARTING,      // Process launching, not yet ready
        RUNNING,       // Process running and healthy
        UNHEALTHY,     // Process running but health check failing
        STOPPING,      // Process shutting down
        CRASHED        // Process terminated unexpectedly
    }

    private final State state;
    private final Long processId;
    private final Instant startTime;
    private final Instant lastHealthCheck;
    private final boolean healthCheckPassing;
    private final String healthCheckMessage;
    private final int restartCount;
    private final String applicationVersion;

    // Constructor, getters, builder pattern

    public State getState() { return state; }
    public Long getProcessId() { return processId; }
    public Instant getStartTime() { return startTime; }
    public Instant getLastHealthCheck() { return lastHealthCheck; }
    public boolean isHealthCheckPassing() { return healthCheckPassing; }
    public String getHealthCheckMessage() { return healthCheckMessage; }
    public int getRestartCount() { return restartCount; }
    public String getApplicationVersion() { return applicationVersion; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        // Builder implementation
    }
}
```

### Exception: ProcessManagementException

**Package:** `org.nocodenation.nifi.nodejsapp`

```java
package org.nocodenation.nifi.nodejsapp;

/**
 * Exception thrown when Node.js process management operations fail.
 */
public class ProcessManagementException extends Exception {

    public ProcessManagementException(String message) {
        super(message);
    }

    public ProcessManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## Configuration Properties

### PropertyDescriptors

All properties follow NiFi's PropertyDescriptor pattern with Expression Language support where applicable.

#### 1. Application Directory

```java
public static final PropertyDescriptor APP_DIRECTORY = new PropertyDescriptor.Builder()
    .name("Application Directory")
    .displayName("Application Directory")
    .description("Absolute path to the Node.js application directory containing package.json")
    .required(true)
    .addValidator(StandardValidators.createDirectoryExistsValidator(true, false))
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Example:** `/opt/nifi/apps/quality-event-system`

#### 2. Node.js Binary Path

```java
public static final PropertyDescriptor NODE_BINARY = new PropertyDescriptor.Builder()
    .name("Node.js Binary Path")
    .displayName("Node.js Binary Path")
    .description("Path to the Node.js executable. If not specified, 'node' will be resolved from PATH")
    .required(false)
    .defaultValue("node")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Example:** `/usr/local/bin/node` or `node`

#### 3. Package Manager

```java
public static final PropertyDescriptor PACKAGE_MANAGER = new PropertyDescriptor.Builder()
    .name("Package Manager")
    .displayName("Package Manager")
    .description("Package manager used to run application scripts")
    .required(true)
    .allowableValues("npm", "yarn", "pnpm", "bun", "auto-detect")
    .defaultValue("auto-detect")
    .build();
```

**Values:**
- `npm` - Use npm
- `yarn` - Use Yarn
- `pnpm` - Use pnpm
- `bun` - Use Bun
- `auto-detect` - Automatically detect from lock files

**Auto-detection Logic:**
```
bun.lock → bun
yarn.lock → yarn
pnpm-lock.yaml → pnpm
package-lock.json → npm
(default) → npm
```

#### 4. Start Command

```java
public static final PropertyDescriptor START_COMMAND = new PropertyDescriptor.Builder()
    .name("Start Command")
    .displayName("Start Command")
    .description("NPM script name to execute for starting the application (from package.json scripts)")
    .required(true)
    .defaultValue("start")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
    .build();
```

**Example:** `start`, `dev`, `serve`

This executes: `<package-manager> run <start-command>`

#### 5. Application Port

```java
public static final PropertyDescriptor APP_PORT = new PropertyDescriptor.Builder()
    .name("Application Port")
    .displayName("Application Port")
    .description("Port number the application will listen on. This value is passed as PORT environment variable")
    .required(true)
    .defaultValue("3000")
    .addValidator(StandardValidators.PORT_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Range:** 1-65535
**Default:** 3000

#### 6. Environment Variables

```java
public static final PropertyDescriptor ENV_VARIABLES = new PropertyDescriptor.Builder()
    .name("Environment Variables")
    .displayName("Environment Variables")
    .description("JSON object containing environment variables to pass to the application. " +
                 "Example: {\"NODE_ENV\":\"production\",\"API_KEY\":\"${api.key}\"}")
    .required(false)
    .defaultValue("{\"NODE_ENV\":\"production\"}")
    .addValidator(new JsonValidator())
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Format:** JSON object
**Example:**
```json
{
  "NODE_ENV": "production",
  "PORT": "3000",
  "API_BASE_URL": "http://localhost:8443",
  "LOG_LEVEL": "info"
}
```

#### 7. Health Check URL

```java
public static final PropertyDescriptor HEALTH_CHECK_URL = new PropertyDescriptor.Builder()
    .name("Health Check URL")
    .displayName("Health Check URL")
    .description("URL to check for application health. If not specified, defaults to http://localhost:{port}")
    .required(false)
    .addValidator(StandardValidators.URL_VALIDATOR)
    .expressionLanguageSupported(ExpressionLanguageScope.ENVIRONMENT)
    .build();
```

**Default:** `http://localhost:<port>`
**Example:** `http://localhost:3000/health`

#### 8. Health Check Interval

```java
public static final PropertyDescriptor HEALTH_CHECK_INTERVAL = new PropertyDescriptor.Builder()
    .name("Health Check Interval")
    .displayName("Health Check Interval")
    .description("Time between health checks")
    .required(true)
    .defaultValue("30 sec")
    .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
    .build();
```

**Range:** 5 sec - 5 min
**Default:** 30 sec

#### 9. Startup Timeout

```java
public static final PropertyDescriptor STARTUP_TIMEOUT = new PropertyDescriptor.Builder()
    .name("Startup Timeout")
    .displayName("Startup Timeout")
    .description("Maximum time to wait for application to become healthy after starting")
    .required(true)
    .defaultValue("60 sec")
    .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
    .build();
```

**Range:** 10 sec - 5 min
**Default:** 60 sec

#### 10. Shutdown Timeout

```java
public static final PropertyDescriptor SHUTDOWN_TIMEOUT = new PropertyDescriptor.Builder()
    .name("Shutdown Timeout")
    .displayName("Shutdown Timeout")
    .description("Maximum time to wait for graceful shutdown before forcing termination")
    .required(true)
    .defaultValue("10 sec")
    .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
    .build();
```

**Range:** 5 sec - 60 sec
**Default:** 10 sec

#### 11. Auto-Restart on Crash

```java
public static final PropertyDescriptor AUTO_RESTART = new PropertyDescriptor.Builder()
    .name("Auto-Restart on Crash")
    .displayName("Auto-Restart on Crash")
    .description("Automatically restart the application if the process crashes unexpectedly")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("true")
    .build();
```

**Default:** true

#### 12. Max Restart Attempts

```java
public static final PropertyDescriptor MAX_RESTART_ATTEMPTS = new PropertyDescriptor.Builder()
    .name("Max Restart Attempts")
    .displayName("Max Restart Attempts")
    .description("Maximum number of consecutive restart attempts before giving up. " +
                 "Reset after successful 5-minute uptime. 0 = unlimited")
    .required(true)
    .defaultValue("3")
    .addValidator(StandardValidators.NON_NEGATIVE_INTEGER_VALIDATOR)
    .build();
```

**Range:** 0 (unlimited) - 10
**Default:** 3

#### 13. Install Dependencies

```java
public static final PropertyDescriptor INSTALL_DEPENDENCIES = new PropertyDescriptor.Builder()
    .name("Install Dependencies")
    .displayName("Install Dependencies")
    .description("Run package manager install command if node_modules directory is missing")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("true")
    .build();
```

**Default:** true

**Behavior:** Runs `<package-manager> install` if `node_modules` doesn't exist

#### 14. Build Before Start

```java
public static final PropertyDescriptor BUILD_BEFORE_START = new PropertyDescriptor.Builder()
    .name("Build Before Start")
    .displayName("Build Before Start")
    .description("Run build command before starting the application (production mode)")
    .required(true)
    .allowableValues("true", "false")
    .defaultValue("false")
    .build();
```

**Default:** false

**Behavior:** Runs `<package-manager> run build` before start command

#### 15. Build Command

```java
public static final PropertyDescriptor BUILD_COMMAND = new PropertyDescriptor.Builder()
    .name("Build Command")
    .displayName("Build Command")
    .description("NPM script name to execute for building the application")
    .required(false)
    .defaultValue("build")
    .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
    .dependsOn(BUILD_BEFORE_START, "true")
    .build();
```

**Default:** `build`

#### 16. Log Level

```java
public static final PropertyDescriptor LOG_LEVEL = new PropertyDescriptor.Builder()
    .name("Log Level")
    .displayName("Log Level")
    .description("Minimum log level to capture from application output")
    .required(true)
    .allowableValues("TRACE", "DEBUG", "INFO", "WARN", "ERROR")
    .defaultValue("INFO")
    .build();
```

**Default:** INFO

#### 17. Max Log Lines

```java
public static final PropertyDescriptor MAX_LOG_LINES = new PropertyDescriptor.Builder()
    .name("Max Log Lines")
    .displayName("Max Log Lines")
    .description("Maximum number of log lines to keep in memory (circular buffer)")
    .required(true)
    .defaultValue("1000")
    .addValidator(StandardValidators.createLongValidator(100, 10000, true))
    .build();
```

**Range:** 100 - 10,000
**Default:** 1,000

---

## Lifecycle Management

### State Diagram

```
                    ┌─────────────┐
                    │   STOPPED   │
                    └──────┬──────┘
                           │
                    @OnEnabled called
                           │
                           ▼
                    ┌─────────────┐
        ┌──────────▶│  STARTING   │
        │           └──────┬──────┘
        │                  │
        │           Process launched
        │           Health check loop
        │                  │
        │                  ▼
        │           ┌─────────────┐◀──────┐
        │           │   RUNNING   │       │
        │           └──────┬──────┘       │
        │                  │              │
        │                  ├──────────────┘
        │                  │   Health check passing
        │                  │
        │           Health check failing
        │                  │
        │                  ▼
        │           ┌─────────────┐
        │      ┌───│  UNHEALTHY  │
        │      │    └──────┬──────┘
        │      │           │
        │      │    Process still alive
        │      │           │
        │      │           ▼
        │      │    Continue monitoring
        │      │
        │      │    Process died
        │      │           │
        │      │           ▼
        │      │    ┌─────────────┐
        │      └───▶│   CRASHED   │
        │           └──────┬──────┘
        │                  │
        │           Auto-restart enabled?
        │                  │
        │           Yes    │    No
        └───────────────────┘    │
                                 │
                          @OnDisabled called
                                 │
                                 ▼
                          ┌─────────────┐
                          │  STOPPING   │
                          └──────┬──────┘
                                 │
                          Graceful shutdown
                          (with timeout)
                                 │
                                 ▼
                          ┌─────────────┐
                          │   STOPPED   │
                          └─────────────┘
```

### Lifecycle Methods

#### @OnEnabled

**Triggered:** When user enables the service in NiFi UI

**Sequence:**
1. **Validate Configuration**
   - Check all required properties
   - Validate file paths exist
   - Check port availability
   - Verify Node.js binary exists

2. **Prepare Application**
   - Check for `package.json`
   - Detect/validate package manager
   - Install dependencies (if enabled and needed)
   - Run build command (if enabled)

3. **Start Process**
   - Create ProcessBuilder with environment
   - Set working directory
   - Configure stdout/stderr capture
   - Launch process

4. **Initialize Monitoring**
   - Start health check scheduler
   - Start log capture threads
   - Initialize process monitor

5. **Wait for Healthy**
   - Poll health check endpoint
   - Timeout after configured startup timeout
   - Throw exception if fails to become healthy

**Pseudo-code:**
```java
@OnEnabled
public void onEnabled(final ConfigurationContext context) throws InitializationException {
    try {
        // 1. Validate
        validateConfiguration(context);

        // 2. Prepare
        if (context.getProperty(INSTALL_DEPENDENCIES).asBoolean()) {
            installDependenciesIfNeeded();
        }

        if (context.getProperty(BUILD_BEFORE_START).asBoolean()) {
            buildApplication();
        }

        // 3. Start
        process = lifecycleManager.startApplication(context);

        // 4. Initialize monitoring
        startHealthMonitoring();
        startLogCapture();

        // 5. Wait for healthy
        waitForHealthy(context.getProperty(STARTUP_TIMEOUT).asTimePeriod());

        getLogger().info("Application started successfully on port {}", getApplicationPort());

    } catch (Exception e) {
        cleanup();
        throw new InitializationException("Failed to start application", e);
    }
}
```

#### @OnDisabled

**Triggered:** When user disables the service in NiFi UI

**Sequence:**
1. **Stop Health Monitoring**
   - Cancel scheduled health checks
   - Shutdown executor service

2. **Stop Application**
   - Send SIGTERM (graceful shutdown)
   - Wait for shutdown timeout
   - Send SIGKILL if still running

3. **Stop Log Capture**
   - Close stream readers
   - Flush remaining logs

4. **Cleanup Resources**
   - Clear process reference
   - Reset state

**Pseudo-code:**
```java
@OnDisabled
public void onDisabled() {
    try {
        getLogger().info("Stopping application...");

        // 1. Stop monitoring
        stopHealthMonitoring();

        // 2. Stop application
        lifecycleManager.stopApplication(
            process,
            context.getProperty(SHUTDOWN_TIMEOUT).asTimePeriod()
        );

        // 3. Stop log capture
        logCapture.stop();

        // 4. Cleanup
        process = null;
        restartCount = 0;

        getLogger().info("Application stopped");

    } catch (Exception e) {
        getLogger().error("Error stopping application", e);
    }
}
```

#### @OnShutdown

**Triggered:** When NiFi is shutting down

**Sequence:**
Same as @OnDisabled but with forced termination if graceful shutdown fails

**Pseudo-code:**
```java
@OnShutdown
public void onShutdown() {
    if (process != null && process.isAlive()) {
        getLogger().warn("Force terminating application due to NiFi shutdown");
        process.destroyForcibly();
    }
}
```

---

## Process Management

### ProcessLifecycleManager Class

Responsible for all process start/stop operations.

**Key Methods:**

#### startApplication()

```java
public Process startApplication(ConfigurationContext context)
        throws ProcessManagementException {

    File appDir = new File(context.getProperty(APP_DIRECTORY).getValue());
    String packageManager = detectPackageManager(appDir,
        context.getProperty(PACKAGE_MANAGER).getValue());
    String startCommand = context.getProperty(START_COMMAND).getValue();

    // Build command
    List<String> command = Arrays.asList(
        packageManager,
        "run",
        startCommand
    );

    // Build environment
    Map<String, String> environment = buildEnvironment(context);

    // Create process
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(appDir);
    pb.environment().putAll(environment);
    pb.redirectErrorStream(false); // Keep stdout/stderr separate

    try {
        Process process = pb.start();

        getLogger().info("Started application with PID {} using command: {}",
            process.pid(), String.join(" ", command));

        return process;

    } catch (IOException e) {
        throw new ProcessManagementException(
            "Failed to start application: " + e.getMessage(), e);
    }
}
```

#### stopApplication()

```java
public void stopApplication(Process process, long timeoutMillis)
        throws ProcessManagementException {

    if (process == null || !process.isAlive()) {
        return;
    }

    try {
        // Graceful shutdown (SIGTERM)
        getLogger().info("Sending SIGTERM to process {}", process.pid());
        process.destroy();

        // Wait for graceful exit
        boolean exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);

        if (exited) {
            getLogger().info("Process {} exited gracefully with code {}",
                process.pid(), process.exitValue());
        } else {
            // Force kill (SIGKILL)
            getLogger().warn("Process {} did not exit gracefully, forcing termination",
                process.pid());
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ProcessManagementException("Interrupted while stopping process", e);
    }
}
```

#### buildEnvironment()

```java
private Map<String, String> buildEnvironment(ConfigurationContext context) {
    Map<String, String> env = new HashMap<>(System.getenv());

    // Set PORT
    env.put("PORT", String.valueOf(context.getProperty(APP_PORT).asInteger()));

    // Parse custom environment variables from JSON
    String envJson = context.getProperty(ENV_VARIABLES).getValue();
    if (envJson != null && !envJson.isEmpty()) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> customEnv = mapper.readValue(envJson,
                new TypeReference<Map<String, String>>() {});
            env.putAll(customEnv);
        } catch (Exception e) {
            getLogger().error("Failed to parse environment variables JSON", e);
        }
    }

    return env;
}
```

#### detectPackageManager()

```java
private String detectPackageManager(File appDir, String configured) {
    if (!"auto-detect".equals(configured)) {
        return configured;
    }

    // Check for lock files
    if (new File(appDir, "bun.lock").exists()) {
        return "bun";
    }
    if (new File(appDir, "yarn.lock").exists()) {
        return "yarn";
    }
    if (new File(appDir, "pnpm-lock.yaml").exists()) {
        return "pnpm";
    }
    if (new File(appDir, "package-lock.json").exists()) {
        return "npm";
    }

    // Default to npm
    getLogger().warn("No lock file found, defaulting to npm");
    return "npm";
}
```

#### installDependenciesIfNeeded()

```java
private void installDependenciesIfNeeded() throws ProcessManagementException {
    File nodeModules = new File(appDir, "node_modules");

    if (nodeModules.exists()) {
        getLogger().debug("node_modules exists, skipping dependency installation");
        return;
    }

    getLogger().info("node_modules not found, installing dependencies...");

    ProcessBuilder pb = new ProcessBuilder(packageManager, "install");
    pb.directory(appDir);
    pb.inheritIO(); // Show install output in NiFi logs

    try {
        Process installProcess = pb.start();
        int exitCode = installProcess.waitFor();

        if (exitCode != 0) {
            throw new ProcessManagementException(
                "Dependency installation failed with exit code " + exitCode);
        }

        getLogger().info("Dependencies installed successfully");

    } catch (Exception e) {
        throw new ProcessManagementException("Failed to install dependencies", e);
    }
}
```

---

## Health Monitoring

### ProcessMonitor Class

Responsible for continuous health checking and crash detection.

**Key Responsibilities:**
- Periodic health check requests
- Process liveness monitoring
- Automatic restart on crash
- Restart attempt tracking

#### Health Check Logic

```java
public class ProcessMonitor {

    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(1);
    private final ComponentLog logger;
    private final ProcessLifecycleManager lifecycleManager;
    private final ConfigurationContext context;

    private Process process;
    private int consecutiveFailures = 0;
    private int restartAttempts = 0;
    private Instant lastSuccessfulCheck = null;

    public void start() {
        long intervalSeconds = context.getProperty(HEALTH_CHECK_INTERVAL)
            .asTimePeriod(TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(
            this::performHealthCheck,
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    private void performHealthCheck() {
        try {
            // Check 1: Process liveness
            if (process == null || !process.isAlive()) {
                handleProcessCrash();
                return;
            }

            // Check 2: HTTP health check
            String healthUrl = buildHealthCheckUrl();
            boolean healthy = checkHttpHealth(healthUrl);

            if (healthy) {
                consecutiveFailures = 0;
                lastSuccessfulCheck = Instant.now();

                // Reset restart counter after 5 minutes of stability
                if (Duration.between(startTime, Instant.now()).toMinutes() >= 5) {
                    restartAttempts = 0;
                }
            } else {
                consecutiveFailures++;
                logger.warn("Health check failed ({} consecutive failures)",
                    consecutiveFailures);

                if (consecutiveFailures >= 3) {
                    logger.error("Application unhealthy after 3 consecutive failures");
                }
            }

        } catch (Exception e) {
            logger.error("Health check error", e);
        }
    }

    private boolean checkHttpHealth(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 400;

        } catch (IOException e) {
            return false;
        }
    }

    private void handleProcessCrash() {
        logger.error("Process crashed! Exit value: {}",
            process != null ? process.exitValue() : "unknown");

        boolean autoRestart = context.getProperty(AUTO_RESTART).asBoolean();
        int maxAttempts = context.getProperty(MAX_RESTART_ATTEMPTS).asInteger();

        if (!autoRestart) {
            logger.info("Auto-restart disabled, not attempting restart");
            return;
        }

        if (maxAttempts > 0 && restartAttempts >= maxAttempts) {
            logger.error("Max restart attempts ({}) reached, giving up", maxAttempts);
            return;
        }

        restartAttempts++;
        logger.info("Attempting restart ({}/{})", restartAttempts,
            maxAttempts == 0 ? "unlimited" : maxAttempts);

        try {
            Thread.sleep(5000); // Wait 5 seconds before restart
            process = lifecycleManager.startApplication(context);
            logger.info("Application restarted successfully");
        } catch (Exception e) {
            logger.error("Failed to restart application", e);
        }
    }
}
```

---

## Logging & Error Handling

### LogCapture Class

Captures stdout and stderr from the Node.js process and forwards to NiFi logging.

```java
public class LogCapture {

    private final ComponentLog logger;
    private final CircularFifoQueue<String> logBuffer;
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    public LogCapture(ComponentLog logger, int maxLines) {
        this.logger = logger;
        this.logBuffer = new CircularFifoQueue<>(maxLines);
    }

    public void start(Process process) {
        // Capture stdout
        executorService.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logBuffer.add("[STDOUT] " + line);
                    logger.info("[App] {}", line);
                }
            } catch (IOException e) {
                logger.error("Error reading stdout", e);
            }
        });

        // Capture stderr
        executorService.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logBuffer.add("[STDERR] " + line);
                    logger.error("[App] {}", line);
                }
            } catch (IOException e) {
                logger.error("Error reading stderr", e);
            }
        });
    }

    public List<String> getRecentLogs(int maxLines) {
        List<String> logs = new ArrayList<>(logBuffer);
        Collections.reverse(logs); // Most recent first
        return logs.subList(0, Math.min(maxLines, logs.size()));
    }

    public void stop() {
        executorService.shutdownNow();
    }
}
```

### Error Handling Strategy

**Principle:** Fail gracefully with clear error messages

**Error Categories:**

1. **Configuration Errors** (thrown during @OnEnabled)
   - Invalid paths
   - Missing package.json
   - Port conflicts
   - Invalid JSON in environment variables

2. **Startup Errors** (thrown during @OnEnabled)
   - Process failed to start
   - Dependency installation failed
   - Build failed
   - Health check timeout

3. **Runtime Errors** (logged, may trigger restart)
   - Process crashed
   - Health check failures
   - Stream read errors

4. **Shutdown Errors** (logged only)
   - Process didn't stop gracefully
   - Cleanup failures

**Error Message Format:**
```
[NodeJSAppManager] <Level>: <Context> - <Message>
  Details: <Additional Info>
  Action: <Suggested Resolution>
```

**Example:**
```
[NodeJSAppManager] ERROR: Startup Failed - Application did not become healthy within 60 seconds
  Details: Health check to http://localhost:3000 returned 503 Service Unavailable
  Action: Check application logs, increase Startup Timeout, or verify application starts correctly
```

---

## Security Considerations

### 1. Process Execution Security

**Risks:**
- Command injection via property values
- Arbitrary code execution
- Privilege escalation

**Mitigations:**
```java
// ✅ SAFE: Use ProcessBuilder with separate arguments
ProcessBuilder pb = new ProcessBuilder("npm", "run", "start");

// ❌ UNSAFE: Don't use Runtime.exec with shell
// Runtime.getRuntime().exec("npm run " + userInput);

// Validate all inputs
private static final Set<String> ALLOWED_PACKAGE_MANAGERS =
    Set.of("npm", "yarn", "pnpm", "bun");

if (!ALLOWED_PACKAGE_MANAGERS.contains(packageManager)) {
    throw new IllegalArgumentException("Invalid package manager: " + packageManager);
}

// Validate paths
File appDir = new File(configuredPath);
if (!appDir.exists() || !appDir.isDirectory()) {
    throw new IllegalArgumentException("Invalid application directory");
}

// Whitelist script names (alphanumeric + hyphen/underscore only)
if (!startCommand.matches("^[a-zA-Z0-9_-]+$")) {
    throw new IllegalArgumentException("Invalid start command format");
}
```

### 2. Environment Variable Security

**Risks:**
- Exposure of sensitive credentials in logs
- Injection attacks via environment variables

**Mitigations:**
```java
// Support for sensitive properties
public static final PropertyDescriptor API_KEY = new PropertyDescriptor.Builder()
    .name("API Key")
    .sensitive(true) // Encrypted in NiFi config
    .build();

// Don't log environment variables
getLogger().debug("Starting with {} environment variables", env.size());
// NOT: getLogger().debug("Environment: {}", env);

// Validate JSON to prevent injection
try {
    new ObjectMapper().readValue(envJson, Map.class);
} catch (JsonProcessingException e) {
    throw new IllegalArgumentException("Invalid JSON in environment variables");
}
```

### 3. Network Security

**Risks:**
- Application exposed to external networks
- Lack of authentication
- Unencrypted traffic

**Mitigations:**
```java
// Bind to localhost only (not 0.0.0.0)
environment.put("HOST", "127.0.0.1");
environment.put("PORT", String.valueOf(port));

// Recommend reverse proxy with authentication in docs
// Example: nginx with Basic Auth, HTTPS, IP whitelisting

// Health checks use localhost
String healthUrl = "http://127.0.0.1:" + port + "/health";
```

### 4. File System Security

**Risks:**
- Unauthorized file access
- Path traversal attacks
- Modification of NiFi files

**Mitigations:**
```java
// Validate application directory is not within NiFi installation
File nifiHome = new File(System.getProperty("nifi.home"));
File appDir = new File(configuredPath).getCanonicalFile();

if (appDir.getPath().startsWith(nifiHome.getPath())) {
    throw new IllegalArgumentException(
        "Application directory cannot be within NiFi installation");
}

// Use canonical paths to prevent traversal
File packageJson = new File(appDir, "package.json").getCanonicalFile();
if (!packageJson.getPath().startsWith(appDir.getPath())) {
    throw new SecurityException("Path traversal detected");
}

// Recommend running NiFi with limited user permissions
```

### 5. Resource Limits

**Risks:**
- Memory exhaustion
- CPU starvation
- Disk space exhaustion

**Mitigations:**
```java
// Log buffer limits
private final CircularFifoQueue<String> logBuffer =
    new CircularFifoQueue<>(maxLogLines);

// Health check timeouts
conn.setConnectTimeout(5000);
conn.setReadTimeout(5000);

// Thread pool limits
private final ScheduledExecutorService scheduler =
    Executors.newScheduledThreadPool(1); // Only 1 thread for health checks

// Document recommended Node.js memory limits
// Example: NODE_OPTIONS="--max-old-space-size=512"
```

---

## Implementation Details

### Module Structure

Following Pattern B (Controller Service):

#### 1. Parent POM (`pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.nocodenation.nifi</groupId>
    <artifactId>nodejs-app-manager</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <nifi.version>2.1.0</nifi.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.apache.nifi</groupId>
                <artifactId>nifi-bom</artifactId>
                <version>${nifi.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.nifi</groupId>
                    <artifactId>nifi-nar-maven-plugin</artifactId>
                    <version>2.0.0</version>
                    <extensions>true</extensions>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>

    <modules>
        <module>nodejs-app-manager-service-api</module>
        <module>nodejs-app-manager-service-api-nar</module>
        <module>nodejs-app-manager-service</module>
        <module>nodejs-app-manager-service-nar</module>
    </modules>
</project>
```

#### 2. API Module POM (`nodejs-app-manager-service-api/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.nocodenation.nifi</groupId>
        <artifactId>nodejs-app-manager</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>nodejs-app-manager-service-api</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.apache.nifi</groupId>
            <artifactId>nifi-api</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

#### 3. API NAR POM (`nodejs-app-manager-service-api-nar/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.nocodenation.nifi</groupId>
        <artifactId>nodejs-app-manager</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>nodejs-app-manager-service-api-nar</artifactId>
    <packaging>nar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.nocodenation.nifi</groupId>
            <artifactId>nodejs-app-manager-service-api</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.apache.nifi</groupId>
            <artifactId>nifi-standard-services-api-nar</artifactId>
            <version>${nifi.version}</version>
            <type>nar</type>
        </dependency>
    </dependencies>
</project>
```

#### 4. Implementation Module POM (`nodejs-app-manager-service/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.nocodenation.nifi</groupId>
        <artifactId>nodejs-app-manager</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>nodejs-app-manager-service</artifactId>
    <packaging>jar</packaging>

    <dependencies>
        <!-- API -->
        <dependency>
            <groupId>org.nocodenation.nifi</groupId>
            <artifactId>nodejs-app-manager-service-api</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>

        <!-- NiFi -->
        <dependency>
            <groupId>org.apache.nifi</groupId>
            <artifactId>nifi-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.nifi</groupId>
            <artifactId>nifi-utils</artifactId>
        </dependency>

        <!-- JSON Processing -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <!-- Apache Commons -->
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-collections4</artifactId>
            <version>4.4</version>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.apache.nifi</groupId>
            <artifactId>nifi-mock</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

#### 5. Implementation NAR POM (`nodejs-app-manager-service-nar/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.nocodenation.nifi</groupId>
        <artifactId>nodejs-app-manager</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>nodejs-app-manager-service-nar</artifactId>
    <packaging>nar</packaging>

    <dependencies>
        <dependency>
            <groupId>org.nocodenation.nifi</groupId>
            <artifactId>nodejs-app-manager-service</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
        <dependency>
            <groupId>org.nocodenation.nifi</groupId>
            <artifactId>nodejs-app-manager-service-api-nar</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <type>nar</type>
        </dependency>
    </dependencies>
</project>
```

### Service Descriptor

**File:** `nodejs-app-manager-service/src/main/resources/META-INF/services/org.apache.nifi.controller.ControllerService`

**Content:**
```
org.nocodenation.nifi.nodejsapp.StandardNodeJSApplicationManagerService
```

---

## Testing Requirements

### Unit Tests

**Package:** `org.nocodenation.nifi.nodejsapp`
**Framework:** JUnit 5

#### Test Classes

1. **StandardNodeJSApplicationManagerServiceTest**
   - Test configuration validation
   - Test lifecycle methods (@OnEnabled, @OnDisabled)
   - Test API methods (isRunning, getStatus, getLogs)
   - Mock ProcessBuilder and Process

2. **ProcessLifecycleManagerTest**
   - Test process start with various configurations
   - Test graceful shutdown
   - Test forced shutdown
   - Test environment building
   - Test package manager detection

3. **ProcessMonitorTest**
   - Test health check logic
   - Test crash detection
   - Test auto-restart logic
   - Test restart attempt limits

4. **LogCaptureTest**
   - Test stdout/stderr capture
   - Test log buffer limits
   - Test thread safety

#### Example Test

```java
@Test
public void testApplicationStart() throws Exception {
    // Setup
    TestRunner runner = TestRunners.newTestRunner(TestProcessor.class);

    StandardNodeJSApplicationManagerService service =
        new StandardNodeJSApplicationManagerService();

    runner.addControllerService("nodejs-manager", service);
    runner.setProperty(service, APP_DIRECTORY, "/tmp/test-app");
    runner.setProperty(service, PACKAGE_MANAGER, "npm");
    runner.setProperty(service, START_COMMAND, "start");
    runner.setProperty(service, APP_PORT, "3000");

    // Enable
    runner.enableControllerService(service);

    // Verify
    assertTrue(service.isApplicationRunning());
    assertEquals(3000, service.getApplicationPort());
    assertEquals("http://localhost:3000", service.getApplicationUrl());

    // Cleanup
    runner.disableControllerService(service);
    assertFalse(service.isApplicationRunning());
}
```

### Integration Tests

**Requirements:**
- Real Node.js installation
- Test application directory with package.json
- Docker environment (optional, for CI/CD)

#### Test Application

Create simple test application:

**package.json:**
```json
{
  "name": "test-app",
  "version": "1.0.0",
  "scripts": {
    "start": "node server.js"
  }
}
```

**server.js:**
```javascript
const http = require('http');
const port = process.env.PORT || 3000;

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200);
    res.end('OK');
  } else {
    res.writeHead(200);
    res.end('Test App Running');
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Server running on port ${port}`);
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down...');
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});
```

#### Integration Test Cases

1. **Full Lifecycle Test**
   - Start service
   - Verify application is running
   - Verify health check passes
   - Stop service
   - Verify application stopped

2. **Crash Recovery Test**
   - Start service
   - Kill Node.js process externally
   - Verify auto-restart occurs
   - Verify application running again

3. **Configuration Variations Test**
   - Test with npm, yarn, pnpm, bun
   - Test with different ports
   - Test with environment variables
   - Test with build step

4. **Error Handling Test**
   - Test with invalid directory
   - Test with missing package.json
   - Test with port conflict
   - Test with failing health check

---

## Deployment Guide

### Prerequisites

1. **NiFi Installation**
   - Apache NiFi 2.1.0 or later
   - Java 21

2. **Node.js Installation**
   - Node.js 18+ installed on NiFi server
   - Package manager (npm, yarn, pnpm, or bun)

3. **Network Requirements**
   - Available port for application (default: 3000)
   - Firewall rules if accessing from other machines

### Build Process

```bash
# Navigate to project root
cd liquid-library/src/java_extensions/nodejs-app-manager

# Build all modules
mvn clean install

# NAR files created in:
# - nodejs-app-manager-service-api-nar/target/nodejs-app-manager-service-api-nar-1.0.0-SNAPSHOT.nar
# - nodejs-app-manager-service-nar/target/nodejs-app-manager-service-nar-1.0.0-SNAPSHOT.nar
```

### Installation

#### Method 1: Docker Image (Recommended for Liquid-Playground)

```bash
# Copy NAR files to liquid-playground
source .env
cp nodejs-app-manager-service-api-nar/target/*.nar $LIQUID_PLAYGROUND_HOME/files/
cp nodejs-app-manager-service-nar/target/*.nar $LIQUID_PLAYGROUND_HOME/files/

# Remove old NARs (if updating)
rm $LIQUID_PLAYGROUND_HOME/files/nodejs-app-manager-*-old.nar

# Rebuild Docker image
cd $LIQUID_PLAYGROUND_HOME
./build.sh

# Start NiFi
./start.sh
```

#### Method 2: Direct Installation

```bash
# Copy NAR files to NiFi lib directory
cp *.nar /opt/nifi/nifi-current/lib/

# Restart NiFi
/opt/nifi/nifi-current/bin/nifi.sh restart
```

### Configuration in NiFi UI

1. **Add Controller Service**
   - Open NiFi UI (https://localhost:8443/nifi)
   - Navigate to Controller Settings (gear icon)
   - Go to Controller Services tab
   - Click + to add new service
   - Select "StandardNodeJSApplicationManagerService"

2. **Configure Properties**
   ```
   Application Directory: /opt/nifi/apps/quality-event-system
   Node.js Binary Path: node
   Package Manager: auto-detect
   Start Command: start
   Application Port: 3000
   Environment Variables: {"NODE_ENV":"production","PORT":"3000"}
   Health Check URL: http://localhost:3000
   Health Check Interval: 30 sec
   Startup Timeout: 60 sec
   Shutdown Timeout: 10 sec
   Auto-Restart on Crash: true
   Max Restart Attempts: 3
   Install Dependencies: true
   Build Before Start: false
   Build Command: build
   Log Level: INFO
   Max Log Lines: 1000
   ```

3. **Enable Service**
   - Click lightning bolt icon to enable
   - Monitor NiFi bulletin board for startup messages
   - Verify application accessible at http://localhost:3000

### Verification

```bash
# Check application is running
curl http://localhost:3000

# Check health endpoint
curl http://localhost:3000/health

# Check NiFi logs
tail -f /opt/nifi/nifi-current/logs/nifi-app.log | grep NodeJSAppManager
```

### Troubleshooting

**Issue:** Service fails to enable with "Application directory does not exist"

**Solution:**
```bash
# Create application directory
mkdir -p /opt/nifi/apps/quality-event-system

# Copy application files
cp -r /path/to/quality-event-system/* /opt/nifi/apps/quality-event-system/
```

**Issue:** "Port 3000 already in use"

**Solution:**
- Change Application Port property to different port
- Or stop other service using port 3000

**Issue:** Health check timeout during startup

**Solution:**
- Increase Startup Timeout property
- Check application logs for errors
- Verify application actually listens on configured port

---

## Appendices

### Appendix A: Configuration Examples

#### Example 1: Quality Event System (Production)

```
Application Directory: /opt/nifi/apps/quality-event-system
Package Manager: bun
Start Command: start
Application Port: 3000
Environment Variables: {
  "NODE_ENV": "production",
  "PORT": "3000",
  "API_BASE_URL": "http://localhost:8443"
}
Build Before Start: true
```

#### Example 2: Next.js Development Mode

```
Application Directory: /home/dev/my-next-app
Package Manager: npm
Start Command: dev
Application Port: 3000
Environment Variables: {
  "NODE_ENV": "development",
  "NEXT_PUBLIC_API_URL": "http://localhost:8443/api"
}
Install Dependencies: true
Build Before Start: false
Auto-Restart on Crash: false
```

#### Example 3: Express API Server

```
Application Directory: /opt/nifi/apps/api-server
Package Manager: yarn
Start Command: start
Application Port: 4000
Environment Variables: {
  "NODE_ENV": "production",
  "PORT": "4000",
  "DATABASE_URL": "${db.connection.url}",
  "JWT_SECRET": "${jwt.secret}"
}
Health Check URL: http://localhost:4000/api/health
```

### Appendix B: Package Manager Command Reference

| Operation | npm | yarn | pnpm | bun |
|-----------|-----|------|------|-----|
| Install | `npm install` | `yarn install` | `pnpm install` | `bun install` |
| Run script | `npm run start` | `yarn run start` | `pnpm run start` | `bun run start` |
| Build | `npm run build` | `yarn run build` | `pnpm run build` | `bun run build` |

### Appendix C: Environment Variable Reference

Standard environment variables set by the service:

| Variable | Value | Purpose |
|----------|-------|---------|
| `PORT` | From Application Port property | Application listen port |
| `NODE_ENV` | From Environment Variables property | Node.js environment mode |
| `PATH` | Inherited from NiFi | Binary search path |
| `HOME` | Inherited from NiFi | User home directory |

### Appendix D: Health Check Endpoint Recommendations

For best compatibility, applications should implement a health endpoint:

**Express Example:**
```javascript
app.get('/health', (req, res) => {
  res.status(200).json({ status: 'healthy', timestamp: new Date() });
});
```

**Next.js API Route Example:**
```typescript
// pages/api/health.ts
export default function handler(req, res) {
  res.status(200).json({ status: 'healthy' });
}
```

---

## Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0.0-SNAPSHOT | 2026-01-01 | Development Team | Initial specification |

---

## Approval

**Specification Status:** ✅ Ready for Implementation

**Next Steps:**
1. Review and approve this specification
2. Create feature branch: `feat/nodejs-app-manager-v1.0.0-controller-service`
3. Implement Phase 1 (Core functionality)
4. Create unit and integration tests
5. Deploy to liquid-playground for testing
6. Test with quality-event-system application
7. Documentation and user guide
8. Production deployment

**Estimated Implementation Time:** 4-6 weeks

---

*End of Specification Document*