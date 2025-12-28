package com.example.nifi.services;

import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.Validator;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.processor.util.StandardValidators;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.channels.ClosedChannelException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.websocket.core.exception.WebSocketTimeoutException;

@Tags({ "log", "viewer", "web", "jetty" })
@CapabilityDescription("Exposes a web interface to view a tail of a log file.")
public class StandardLogViewerService extends AbstractControllerService implements LogViewerService {
    // ... properties ...
    // I will just replace imports for now.
    // Actually replace_file_content is better for blocks.

    public static final PropertyDescriptor CONFIGURATION_MODE = new PropertyDescriptor.Builder()
            .name("Configuration Mode")
            .description("Specifies how the log file(s) are defined.")
            .required(true)
            .allowableValues("Single Log File", "Multiple Log Files")
            .defaultValue("Single Log File")
            .build();

    public static final PropertyDescriptor LOG_FILE = new PropertyDescriptor.Builder()
            .name("Log File Path")
            .description("Path to the log file to tail. Used only when Configuration Mode is 'Single Log File'.")
            .required(false) // Now optional as it depends on mode
            .addValidator(Validator.VALID)
            .defaultValue("/opt/nifi/nifi-current/logs/nifi-app.log")
            .dependsOn(CONFIGURATION_MODE, "Single Log File")
            .build();

    public static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("Web Server Port")
            .description("Port to run the embedded web server on.")
            .required(true)
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .defaultValue("9999")
            .build();

    public static final PropertyDescriptor READ_INTERVAL = new PropertyDescriptor.Builder()
            .name("Read Interval")
            .description("Time period to wait between log checks.")
            .required(true)
            .addValidator(StandardValidators.TIME_PERIOD_VALIDATOR)
            .defaultValue("1 sec")
            .build();

    private Server server;
    private ExecutorService executor;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Multi-log support
    private final ConcurrentHashMap<String, String> logFiles = new ConcurrentHashMap<>(); // Name -> Path

