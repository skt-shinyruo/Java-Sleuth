package com.javasleuth.security;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.util.SleuthLogger;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class AuditLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final ProductionConfig config;
    private final BlockingQueue<AuditEvent> auditQueue;
    private final AtomicBoolean running;
    private final AtomicLong eventCounter;
    private final AtomicLong droppedCounter;
    private final Thread auditThread;

    private PrintWriter auditWriter;
    private PrintWriter securityWriter;

    private static AuditLogger instance;

    private AuditLogger() {
        this.config = ProductionConfig.getInstance();
        this.auditQueue = new LinkedBlockingQueue<>(1000);
        this.running = new AtomicBoolean(false);
        this.eventCounter = new AtomicLong(0);
        this.droppedCounter = new AtomicLong(0);
        this.auditThread = new Thread(this::processAuditEvents, "sleuth-audit-logger");

        initializeWriters();
        start();
    }

    public static synchronized AuditLogger getInstance() {
        if (instance == null) {
            instance = new AuditLogger();
        }
        return instance;
    }

    private void initializeWriters() {
        if (!config.isAuditLogEnabled()) {
            return;
        }
        try {
            String auditPath = config.getAuditLogFilePath();
            String securityPath = config.getSecurityLogFilePath();
            auditWriter = openWriter(auditPath);
            securityWriter = openWriter(securityPath);
        } catch (IOException | RuntimeException e) {
            SleuthLogger.warn("Failed to initialize audit log writers: " + e.getMessage(), e);
        }
    }

    private PrintWriter openWriter(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path.trim());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            // Best-effort; ignore mkdir failures (permission/readonly)
            parent.mkdirs();
        }
        return new PrintWriter(new FileWriter(file, true));
    }

    private void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        auditThread.setDaemon(true);
        auditThread.start();
    }

    private void processAuditEvents() {
        while (running.get() || !auditQueue.isEmpty()) {
            try {
                AuditEvent event = auditQueue.take();
                writeAuditEvent(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                SleuthLogger.warn("Error processing audit event: " + e.getMessage(), e);
            }
        }
    }

    private void writeAuditEvent(AuditEvent event) {
        eventCounter.incrementAndGet();
        String timestamp = TIMESTAMP_FORMATTER.format(event.getTimestamp());
        String logEntry = String.format("[%s] [%s] [%s] %s - %s",
            timestamp, event.getLevel(), event.getCategory(), event.getAction(), event.getDetails());

        // Optional: mirror audit events to console (disabled by default to avoid polluting target JVM stdout/stderr)
        if (config.isAuditConsoleEnabled()) {
            String line = "AUDIT: " + logEntry;
            String lvl = event.getLevel() != null ? event.getLevel().trim().toUpperCase() : "";
            SleuthLogger.Level outLevel = SleuthLogger.Level.INFO;
            if ("ERROR".equals(lvl)) {
                outLevel = SleuthLogger.Level.ERROR;
            } else if ("WARN".equals(lvl)) {
                outLevel = SleuthLogger.Level.WARN;
            }
            SleuthLogger.auditConsole(outLevel, line);
        }

        // Write to appropriate log file(s)
        if (config.isAuditLogEnabled()) {
            if ("SECURITY".equals(event.getCategory()) && securityWriter != null) {
                securityWriter.println(logEntry);
                securityWriter.flush();
            } else if (auditWriter != null) {
                auditWriter.println(logEntry);
                auditWriter.flush();
            }
        }
    }

    // Public audit methods
    public void logCommandExecution(String sessionId, String clientInfo, String command, String[] args, boolean success) {
        if (!config.isAuditLoggingEnabled()) return;

        String details = String.format("Command: %s, Args: %s, Success: %s",
            sanitizeForLog(command), formatArgsForAudit(command, args), success);

        AuditEvent event = new AuditEvent(
            success ? "INFO" : "WARN",
            "COMMAND",
            "EXECUTE",
            details,
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logSecurityViolation(String sessionId, String clientInfo, String violation, String details) {
        AuditEvent event = new AuditEvent(
            "ERROR",
            "SECURITY",
            "VIOLATION",
            String.format("%s: %s", violation, details),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logConnectionEvent(String sessionId, String clientInfo, String action) {
        if (!config.isAuditLoggingEnabled()) return;

        AuditEvent event = new AuditEvent(
            "INFO",
            "CONNECTION",
            action,
            String.format("Client connection %s", action.toLowerCase()),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logSystemEvent(String action, String details) {
        if (!config.isAuditLoggingEnabled()) return;

        AuditEvent event = new AuditEvent(
            "INFO",
            "SYSTEM",
            action,
            details,
            null,
            "system"
        );

        queueEvent(event);
    }

    public void logAuthorizationFailure(String sessionId, String clientInfo, String command, String reason) {
        AuditEvent event = new AuditEvent(
            "WARN",
            "SECURITY",
            "AUTHORIZATION_FAILED",
            String.format("Command: %s, Reason: %s", command, reason),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logInputValidationFailure(String sessionId, String clientInfo, String input, String reason) {
        AuditEvent event = new AuditEvent(
            "WARN",
            "SECURITY",
            "INPUT_VALIDATION_FAILED",
            String.format("Input: %s, Reason: %s", sanitizeForLog(input), reason),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logPerformanceAlert(String component, String metric, String value, String threshold) {
        if (!config.isPerformanceLogEnabled()) return;

        AuditEvent event = new AuditEvent(
            "WARN",
            "PERFORMANCE",
            "THRESHOLD_EXCEEDED",
            String.format("Component: %s, Metric: %s, Value: %s, Threshold: %s", component, metric, value, threshold),
            null,
            "system"
        );

        queueEvent(event);
    }

    // Authentication and authorization logging methods
    public void logAuthenticationAttempt(String username, String clientInfo, boolean success, String details) {
        AuditEvent event = new AuditEvent(
            success ? "INFO" : "WARN",
            "SECURITY",
            "AUTHENTICATION_ATTEMPT",
            String.format("Username: %s, Success: %s, Details: %s", sanitizeForLog(username), success, details),
            null,
            clientInfo
        );

        queueEvent(event);
    }

    public void logSessionStart(String sessionId, String clientInfo, String role) {
        AuditEvent event = new AuditEvent(
            "INFO",
            "SECURITY",
            "SESSION_START",
            String.format("Session started, Role: %s", role),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logSessionEnd(String sessionId, String clientInfo, String reason) {
        AuditEvent event = new AuditEvent(
            "INFO",
            "SECURITY",
            "SESSION_END",
            String.format("Session ended, Reason: %s", reason),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logCommandAuthorization(String sessionId, String command, String[] args, boolean success, String role) {
        if (!config.isAuditLoggingEnabled()) return;

        AuditEvent event = new AuditEvent(
            success ? "INFO" : "WARN",
            "SECURITY",
            "COMMAND_AUTHORIZATION",
            String.format("Command: %s, Args: %s, Role: %s, Success: %s",
                sanitizeForLog(command), formatArgsForAudit(command, args), sanitizeForLog(role), success),
            sessionId,
            null
        );

        queueEvent(event);
    }

    public void logPrivilegedOperation(String sessionId, String operation, String details) {
        AuditEvent event = new AuditEvent(
            "WARN",
            "SECURITY",
            "PRIVILEGED_OPERATION",
            String.format("Operation: %s, Details: %s", operation, details),
            sessionId,
            null
        );

        queueEvent(event);
    }

    public void logRateLimitViolation(String sessionId, String clientInfo, String command, int currentRate, int limit) {
        AuditEvent event = new AuditEvent(
            "WARN",
            "SECURITY",
            "RATE_LIMIT_VIOLATION",
            String.format("Command: %s, Current: %d, Limit: %d", command, currentRate, limit),
            sessionId,
            clientInfo
        );

        queueEvent(event);
    }

    public void logConfigurationChange(String sessionId, String parameter, String oldValue, String newValue) {
        String safeOld = oldValue;
        String safeNew = newValue;
        if (isSensitiveKey(parameter)) {
            safeOld = maskValue(oldValue);
            safeNew = maskValue(newValue);
        }
        AuditEvent event = new AuditEvent(
            "INFO",
            "CONFIGURATION",
            "PARAMETER_CHANGED",
            String.format("Parameter: %s, Old: %s, New: %s",
                parameter, sanitizeForLog(safeOld), sanitizeForLog(safeNew)),
            sessionId,
            null
        );

        queueEvent(event);
    }

    private void queueEvent(AuditEvent event) {
        try {
            boolean ok = auditQueue.offer(event);
            if (!ok) {
                droppedCounter.incrementAndGet();
                long d = droppedCounter.get();
                if (d % 100 == 1) {
                    SleuthLogger.warn("Audit queue full; dropped events=" + d);
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private String sanitizeForLog(String input) {
        if (input == null) return "null";
        if (input.length() > 100) {
            input = input.substring(0, 100) + "...";
        }
        // Remove potential log injection characters
        return input.replaceAll("[\r\n\t]", "_");
    }

    private String formatArgsForAudit(String command, String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }

        String cmd = command != null ? command.trim().toLowerCase() : "";
        String[] safe = Arrays.copyOf(args, args.length);
        for (int i = 0; i < safe.length; i++) {
            if (safe[i] == null) {
                safe[i] = "null";
            }
        }

        if ("auth".equals(cmd)) {
            if (safe.length >= 3) {
                safe[2] = "***";
            }
        } else if ("config".equals(cmd)) {
            if (safe.length >= 4 && "set".equalsIgnoreCase(safe[1]) && isSensitiveKey(safe[2])) {
                safe[3] = "***";
            }
        } else if ("sysprop".equals(cmd)) {
            if (safe.length >= 4 && "set".equalsIgnoreCase(safe[1]) && isSensitiveKey(safe[2])) {
                safe[3] = "***";
            }
        }

        return sanitizeForLog(String.join(" ", safe));
    }

    private boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("password") || k.contains("secret") || k.contains("token") ||
               k.contains("credential") || k.contains("session") || k.contains("apikey") || k.contains("api_key");
    }

    private String maskValue(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= 4) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    private String maskSessionId(String sessionId) {
        // Session IDs are bearer tokens; never write them in full to logs.
        return maskValue(sessionId);
    }

    public void shutdown() {
        logSystemEvent("AUDIT_SHUTDOWN", "Audit logger shutting down");
        running.set(false);

        // Process remaining events
        while (!auditQueue.isEmpty() && auditThread.isAlive()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        auditThread.interrupt();

        try {
            auditThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (auditWriter != null) {
            auditWriter.flush();
            auditWriter.close();
        }
        if (securityWriter != null) {
            securityWriter.flush();
            securityWriter.close();
        }
    }

    public String getAuditStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== AUDIT LOGGING STATUS ===\n");
        status.append("Audit Logging: ").append(config.isAuditLoggingEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("File Output: ").append(config.isAuditLogEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Console Output: ").append(config.isAuditConsoleEnabled() ? "ENABLED" : "DISABLED").append("\n");
        status.append("Events Processed: ").append(eventCounter.get()).append("\n");
        status.append("Events Dropped: ").append(droppedCounter.get()).append("\n");
        status.append("Queue Size: ").append(auditQueue.size()).append("/1000\n");
        status.append("Thread Status: ").append(auditThread.isAlive() ? "RUNNING" : "STOPPED").append("\n");
        status.append("Audit Log File: ").append(config.getAuditLogFilePath()).append("\n");
        status.append("Security Log File: ").append(config.getSecurityLogFilePath()).append("\n");

        // Queue health
        int queueSize = auditQueue.size();
        if (queueSize > 800) {
            status.append("⚠️ WARNING: Audit queue is nearly full\n");
        } else if (queueSize > 500) {
            status.append("⚠️ CAUTION: Audit queue is filling up\n");
        } else {
            status.append("✅ Audit queue healthy\n");
        }

        return status.toString();
    }

    public long getDroppedCount() {
        return droppedCounter.get();
    }

    /**
     * Get recent audit events summary
     */
    public String getRecentEvents(int count) {
        StringBuilder events = new StringBuilder();
        events.append("=== RECENT AUDIT EVENTS ===\n");
        events.append("Note: Java-Sleuth does not keep an in-memory audit history.\n");
        events.append("For complete audit trail, check log files:\n");
        events.append("- ").append(config.getAuditLogFilePath()).append("\n");
        events.append("- ").append(config.getSecurityLogFilePath()).append("\n\n");
        events.append("Total events logged: ").append(eventCounter.get()).append("\n");
        return events.toString();
    }

    /**
     * Emergency audit mode - log everything
     */
    public void enableEmergencyAuditMode() {
        logSystemEvent("EMERGENCY_AUDIT_MODE", "Emergency audit mode enabled - all events will be logged");
        if (config.isAuditConsoleEnabled()) {
            SleuthLogger.auditConsole(SleuthLogger.Level.WARN, "🚨 EMERGENCY AUDIT MODE ENABLED - All activities are being logged");
        }
    }

    // Inner class for audit events
    private static class AuditEvent {
        private final String level;
        private final String category;
        private final String action;
        private final String details;
        private final String sessionId;
        private final String clientInfo;
        private final Instant timestamp;

        public AuditEvent(String level, String category, String action, String details, String sessionId, String clientInfo) {
            this.level = level;
            this.category = category;
            this.action = action;
            this.details = details;
            this.sessionId = sessionId;
            this.clientInfo = clientInfo;
            this.timestamp = Instant.now();
        }

        // Getters
        public String getLevel() { return level; }
        public String getCategory() { return category; }
        public String getAction() { return action; }
        public String getDetails() { return details; }
        public String getSessionId() { return sessionId; }
        public String getClientInfo() { return clientInfo; }
        public Instant getTimestamp() { return timestamp; }
    }
}
