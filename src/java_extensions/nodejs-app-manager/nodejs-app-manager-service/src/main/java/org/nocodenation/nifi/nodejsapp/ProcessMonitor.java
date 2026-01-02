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

import org.apache.nifi.logging.ComponentLog;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitors the health of a running Node.js application process.
 *
 * This class performs periodic health checks using HTTP requests and can
 * automatically restart the application if it becomes unhealthy or crashes.
 *
 * Features:
 * - HTTP endpoint health checking
 * - Process liveness monitoring
 * - Auto-restart with configurable attempt limits
 * - Restart counter reset after stability period (5 minutes)
 */
public class ProcessMonitor {

    // Constants for timeouts and thresholds
    private static final long STABILITY_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes - uptime before resetting restart counter
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_SEC = 5; // 5 seconds - max wait for executor service shutdown

    // Constants for strings
    private static final String HTTP_PROTOCOL = "http://";
    private static final String THREAD_NAME_HEALTH_CHECK = "NodeJS-HealthCheck";
    private static final String HTTP_METHOD_GET = "GET";

    private final ProcessLifecycleManager lifecycleManager;
    private final String host;
    private final int port;
    private final String healthCheckPath;
    private final long healthCheckIntervalMs;
    private final long healthCheckTimeoutMs;
    private final boolean autoRestart;
    private final int maxRestartAttempts;
    private final ComponentLog logger;

    private ScheduledExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastHealthCheck = new AtomicReference<>();
    private final AtomicReference<String> lastHealthCheckMessage = new AtomicReference<>("Not yet checked");
    private final AtomicBoolean lastHealthCheckPassing = new AtomicBoolean(false);

    public ProcessMonitor(ProcessLifecycleManager lifecycleManager,
                          String host,
                          int port,
                          String healthCheckPath,
                          long healthCheckIntervalMs,
                          long healthCheckTimeoutMs,
                          boolean autoRestart,
                          int maxRestartAttempts,
                          ComponentLog logger) {
        this.lifecycleManager = lifecycleManager;
        this.host = host;
        this.port = port;
        this.healthCheckPath = healthCheckPath;
        this.healthCheckIntervalMs = healthCheckIntervalMs;
        this.healthCheckTimeoutMs = healthCheckTimeoutMs;
        this.autoRestart = autoRestart;
        this.maxRestartAttempts = maxRestartAttempts;
        this.logger = logger;
    }

