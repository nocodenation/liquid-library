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

import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.logging.ComponentLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the lifecycle of a Node.js application process using Java's ProcessBuilder API.
 *
 * This class handles:
 * - Starting the Node.js process with proper environment and configuration
 * - Graceful and forceful shutdown
 * - Process state tracking
 * - Automatic package manager detection
 * - Restart management with attempt tracking
 */
public class ProcessLifecycleManager {

    // Constants for timeouts and intervals
    private static final long STARTUP_STABILITY_CHECK_MS = 5_000; // 5 seconds - time to wait before considering process started
    private static final long STARTUP_CHECK_INTERVAL_MS = 1_000; // 1 second - interval between startup checks
    private static final long THREAD_JOIN_TIMEOUT_MS = 2_000; // 2 seconds - max wait for log capture threads to stop
    private static final long FORCE_SHUTDOWN_TIMEOUT_MS = 5_000; // 5 seconds - max wait for forced process termination

    // Constants for thread names
    private static final String THREAD_NAME_STDOUT_CAPTURE = "NodeJS-stdout-capture";
    private static final String THREAD_NAME_STDERR_CAPTURE = "NodeJS-stderr-capture";

    private final ConfigurationContext context;
    private final LogCapture logCapture;
    private final ComponentLog logger;

    private volatile Process nodeProcess;
    private volatile Instant startTime;
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private volatile Instant lastSuccessfulStart;
    private final AtomicReference<ProcessStatus.State> currentState = new AtomicReference<>(ProcessStatus.State.STOPPED);

    // Log capture threads for proper cleanup
    private volatile Thread stdoutCaptureThread;
    private volatile Thread stderrCaptureThread;

    public ProcessLifecycleManager(ConfigurationContext context, LogCapture logCapture, ComponentLog logger) {
        this.context = context;
        this.logCapture = logCapture;
        this.logger = logger;
    }

    /**
     * Starts the Node.js application process.
     */
    public synchronized void startApplication() throws ProcessManagementException {
        if (isRunning()) {
            logger.warn("Application is already running");
            return;
        }

        currentState.set(ProcessStatus.State.STARTING);
        logger.info("Starting Node.js application");

        try {
            final String appPath = context.getProperty(StandardNodeJSApplicationManagerService.APPLICATION_PATH)
                    .evaluateAttributeExpressions().getValue();
            final String nodeExecutable = context.getProperty(StandardNodeJSApplicationManagerService.NODE_EXECUTABLE)
                    .evaluateAttributeExpressions().getValue();
            final String packageManager = detectPackageManager(appPath);
            final String startCommand = context.getProperty(StandardNodeJSApplicationManagerService.START_COMMAND)
                    .evaluateAttributeExpressions().getValue();

            logger.info("Application path: {}", appPath);
            logger.info("Package manager: {}", packageManager);
            logger.info("Start command: {}", startCommand);

            // Build the command
            List<String> command = new ArrayList<>();
            command.add(packageManager);
            command.add("run");
            command.add(startCommand);

            logger.debug("Full command: {}", String.join(" ", command));

            // Build process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(new File(appPath));
            processBuilder.redirectErrorStream(false); // Keep stdout and stderr separate

            // Set environment variables
            Map<String, String> environment = processBuilder.environment();

            // Set NODE_ENV
            final String nodeEnv = context.getProperty(StandardNodeJSApplicationManagerService.NODE_ENV)
                    .evaluateAttributeExpressions().getValue();
            if (nodeEnv != null && !nodeEnv.trim().isEmpty()) {
                environment.put("NODE_ENV", nodeEnv);
            }

            // Set PORT
            final String port = context.getProperty(StandardNodeJSApplicationManagerService.APPLICATION_PORT)
                    .evaluateAttributeExpressions().getValue();
            if (port != null && !port.trim().isEmpty()) {
                environment.put("PORT", port);
            }

            // Parse and set custom environment variables
            final String customEnvVars = context.getProperty(StandardNodeJSApplicationManagerService.ENVIRONMENT_VARIABLES)
                    .evaluateAttributeExpressions().getValue();
            if (customEnvVars != null && !customEnvVars.trim().isEmpty()) {
                parseEnvironmentVariables(customEnvVars, environment);
            }

            logger.debug("Environment variables: NODE_ENV={}, PORT={}", nodeEnv, port);

            // Start the process
            nodeProcess = processBuilder.start();
            startTime = Instant.now();
            lastSuccessfulStart = Instant.now();

            logger.info("Node.js process started with PID: {}", nodeProcess.pid());

            // Start log capture threads
            startLogCapture();

            // Wait for startup
            waitForStartup();

            currentState.set(ProcessStatus.State.RUNNING);
            logger.info("Node.js application started successfully");

        } catch (Exception e) {
            currentState.set(ProcessStatus.State.CRASHED);
            logger.error("Failed to start Node.js application", e);
            throw new ProcessManagementException("Failed to start Node.js application", e);
        }
    }