    private static final java.time.format.DateTimeFormatter REQUEST_LOG_FORMATTER = java.time.format.DateTimeFormatter
            .ofPattern("dd/MMM/yyyy:HH:mm:ss Z", java.util.Locale.US);

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return List.of(CONFIGURATION_MODE, LOG_FILE, PORT, READ_INTERVAL);
    }

    @Override
    protected PropertyDescriptor getSupportedDynamicPropertyDescriptor(final String propertyDescriptorName) {
        return new PropertyDescriptor.Builder()
                .name(propertyDescriptorName)
                .description("Log file path for " + propertyDescriptorName)
                .required(false)
                .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
                .dynamic(true)
                .build();
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) {
        final int port = context.getProperty(PORT).asInteger();
        final long intervalMillis = context.getProperty(READ_INTERVAL).asTimePeriod(TimeUnit.MILLISECONDS);
        final String mode = context.getProperty(CONFIGURATION_MODE).getValue();

        logFiles.clear();

        if ("Single Log File".equals(mode)) {
            String path = context.getProperty(LOG_FILE).getValue();
            if (path != null) {
                logFiles.put("Default", path);
            }
        } else {
            // Load dynamic properties
            for (PropertyDescriptor desc : context.getProperties().keySet()) {
                if (desc.isDynamic()) {
                    logFiles.put(desc.getName(), context.getProperty(desc).getValue());
                }
            }
        }

        running.set(true);
        executor = Executors.newCachedThreadPool();

        // Submit a tailer for each file
        for (String name : logFiles.keySet()) {
            final String path = logFiles.get(name);
            executor.submit(() -> tailLog(name, path, intervalMillis));
        }

        getLogger().info("Configured Log Files: " + logFiles);

        try {
            startServer(port);
            getLogger().info("Log Viewer Server started on port {}", new Object[] { port });
        } catch (Exception e) {
            getLogger().error("Failed to start Log Viewer Server", e);
        }
    }

    @OnDisabled
    public void onDisabled() {
        running.set(false);
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                getLogger().error("Failed to stop server", e);
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void startServer(int port) throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        connector.setReuseAddress(true);
        server.addConnector(connector);

        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        handler.setContextPath("/");
        server.setHandler(handler);

        JettyWebSocketServletContainerInitializer.configure(handler, (servletContext, container) -> {
            container.setIdleTimeout(Duration.ofMinutes(5));
            container.addMapping("/ws", (req, res) -> new LogWebSocket());
        });

        handler.addServlet(new ServletHolder(new LogPageServlet()), "/");

        server.start();
    }

    // ... onDisabled ...

    private void tailLog(String name, String path, long intervalMillis) {
        File file = new File(path);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(file.length()); // Start at end
            while (running.get()) {
                String line = raf.readLine();
                if (line != null) {
                    broadcast(name, line);
                } else {
                    Thread.sleep(intervalMillis);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                getLogger().error("Error tailing log file " + name, e);
            } else {
                getLogger().debug("Tailing stopped for " + name + ": " + e.getMessage());
            }
        }
    }

    private void broadcast(String logName, String message) {
        // Use the subscriptions map to broadcast to relevant clients
        broadcastToSubscribers(logName, message);
    }

    private final ConcurrentHashMap<Session, String> subscriptions = new ConcurrentHashMap<>();

    private void broadcastToSubscribers(String logName, String message) {
        for (Session session : sessions) {
            if (session.isOpen()) {
                String sub = subscriptions.getOrDefault(session, "Default");
                if (logName.equals(sub)) {
                    try {
                        session.sendText(message, Callback.NOOP);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
        }
    }

    @WebSocket
    public class LogWebSocket {
        private Session session;

        @OnWebSocketOpen
        public void onConnect(Session sess) {
            getLogger().info("WebSocket Connected: " + sess);
            this.session = sess;
            sessions.add(sess);
            try {
                sess.sendText("INFO System: Connected to Log Viewer Service", Callback.NOOP);

                // If multi-mode, send list
                if (!logFiles.isEmpty()) {
                    String list = String.join(",", logFiles.keySet());
                    sess.sendText("LOG_LIST " + list, Callback.NOOP);

                    // Auto-subscribe to "Default" if single mode, or first one
                    String defaultLog = "Default";
                    if (!logFiles.containsKey("Default") && !logFiles.isEmpty()) {
                        defaultLog = logFiles.keySet().iterator().next(); // Pick first
                    }

                    subscriptions.put(sess, defaultLog);
                    sess.sendText("SWITCH_LOG " + defaultLog, Callback.NOOP); // Tell client what we selected
                    // Removed sendTail to rely on client history request
                } else {
                    sess.sendText(
                            "WARN System: No configured log files found. Please check Controller Service properties.",
                            Callback.NOOP);
                }

            } catch (Throwable t) {
                getLogger().error("Error in onConnect", t);
            }
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason) {
            getLogger().info("WebSocket Closed: " + statusCode + " - " + reason);
            sessions.remove(this.session);
            subscriptions.remove(this.session);
        }

        @OnWebSocketMessage
        public void onMessage(String message) {
            getLogger().info("Received Message: " + message);
            if (message != null) {
                if (message.startsWith("HISTORY")) {
                    String[] parts = message.split(" ");
                    if (parts.length > 1) {
                        processHistoryRequest(session, parts[1]);
                    }
                } else if (message.startsWith("SELECT_LOG")) {
                    String[] parts = message.split(" ", 2);
                    if (parts.length > 1) {
                        String logName = parts[1].trim();
                        if (logFiles.containsKey(logName)) {
                            subscriptions.put(session, logName);
                            // Send tail of new log
                            try {
                                session.sendText("SWITCH_LOG " + logName, Callback.NOOP);
                                // Removed sendTail to rely on client history request
                            } catch (Exception e) {
                                getLogger().error("Error switching log", e);
                            }
                        }
                    }
                }
            }
        }

        @org.eclipse.jetty.websocket.api.annotations.OnWebSocketError
        public void onError(Throwable t) {
            if (isExpectedError(t)) {
                getLogger().debug("WebSocket Connection Issue (Client likely disconnected): " + t.getMessage());
            } else {
                getLogger().error("WebSocket Error", t);
            }
        }

        private boolean isExpectedError(Throwable t) {
            if (t instanceof WebSocketTimeoutException || t instanceof TimeoutException
                    || t instanceof ClosedChannelException) {
                return true;
            }
            if (t.getCause() != null && isExpectedError(t.getCause())) {
                return true;
            }
            return false;
        }

    }

    private void processHistoryRequest(Session session, String timeSpec) {
        // Find which log this session is subscribed to
        String logName = subscriptions.getOrDefault(session, "Default");
        String path = logFiles.get(logName);
        if (path == null) {
            return;
        }

        // timeSpec could be "10m", "30s", etc.
        long durationMillis = parseTimeSpec(timeSpec);
        if (durationMillis > 0) {
            String finalPath = path;
            try {
                session.sendText("HISTORY_START", Callback.NOOP);
            } catch (Exception e) {
                // ignore
            }
            executor.submit(() -> streamHistory(session, finalPath, durationMillis));
        }
    }

    private long parseTimeSpec(String timeSpec) {
        try {
            if (timeSpec.endsWith("m")) {
                return Long.parseLong(timeSpec.substring(0, timeSpec.length() - 1)) * 60 * 1000;
            } else if (timeSpec.endsWith("s")) {
                return Long.parseLong(timeSpec.substring(0, timeSpec.length() - 1)) * 1000;
            } else if (timeSpec.endsWith("h")) {
                return Long.parseLong(timeSpec.substring(0, timeSpec.length() - 1)) * 60 * 60 * 1000;
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return 0;
    }

    private void streamHistory(Session session, String path, long durationMillis) {
        File file = new File(path);
        if (!file.exists())
            return;

        long cutoff = System.currentTimeMillis() - durationMillis;

        // Simple inefficient implementation: scan from end backwards or just read whole
        // file if small?
        // For large files, we should scan backwards.
        // Let's implement a rudimentary backward scanner.

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = file.length();

            // Simplified approach: Start reading from 5MB back? No, precise calculation is
            // better.
            // Let's just seek back chunk by chunk.

            // Optimization: Just guess based on average line size? No, timestamps are
            // needed.

            // Read line by line from the beginning if file < 10MB
            if (length < 10 * 1024 * 1024) {
                raf.seek(0);
                String line;
                boolean sending = false;
                while ((line = raf.readLine()) != null) {
                    // Check timestamp if we aren't sending yet
                    if (!sending) {
                        // Parse timestamp assume standard format "yyyy-MM-dd HH:mm:ss"
                        // If line starts with date > cutoff, start sending
                        // This is brittle without regex, but sufficient for standard nifi logs.
                        // Regex: ^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}
                        // Let's assuming lines starting with digit are potential timestamps
                        if (isAfter(line, cutoff)) {
                            sending = true;
                        }
                    }

                    if (sending) {
                        if (session.isOpen()) {
                            session.sendText(line, Callback.NOOP);
                        } else {
                            break;
                        }
                    }
                }
            } else {
                // For larger files, maybe just tail last 1000 lines as fallback or implement
                // real seek.
                // Or just start reading from length - (approximated bytes).
                // 1 min of logs ~?? KB.
                // Let's implement a simple tail: last 10000 lines?
                // The user asked for "past X minutes".
                // Let's do a probabilistic seek:
                // average line 100 bytes.
                // 10 mins = maybe 6000 lines = 600KB?

                // Let's take a block of 5MB from end and scan that.
                long startPos = Math.max(0, length - (5 * 1024 * 1024));
                raf.seek(startPos);
                // consume partial line
                raf.readLine();

                String line;
                while ((line = raf.readLine()) != null) {
                    if (session.isOpen()) {
                        // Filter by time here
                        if (isAfter(line, cutoff)) {
                            session.sendText(line, Callback.NOOP);
                        }
                    }
                }
            }

        } catch (Exception e) {
            getLogger().error("Error streaming history", e);
        } finally {
            try {
                if (session.isOpen())
                    session.sendText("HISTORY_END", Callback.NOOP);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private boolean isAfter(String line, long cutoff) {
        if (line == null || line.length() < 10)
            return false;

        // Format 1: Standard NiFi Log (2025-12-24 09:21:46,123)
        // Check for YYYY-MM-DD
        if (line.length() >= 19 && Character.isDigit(line.charAt(0)) && line.charAt(4) == '-') {
            try {
                String dateStr = line.substring(0, 19).replace(' ', 'T');
                long time = java.time.LocalDateTime.parse(dateStr).atZone(java.time.ZoneId.systemDefault()).toInstant()
                        .toEpochMilli();
                return time >= cutoff;
            } catch (Exception e) {
                // ignore
            }
        }

        // Format 2: Common Log Format / Request Log (24/Dec/2025:09:21:46 +0000)
        // Pattern: [dd/MMM/yyyy:HH:mm:ss Z]
        int bracketStart = line.indexOf('[');
        int bracketEnd = line.indexOf(']');
        if (bracketStart != -1 && bracketEnd > bracketStart) {
            String datePart = line.substring(bracketStart + 1, bracketEnd);
            // 24/Dec/2025:09:21:46 +0000
            try {
                long time = java.time.ZonedDateTime.parse(datePart, REQUEST_LOG_FORMATTER).toInstant().toEpochMilli();
                return time >= cutoff;
            } catch (Exception e) {
                // ignore
            }
        }

        return false;
    }

    @WebServlet(urlPatterns = "/")
    public class LogPageServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/html");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().println(getHtml());
        }

        private String getHtml() {
            return "<!DOCTYPE html>\n" +
                    "<html><head><title>NiFi Log Viewer</title>\n" +
                    "<style>\n" +
                    "body { font-family: 'Consolas', 'Monaco', monospace; background: #1e1e1e; color: #d4d4d4; margin: 0; padding: 0; height: 100vh; display: flex; flex-direction: column; }\n"
                    +
                    "#header { background: #333; padding: 10px 20px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #444; }\n"
                    +
                    "#controls { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }\n" +
                    "#log-container { flex: 1; overflow-y: auto; padding: 20px; scroll-behavior: smooth; }\n" +
                    ".log-line { white-space: pre-wrap; word-wrap: break-word; font-size: 13px; line-height: 1.4; border-bottom: 1px solid #2a2a2a; }\n"
                    +
                    "input[type='text/number'], select { background: #252526; border: 1px solid #3c3c3c; color: #ccc; padding: 4px 8px; border-radius: 2px; }\n"
                    +
                    "button { background: #0e639c; color: white; border: none; padding: 5px 10px; cursor: pointer; border-radius: 2px; }\n"
                    +
                    "button:hover { background: #1177bb; }\n" +
                    "label { font-size: 0.9em; cursor: pointer; display: flex; align-items: center; gap: 4px; }\n" +
                    ".group { display: flex; align-items: center; gap: 10px; border-right: 1px solid #444; padding-right: 10px; }\n"
                    +
                    ".hidden { display: none !important; }\n" +
                    "</style>\n" +
                    "</head><body>\n" +
                    "<div id=\"header\">\n" +
                    "  <h3 id=\"title\">Log Viewer</h3>\n" +
                    "  <div id=\"controls\">\n" +
                    "    <div class=\"group\" id=\"logSelectGroup\" style=\"display:none;\">\n" +
                    "      <label>Log:</label>\n" +
                    "      <select id=\"logSelect\" onchange=\"switchLog()\"></select>\n" +
                    "    </div>\n" +
                    "    <div class=\"group\">\n" +
                    "      <label>History:</label>\n" +
                    "      <input type=\"text\" id=\"historyTime\" value=\"10m\" style=\"width: 50px;\" title=\"e.g. 10m, 1h, 30s\">\n"
                    +
                    "      <button onclick=\"loadHistory()\">Load</button>\n" +
                    "    </div>\n" +
                    "    <div class=\"group\">\n" +
                    "      <label>Filter:</label>\n" +
                    "      <input type=\"text\" id=\"filterInput\" onkeyup=\"updateVisibility()\" placeholder=\"Regex...\">\n"
                    +
                    "    </div>\n" +
                    "    <div class=\"group\">\n" +
                    "      <label><input type=\"checkbox\" id=\"checkInfo\" checked onchange=\"updateVisibility()\"> INFO</label>\n"
                    +
                    "      <input type=\"color\" id=\"colorInfo\" value=\"#4ec9b0\" onchange=\"updateColors()\">\n" +
                    "      <label><input type=\"checkbox\" id=\"checkWarn\" checked onchange=\"updateVisibility()\"> WARN</label>\n"
                    +
                    "      <input type=\"color\" id=\"colorWarn\" value=\"#dcdcaa\" onchange=\"updateColors()\">\n" +
                    "      <label><input type=\"checkbox\" id=\"checkError\" checked onchange=\"updateVisibility()\"> ERROR</label>\n"
                    +
                    "      <input type=\"color\" id=\"colorError\" value=\"#f44747\" onchange=\"updateColors()\">\n" +
                    "    </div>\n" +
                    "    <div class=\"group\">\n" +
                    "       <button onclick=\"downloadLog()\">Download</button>\n" +
                    "       <button onclick=\"clearLog()\">Clear</button>\n" +
                    "       <button onclick=\"scrollToBottom()\">Tail</button>\n" +
                    "    </div>\n" +
                    "  </div>\n" +
                    "</div>\n" +
                    "<div id=\"log-container\"><div id=\"log\"></div></div>\n" +
                    "<script>\n" +
                    "  var ws;\n" +
                    "  var autoScroll = true;\n" +
                    "  var lastSeverity = 'INFO'; \n" +
                    "  var isBuffering = false;\n" +
                    "  var logBuffer = [];\n" +
                    "  var logContainer = document.getElementById('log-container');\n" +
                    "  logContainer.onscroll = function(){\n" +
                    "    if(logContainer.scrollTop + logContainer.clientHeight >= logContainer.scrollHeight - 5) {\n" +
                    "      autoScroll = true;\n" +
                    "    } else {\n" +
                    "      autoScroll = false;\n" +
                    "    }\n" +
                    "  };\n" +
                    "\n" +
                    "  function connect() {\n" +
                    "    ws = new WebSocket('ws://' + window.location.host + '/ws');\n" +
                    "    ws.onopen = function() {\n" +
                    "        addLogLine('INFO Client: WebSocket connection opened');\n" +
                    "    };\n" +
                    "    ws.onmessage = function(event) {\n" +
                    "      var msg = event.data;\n" +
                    "      if (msg.startsWith('LOG_LIST ')) {\n" +
                    "          addLogLine('INFO Client: Received LOG_LIST');\n" +
                    "          try {\n" +
                    "              var logs = msg.substring(9).split(',');\n" +
                    "              var sel = document.getElementById('logSelect');\n" +
                    "              sel.innerHTML = '';\n" +
                    "              for(var i=0; i<logs.length; i++) {\n" +
                    "                  var opt = document.createElement('option');\n" +
                    "                  opt.value = logs[i];\n" +
                    "                  opt.text = logs[i];\n" +
                    "                  sel.appendChild(opt);\n" +
                    "              }\n" +
                    "              document.getElementById('logSelectGroup').style.display = 'flex';\n" +
                    "              addLogLine('INFO Client: Dropdown populated with ' + logs.length + ' logs');\n" +
                    "              // Restore selection\n" +
                    "              var storedLog = localStorage.getItem('nifi-log-viewer-selected');\n" +
                    "              if (storedLog && logs.includes(storedLog)) {\n" +
                    "                  addLogLine('INFO Client: Restoring previous selection: ' + storedLog);\n" +
                    "                  sel.value = storedLog;\n" +
                    "                  switchLog();\n" +
                    "              }\n" +
                    "          } catch(e) {\n" +
                    "              addLogLine('ERROR Client: Failed to populate dropdown: ' + e);\n" +
                    "          }\n" +
                    "      } else if (msg.startsWith('SWITCH_LOG ')) {\n" +
                    "          var logName = msg.substring(11);\n" +
                    "          document.getElementById('title').textContent = 'Log Viewer - ' + logName;\n" +
                    "          document.getElementById('logSelect').value = logName;\n" +
                    "          addLogLine('INFO Client: Switched to log: ' + logName);\n" +
                    "          loadHistory();\n" +
                    "      } else if (msg === 'HISTORY_START') {\n" +
                    "          isBuffering = true;\n" +
                    "          logBuffer = [];\n" +
                    "          document.getElementById('log').innerHTML = ''; \n" +
                    "          addLogLine('INFO Client: History load started');\n" +
                    "      } else if (msg === 'HISTORY_END') {\n" +
                    "          isBuffering = false;\n" +
                    "          addLogLine('INFO Client: History load finished. Applying ' + logBuffer.length + ' buffered events.');\n"
                    +
                    "          for (var k=0; k<logBuffer.length; k++) {\n" +
                    "              addLogLine(logBuffer[k]);\n" +
                    "          }\n" +
                    "          logBuffer = [];\n" +
                    "          scrollToBottom();\n" +
                    "      } else {\n" +
                    "          if (isBuffering) {\n" +
                    "              logBuffer.push(msg);\n" +
                    "          } else {\n" +
                    "              addLogLine(msg);\n" +
                    "          }\n" +
                    "      }\n" +
                    "    };\n" +
                    "    ws.onclose = function() {\n" +
                    "      addLogLine('WARN Client: WebSocket connection closed, reconnecting...');\n" +
                    "      setTimeout(connect, 1000);\n" +
                    "    };\n" +
                    "    ws.onerror = function(err) {\n" +
                    "        addLogLine('ERROR Client: WebSocket error');\n" +
                    "    };\n" +
                    "  }\n" +
                    "\n" +
                    "  function switchLog() {\n" +
                    "      var name = document.getElementById('logSelect').value;\n" +
                    "      localStorage.setItem('nifi-log-viewer-selected', name);\n" +
                    "      addLogLine('INFO Client: Requesting log switch to ' + name);\n" +
                    "      ws.send('SELECT_LOG ' + name);\n" +
                    "  }\n" +
                    "\n" +
                    "  function downloadLog() {\n" +
                    "      var lines = [];\n" +
                    "      var divs = document.getElementById('log').getElementsByClassName('log-line');\n" +
                    "      for(var i=0; i<divs.length; i++) {\n" +
                    "          lines.push(divs[i].textContent);\n" +
                    "      }\n" +
                    "      var blob = new Blob([lines.join('\\n')], {type: 'text/plain'});\n" +
                    "      var url = URL.createObjectURL(blob);\n" +
                    "      var a = document.createElement('a');\n" +
                    "      a.href = url;\n" +
                    "      a.download = (document.getElementById('logSelect').value || 'nifi-log') + '.txt';\n" +
                    "      document.body.appendChild(a);\n" +
                    "      a.click();\n" +
                    "      document.body.removeChild(a);\n" +
                    "      URL.revokeObjectURL(url);\n" +
                    "  }\n" +
                    "\n" +
                    "  function detectSeverity(text) {\n" +
                    "      if (text.includes(' INFO ')) return 'INFO';\n" +
                    "      if (text.includes(' WARN ')) return 'WARN';\n" +
                    "      if (text.includes(' ERROR ')) return 'ERROR';\n" +
                    "      return null;\n" +
                    "  }\n" +
                    "\n" +
                    "  function addLogLine(text) {\n" +
                    "    var sev = detectSeverity(text);\n" +
                    "    if (sev) {\n" +
                    "        lastSeverity = sev;\n" +
                    "    }\n" +
                    "    var div = document.createElement('div');\n" +
                    "    div.className = 'log-line';\n" +
                    "    div.textContent = text;\n" +
                    "    div.dataset.severity = lastSeverity;\n" +
                    "\n" +
                    "    applyStyle(div, lastSeverity);\n" +
                    "    checkVisibility(div, lastSeverity, text);\n" +
                    "\n" +
                    "    document.getElementById('log').appendChild(div);\n" +
                    "    if (autoScroll) scrollToBottom();\n" +
                    "  }\n" +
                    "\n" +
                    "  function applyStyle(div, severity) {\n" +
                    "    if (severity === 'INFO') div.style.color = document.getElementById('colorInfo').value;\n" +
                    "    else if (severity === 'WARN') div.style.color = document.getElementById('colorWarn').value;\n"
                    +
                    "    else if (severity === 'ERROR') div.style.color = document.getElementById('colorError').value;\n"
                    +
                    "  }\n" +
                    "\n" +
                    "  function checkVisibility(div, severity, text) {\n" +
                    "      var visible = true;\n" +
                    "      // 1. Check severity checkbox\n" +
                    "      var idName = 'check' + severity.charAt(0).toUpperCase() + severity.slice(1).toLowerCase();\n"
                    +
                    "      var cb = document.getElementById(idName);\n" +
                    "      if (cb && !cb.checked) visible = false;\n" +
                    "\n" +
                    "      // 2. Check regex\n" +
                    "      var term = document.getElementById('filterInput').value;\n" +
                    "      if (term && !new RegExp(term, 'i').test(text)) visible = false;\n" +
                    "\n" +
                    "      if (visible) div.classList.remove('hidden');\n" +
                    "      else div.classList.add('hidden');\n" +
                    "  }\n" +
                    "\n" +
                    "  function updateVisibility() {\n" +
                    "      var lines = document.getElementsByClassName('log-line');\n" +
                    "      for (var i = 0; i < lines.length; i++) {\n" +
                    "          checkVisibility(lines[i], lines[i].dataset.severity, lines[i].textContent);\n" +
                    "      }\n" +
                    "  }\n" +
                    "\n" +
                    "  function updateColors() {\n" +
                    "    var lines = document.getElementsByClassName('log-line');\n" +
                    "    for (var i = 0; i < lines.length; i++) {\n" +
                    "      applyStyle(lines[i], lines[i].dataset.severity);\n" +
                    "    }\n" +
                    "  }\n" +
                    "\n" +
                    "  function loadHistory() {\n" +
                    "    document.getElementById('log').innerHTML = '';\n" +
                    "    lastSeverity = 'INFO'; // Reset\n" +
                    "    var time = document.getElementById('historyTime').value;\n" +
                    "    ws.send('HISTORY ' + time);\n" +
                    "  }\n" +
                    "  function clearLog() {\n" +
                    "    document.getElementById('log').innerHTML = '';\n" +
                    "    lastSeverity = 'INFO';\n" +
                    "  }\n" +
                    "  function scrollToBottom() {\n" +
                    "    logContainer.scrollTop = logContainer.scrollHeight;\n" +
                    "    autoScroll = true;\n" +
                    "  }\n" +
                    "  connect();\n" +
                    "</script>\n" +
                    "</body></html>";
        }
    }
}