    /**
     * Starts the health monitoring process.
     *
     * <p>Creates a scheduled executor service that performs periodic health checks
     * according to the configured interval. The health checks monitor both process
     * liveness and HTTP endpoint availability.</p>
     *
     * <p>This method is idempotent - calling it multiple times will log a warning
     * but will not create additional monitoring threads.</p>
     *
     * <p>Health monitoring includes:</p>
     * <ul>
     *   <li>Process liveness check - verifies the Node.js process is still running</li>
     *   <li>HTTP health check - calls the configured endpoint and expects HTTP 200</li>
     *   <li>Auto-restart logic - restarts unhealthy applications if configured</li>
     *   <li>Restart counter management - resets counter after stability period (5 minutes)</li>
     * </ul>
     *
     * @see #stop()
     */
    public void start() {
        if (running.getAndSet(true)) {
            logger.warn("Process monitor is already running");
            return;
        }

        logger.info("Starting process monitor (interval: {}ms, timeout: {}ms, auto-restart: {})",
                healthCheckIntervalMs, healthCheckTimeoutMs, autoRestart);

        executorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, THREAD_NAME_HEALTH_CHECK);
            thread.setDaemon(true);
            return thread;
        });

        executorService.scheduleAtFixedRate(
                this::performHealthCheck,
                healthCheckIntervalMs, // Initial delay
                healthCheckIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stops the health monitoring process. Synchronized to prevent concurrent stop() calls.
     * This method is idempotent - can be called multiple times safely.
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        logger.info("Stopping process monitor");

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executorService.shutdownNow();
            }
            executorService = null;
        }
    }

    /**
     * Performs a health check on the Node.js application.
     */
    private void performHealthCheck() {
        try {
            // First check if process is alive
            if (!lifecycleManager.isRunning()) {
                lifecycleManager.markUnhealthy();
                handleUnhealthyApplication("Process is not running");
                return;
            }

            // Check if application has been stable long enough to reset restart counter
            ProcessStatus status = lifecycleManager.getStatus();
            if (status.getStartTime() != null) {
                Duration uptime = Duration.between(status.getStartTime(), Instant.now());
                if (uptime.toMillis() >= STABILITY_THRESHOLD_MS && lifecycleManager.getRestartCount() > 0) {
                    logger.info("Application has been stable for {} minutes, resetting restart counter",
                            STABILITY_THRESHOLD_MS / 60000);
                    lifecycleManager.resetRestartCounter();
                }
            }

            // Perform HTTP health check
            boolean healthy = performHttpHealthCheck();

            lastHealthCheck.set(Instant.now());
            lastHealthCheckPassing.set(healthy);

            if (!healthy) {
                lifecycleManager.markUnhealthy();
                handleUnhealthyApplication("HTTP health check failed");
            } else {
                lifecycleManager.markHealthy();
            }

        } catch (Exception e) {
            String errorMessage = "Error during health check: " + e.getMessage();
            logger.error(errorMessage, e);
            lastHealthCheckMessage.set("Health check error: " + e.getMessage());
            lastHealthCheckPassing.set(false);
        }
    }

    /**
     * Performs an HTTP health check against the configured endpoint.
     */
    private boolean performHttpHealthCheck() {
        HttpURLConnection connection = null;
        try {
            String healthUrl = String.format("%s%s:%d%s", HTTP_PROTOCOL, host, port, healthCheckPath);
            logger.debug("Performing health check: {}", healthUrl);

            URL url = URI.create(healthUrl).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(HTTP_METHOD_GET);
            connection.setConnectTimeout((int) healthCheckTimeoutMs);
            connection.setReadTimeout((int) healthCheckTimeoutMs);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                lastHealthCheckMessage.set("Healthy (HTTP 200)");
                logger.debug("Health check passed: HTTP {}", responseCode);
                return true;
            } else {
                lastHealthCheckMessage.set("Unhealthy (HTTP " + responseCode + ")");
                logger.warn("Health check failed: HTTP {}", responseCode);
                return false;
            }

        } catch (IOException e) {
            lastHealthCheckMessage.set("Health check failed: " + e.getMessage());
            logger.warn("Health check failed: {}", e.getMessage());
            return false;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Handles an unhealthy application state, potentially triggering auto-restart.
     */
    private void handleUnhealthyApplication(String reason) {
        logger.warn("Application is unhealthy: {}", reason);
        lastHealthCheckMessage.set(reason);
        lastHealthCheckPassing.set(false);

        if (!autoRestart) {
            logger.info("Auto-restart is disabled, not attempting restart");
            return;
        }

        int currentRestartCount = lifecycleManager.getRestartCount();
        if (currentRestartCount >= maxRestartAttempts) {
            logger.error("Maximum restart attempts ({}) reached, not restarting", maxRestartAttempts);
            lastHealthCheckMessage.set(String.format(
                    "Max restart attempts (%d) exceeded", maxRestartAttempts));
            return;
        }

        logger.info("Attempting auto-restart (attempt {} of {})",
                currentRestartCount + 1, maxRestartAttempts);

        try {
            lifecycleManager.restartApplication();
            logger.info("Application restarted successfully");

        } catch (ProcessManagementException e) {
            logger.error("Failed to restart application", e);
            lastHealthCheckMessage.set("Restart failed: " + e.getMessage());
        }
    }

    /**
     * Gets the timestamp of the last health check.
     */
    public Instant getLastHealthCheck() {
        return lastHealthCheck.get();
    }

    /**
     * Gets the message from the last health check.
     */
    public String getLastHealthCheckMessage() {
        return lastHealthCheckMessage.get();
    }

    /**
     * Returns whether the last health check passed.
     */
    public boolean isLastHealthCheckPassing() {
        return lastHealthCheckPassing.get();
    }
}