    /**
     * Stops the Node.js application process gracefully, with forceful fallback.
     */
    public synchronized void stopApplication() throws ProcessManagementException {
        if (!isRunning()) {
            logger.info("Application is not running");
            return;
        }

        currentState.set(ProcessStatus.State.STOPPING);
        logger.info("Stopping Node.js application (PID: {})", nodeProcess.pid());

        try {
            final long shutdownTimeoutMs = context.getProperty(StandardNodeJSApplicationManagerService.SHUTDOWN_TIMEOUT)
                    .asTimePeriod(TimeUnit.MILLISECONDS);

            // Stop log capture threads
            stopLogCaptureThreads();

            // Attempt graceful shutdown (SIGTERM)
            nodeProcess.destroy();
            logger.debug("Sent SIGTERM to process");

            boolean terminated = nodeProcess.waitFor(shutdownTimeoutMs, TimeUnit.MILLISECONDS);

            if (!terminated) {
                logger.warn("Process did not terminate gracefully within {} ms, forcing termination", shutdownTimeoutMs);
                nodeProcess.destroyForcibly();
                terminated = nodeProcess.waitFor(FORCE_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (!terminated) {
                    logger.error("Process could not be forcefully terminated");
                    throw new ProcessManagementException("Failed to stop Node.js application");
                }
            }

            logger.info("Node.js application stopped successfully");
            currentState.set(ProcessStatus.State.STOPPED);
            nodeProcess = null;
            startTime = null;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while stopping Node.js application", e);
            throw new ProcessManagementException("Interrupted during shutdown", e);
        }
    }

    /**
     * Restarts the Node.js application.
     */
    public synchronized void restartApplication() throws ProcessManagementException {
        logger.info("Restarting Node.js application");

        restartCount.incrementAndGet();

        if (isRunning()) {
            stopApplication();
        }

        startApplication();
    }

    /**
     * Checks if the Node.js process is currently running.
     */
    public boolean isRunning() {
        return nodeProcess != null && nodeProcess.isAlive();
    }

    /**
     * Gets the current status of the process.
     */
    public ProcessStatus getStatus() {
        ProcessStatus.Builder builder = ProcessStatus.builder()
                .state(currentState.get())
                .restartCount(restartCount.get());

        if (nodeProcess != null && nodeProcess.isAlive()) {
            builder.processId(nodeProcess.pid())
                   .startTime(startTime);
        }

        return builder.build();
    }

    /**
     * Gets the process PID if running.
     */
    public Long getProcessId() {
        return nodeProcess != null && nodeProcess.isAlive() ? nodeProcess.pid() : null;
    }

    /**
     * Resets the restart counter (called after stable operation).
     */
    public void resetRestartCounter() {
        int previous = restartCount.getAndSet(0);
        if (previous > 0) {
            logger.info("Reset restart counter (was: {})", previous);
        }
    }

    /**
     * Gets the current restart count.
     */
    public int getRestartCount() {
        return restartCount.get();
    }

    /**
     * Marks the application as unhealthy (called when health checks fail).
     * Only updates state if currently RUNNING.
     */
    public void markUnhealthy() {
        if (currentState.compareAndSet(ProcessStatus.State.RUNNING, ProcessStatus.State.UNHEALTHY)) {
            logger.warn("Application marked as UNHEALTHY");
        }
    }

    /**
     * Marks the application as healthy/running (called when health checks pass).
     * Only updates state if currently UNHEALTHY.
     */
    public void markHealthy() {
        if (currentState.compareAndSet(ProcessStatus.State.UNHEALTHY, ProcessStatus.State.RUNNING)) {
            logger.info("Application marked as RUNNING (recovered from UNHEALTHY)");
        }
    }

    /**
     * Detects the appropriate package manager to use.
     */
    private String detectPackageManager(String appPath) {
        String configuredPM = context.getProperty(StandardNodeJSApplicationManagerService.PACKAGE_MANAGER)
                .evaluateAttributeExpressions().getValue();

        if (!"Auto".equalsIgnoreCase(configuredPM)) {
            return configuredPM.toLowerCase();
        }

        // Auto-detect based on lock files (priority order: bun, pnpm, yarn, npm)
        File appDir = new File(appPath);

        if (new File(appDir, "bun.lock").exists() || new File(appDir, "bun.lockb").exists()) {
            logger.info("Detected Bun package manager (bun.lock or bun.lockb found)");
            return "bun";
        }

        if (new File(appDir, "pnpm-lock.yaml").exists()) {
            logger.info("Detected pnpm package manager (pnpm-lock.yaml found)");
            return "pnpm";
        }

        if (new File(appDir, "yarn.lock").exists()) {
            logger.info("Detected Yarn package manager (yarn.lock found)");
            return "yarn";
        }

        if (new File(appDir, "package-lock.json").exists()) {
            logger.info("Detected npm package manager (package-lock.json found)");
            return "npm";
        }

        // Default to npm
        logger.info("No lock file found, defaulting to npm");
        return "npm";
    }

    /**
     * Parses environment variable string into the environment map.
     */
    private void parseEnvironmentVariables(String envVarsString, Map<String, String> environment) {
        if (envVarsString == null || envVarsString.trim().isEmpty()) {
            return;
        }

        String[] pairs = envVarsString.split(";");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            int equalsIndex = trimmed.indexOf('=');
            if (equalsIndex > 0 && equalsIndex < trimmed.length() - 1) {
                String key = trimmed.substring(0, equalsIndex).trim();
                String value = trimmed.substring(equalsIndex + 1).trim();
                environment.put(key, value);
                logger.debug("Set environment variable: {}={}", key, value);
            } else {
                logger.warn("Invalid environment variable format: {}", trimmed);
            }
        }
    }

