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

import org.apache.nifi.controller.ControllerService;
import java.util.List;

/**
 * Controller Service API for managing Node.js applications within NiFi.
 *
 * This service provides lifecycle management for Node.js applications, including:
 * - Starting and stopping Node.js processes
 * - Health monitoring and auto-restart capabilities
 * - Log capture and management
 * - Process status reporting
 *
 * The service manages the Node.js application as a child process using Java's
 * ProcessBuilder API, allowing NiFi to control the application lifecycle while
 * maintaining process isolation.
 */
public interface NodeJSApplicationManagerService extends ControllerService {

    /**
     * Checks if the managed Node.js application is currently running.
     *
     * @return true if the application process is alive and responding, false otherwise
     */
    boolean isApplicationRunning();

    /**
     * Gets the current status of the managed Node.js application.
     *
     * @return ProcessStatus object containing detailed status information including
     *         process ID, state, health check results, and uptime
     */
    ProcessStatus getApplicationStatus();

    /**
     * Retrieves the most recent log lines from the Node.js application.
     *
     * This returns captured stdout and stderr from the child process, up to the
     * specified number of lines.
     *
     * @param maxLines maximum number of log lines to return
     * @return list of log lines (newest first), or empty list if no logs available
     */
    List<String> getApplicationLogs(int maxLines);

    /**
     * Initiates a restart of the Node.js application.
     *
     * This performs a graceful shutdown (SIGTERM) followed by restart. If the
     * process does not stop within the configured timeout, a forceful shutdown
     * (SIGKILL) is performed.
     *
     * @throws ProcessManagementException if the restart operation fails or if
     *         the maximum number of restart attempts has been exceeded
     */
    void restartApplication() throws ProcessManagementException;

    /**
     * Gets the port number on which the Node.js application is configured to run.
     *
     * @return the application port number as configured in the service properties
     */
    int getApplicationPort();

    /**
     * Gets the full URL where the Node.js application is accessible.
     *
     * This constructs the URL based on the configured host and port, typically
     * in the format: http://localhost:PORT
     *
     * @return the application URL, or empty string if the application is not running
     */
    String getApplicationUrl();
}