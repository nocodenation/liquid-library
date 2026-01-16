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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe circular buffer for capturing stdout and stderr from the Node.js process.
 *
 * This class maintains a fixed-size buffer of recent log lines from the managed
 * Node.js application, allowing retrieval of recent logs for debugging and monitoring.
 *
 * Features:
 * - Thread-safe operations using read-write locks
 * - Circular buffer with configurable size
 * - Automatic timestamp prefixing
 * - Separate tracking of stdout and stderr
 * - Efficient retrieval of recent N lines
 */
public class LogCapture {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    private final int maxBufferSize;
    private final LinkedList<String> logBuffer;
    private final ReadWriteLock lock;

    /**
     * Creates a new LogCapture with the specified buffer size.
     *
     * @param maxBufferSize maximum number of log lines to retain
     */
    public LogCapture(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
        this.logBuffer = new LinkedList<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Captures a line from stdout.
     *
     * @param line the log line to capture
     */
    public void captureStdout(String line) {
        if (line == null) {
            return;
        }
        addLogLine("[STDOUT] " + line);
    }

    /**
     * Captures a line from stderr.
     *
     * @param line the log line to capture
     */
    public void captureStderr(String line) {
        if (line == null) {
            return;
        }
        addLogLine("[STDERR] " + line);
    }

    /**
     * Adds a log line to the circular buffer.
     */
    private void addLogLine(String line) {
        lock.writeLock().lock();
        try {
            String timestampedLine = TIMESTAMP_FORMATTER.format(Instant.now()) + " " + line;
            logBuffer.addLast(timestampedLine);

            // Remove oldest entries if buffer exceeds max size
            while (logBuffer.size() > maxBufferSize) {
                logBuffer.removeFirst();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the most recent log lines.
     *
     * @param maxLines maximum number of lines to return (most recent first)
     * @return list of log lines, with newest first
     */
    public List<String> getRecentLogs(int maxLines) {
        lock.readLock().lock();
        try {
            if (logBuffer.isEmpty()) {
                return Collections.emptyList();
            }

            int linesToReturn = Math.min(maxLines, logBuffer.size());
            List<String> recentLogs = new ArrayList<>(linesToReturn);

            // Get last N entries (newest first)
            for (int i = 0; i < linesToReturn; i++) {
                int index = logBuffer.size() - 1 - i;
                recentLogs.add(logBuffer.get(index));
            }

            return recentLogs;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets all captured logs.
     *
     * @return list of all log lines, with newest first
     */
    public List<String> getAllLogs() {
        lock.readLock().lock();
        try {
            List<String> allLogs = new ArrayList<>(logBuffer);
            Collections.reverse(allLogs); // Newest first
            return allLogs;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the current number of log lines in the buffer.
     *
     * @return number of buffered log lines
     */
    public int getBufferSize() {
        lock.readLock().lock();
        try {
            return logBuffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears all captured logs.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            logBuffer.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets the maximum buffer size.
     *
     * @return maximum number of log lines that can be buffered
     */
    public int getMaxBufferSize() {
        return maxBufferSize;
    }
}