    /**
     * Stops the log capture threads gracefully.
     */
    private void stopLogCaptureThreads() {
        stdoutCaptureThread = stopCaptureThread(stdoutCaptureThread, "stdout");
        stderrCaptureThread = stopCaptureThread(stderrCaptureThread, "stderr");
    }

    /**
     * Stops a single capture thread gracefully.
     *
     * @param thread the thread to stop
     * @param streamName name of the stream for logging purposes (e.g., "stdout", "stderr")
     * @return null (to simplify thread cleanup pattern)
     */
    private Thread stopCaptureThread(Thread thread, String streamName) {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for {} capture thread to stop", streamName);
            }
        }
        return null;
    }

    /**
     * Starts threads to capture stdout and stderr from the Node.js process.
     */
    private void startLogCapture() {
        if (nodeProcess == null) {
            return;
        }

        // Capture stdout
        stdoutCaptureThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    logCapture.captureStdout(line);
                    logger.info("[APP] {}", line);
                }
            } catch (IOException e) {
                if (nodeProcess != null && nodeProcess.isAlive() && !Thread.currentThread().isInterrupted()) {
                    logger.error("Error reading stdout", e);
                }
            }
        }, THREAD_NAME_STDOUT_CAPTURE);
        stdoutCaptureThread.setDaemon(true);
        stdoutCaptureThread.start();

        // Capture stderr
        stderrCaptureThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nodeProcess.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    logCapture.captureStderr(line);
                    logger.info("[APP-STDERR] {}", line);
                }
            } catch (IOException e) {
                if (nodeProcess != null && nodeProcess.isAlive() && !Thread.currentThread().isInterrupted()) {
                    logger.error("Error reading stderr", e);
                }
            }
        }, THREAD_NAME_STDERR_CAPTURE);
        stderrCaptureThread.setDaemon(true);
        stderrCaptureThread.start();
    }

    /**
     * Waits for the application to start up successfully.
     * Uses Process.waitFor() instead of busy-waiting for better performance.
     */
    private void waitForStartup() throws ProcessManagementException {
        try {
            // Wait for initial stability period - if process survives this, consider it started
            // Use waitFor with timeout instead of busy-wait polling
            boolean exited = nodeProcess.waitFor(STARTUP_STABILITY_CHECK_MS, TimeUnit.MILLISECONDS);

            if (exited) {
                // Process exited during startup - this is a failure
                int exitCode = nodeProcess.exitValue();
                throw new ProcessManagementException(
                    String.format("Node.js process exited during startup with code %d", exitCode));
            }

            // Process survived the stability check period - consider it successfully started
            // Health checks will verify actual application health
            logger.info("Process appears to have started (alive for {}+ seconds)",
                       STARTUP_STABILITY_CHECK_MS / 1000);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessManagementException("Interrupted during startup wait", e);
        }
    }
}