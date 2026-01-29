package com.javasleuth.security;

import com.javasleuth.config.ProductionConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class AuthenticationManager {
    private static AuthenticationManager instance;
    private final ProductionConfig config;
    private final AuditLogger auditLogger;

    // Session management
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, AttemptInfo> loginAttempts = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    // Configuration
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MINUTES = 15;
    private static final long SESSION_TIMEOUT_MINUTES = 60;
    private static final String DEFAULT_ADMIN_PASSWORD = "sleuth_admin_2023!";

    private static class SessionInfo {
        final String sessionId;
        final String clientInfo;
        final Instant createdAt;
        final UserRole role;
        volatile Instant lastActivity;

        SessionInfo(String sessionId, String clientInfo, UserRole role) {
            this.sessionId = sessionId;
            this.clientInfo = clientInfo;
            this.role = role;
            this.createdAt = Instant.now();
            this.lastActivity = Instant.now();
        }

        boolean isExpired() {
            return lastActivity.isBefore(Instant.now().minus(SESSION_TIMEOUT_MINUTES, ChronoUnit.MINUTES));
        }

        void updateActivity() {
            this.lastActivity = Instant.now();
        }
    }

    private static class AttemptInfo {
        int attempts;
        Instant lastAttempt;
        Instant lockedUntil;

        synchronized boolean isLocked(Instant now) {
            if (lockedUntil == null) {
                return false;
            }
            if (now.isAfter(lockedUntil)) {
                lockedUntil = null;
                attempts = 0;
                return false;
            }
            return true;
        }

        synchronized void recordFailure(Instant now) {
            lastAttempt = now;
            if (lockedUntil != null && now.isBefore(lockedUntil)) {
                return;
            }
            attempts += 1;
            if (attempts >= MAX_LOGIN_ATTEMPTS) {
                lockedUntil = now.plus(LOCKOUT_DURATION_MINUTES, ChronoUnit.MINUTES);
            }
        }

        synchronized boolean isStale(Instant now) {
            if (lastAttempt == null) {
                return true;
            }
            Instant cutoff = now.minus(LOCKOUT_DURATION_MINUTES + 5, ChronoUnit.MINUTES);
            return lastAttempt.isBefore(cutoff);
        }
    }

    public enum UserRole {
        ADMIN("admin", 100),
        OPERATOR("operator", 50),
        VIEWER("viewer", 10);

        private final String name;
        private final int level;

        UserRole(String name, int level) {
            this.name = name;
            this.level = level;
        }

        public String getName() { return name; }
        public int getLevel() { return level; }

        public boolean hasPermission(UserRole required) {
            return this.level >= required.level;
        }
    }

    private AuthenticationManager() {
        this.config = ProductionConfig.getInstance();
        this.auditLogger = AuditLogger.getInstance();

        // Schedule cleanup of expired sessions
        startSessionCleanupTask();
    }

    public static synchronized AuthenticationManager getInstance() {
        if (instance == null) {
            instance = new AuthenticationManager();
        }
        return instance;
    }

    /**
     * Authenticate user with username and password
     */
    public AuthenticationResult authenticate(String username, String password, String clientInfo) {
        String clientKey = extractClientKey(clientInfo);

        // Check if client is locked out
        if (isClientLockedOut(clientKey)) {
            auditLogger.logAuthenticationAttempt(username, clientInfo, false, "Client locked out");
            return AuthenticationResult.failure("Account temporarily locked due to too many failed attempts");
        }

        // Validate credentials
        UserRole role = validateCredentials(username, password);
        if (role == null) {
            recordFailedAttempt(clientKey);
            auditLogger.logAuthenticationAttempt(username, clientInfo, false, "Invalid credentials");
            return AuthenticationResult.failure("Invalid username or password");
        }

        // Create session
        String sessionId = generateSessionId();
        SessionInfo session = new SessionInfo(sessionId, clientInfo, role);
        activeSessions.put(sessionId, session);

        // Clear failed attempts
        loginAttempts.remove(clientKey);

        auditLogger.logAuthenticationAttempt(username, clientInfo, true, "Authentication successful");
        auditLogger.logSessionStart(sessionId, clientInfo, role.getName());

        return AuthenticationResult.success(sessionId, role);
    }

    /**
     * Create session without credential verification (for anonymous/guest access)
     */
    public AuthenticationResult createSession(UserRole role, String clientInfo) {
        if (role == UserRole.VIEWER && !config.isAnonymousViewerEnabled()) {
            return AuthenticationResult.failure("Anonymous viewer sessions disabled");
        }

        String sessionId = generateSessionId();
        SessionInfo session = new SessionInfo(sessionId, clientInfo, role);
        activeSessions.put(sessionId, session);
        auditLogger.logSessionStart(sessionId, clientInfo, role.getName());
        return AuthenticationResult.success(sessionId, role);
    }

    /**
     * Validate session and check if it's still active
     */
    public SessionValidationResult validateSession(String sessionId) {
        SessionInfo session = activeSessions.get(sessionId);
        if (session == null) {
            return SessionValidationResult.invalid("Session not found");
        }

        if (session.isExpired()) {
            activeSessions.remove(sessionId);
            auditLogger.logSessionEnd(sessionId, session.clientInfo, "Session expired");
            return SessionValidationResult.invalid("Session expired");
        }

        session.updateActivity();
        return SessionValidationResult.valid(session.role);
    }

    /**
     * Check if user has permission to execute a command
     */
    public boolean hasPermission(String sessionId, String command) {
        SessionInfo session = activeSessions.get(sessionId);
        if (session == null) {
            return false;
        }

        UserRole requiredRole = getRequiredRole(command);
        boolean hasPermission = session.role.hasPermission(requiredRole);

        if (!hasPermission) {
            auditLogger.logAuthorizationFailure(sessionId, session.clientInfo, command,
                "Insufficient permissions: " + session.role.getName() + " < " + requiredRole.getName());
        }

        return hasPermission;
    }

    /**
     * Logout user session
     */
    public void logout(String sessionId) {
        SessionInfo session = activeSessions.remove(sessionId);
        if (session != null) {
            auditLogger.logSessionEnd(sessionId, session.clientInfo, "User logout");
        }
    }

    /**
     * Get user role for command authorization
     */
    private UserRole getRequiredRole(String command) {
        switch (command.toLowerCase()) {
            case "redefine":
            case "retransform":
            case "mc":
            case "heapdump":
                return UserRole.ADMIN;

            case "watch":
            case "trace":
            case "monitor":
            case "profiler":
                return UserRole.OPERATOR;

            default:
                return UserRole.VIEWER;
        }
    }

    /**
     * Validate username and password
     */
    private UserRole validateCredentials(String username, String password) {
        // In production, this would connect to LDAP, database, or other auth system
        // For demo purposes, we have hardcoded credentials

        if ("admin".equals(username) && validatePassword(password, DEFAULT_ADMIN_PASSWORD)) {
            return UserRole.ADMIN;
        }

        if ("operator".equals(username) && validatePassword(password, "sleuth_op_2023!")) {
            return UserRole.OPERATOR;
        }

        if ("viewer".equals(username) && validatePassword(password, "sleuth_view_2023!")) {
            return UserRole.VIEWER;
        }

        return null;
    }

    /**
     * Validate password with secure comparison
     */
    private boolean validatePassword(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }

        // Use MessageDigest.isEqual to prevent timing attacks
        byte[] providedBytes = provided.getBytes();
        byte[] expectedBytes = expected.getBytes();

        return MessageDigest.isEqual(providedBytes, expectedBytes);
    }

    /**
     * Generate secure session ID
     */
    private String generateSessionId() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Record failed login attempt
     */
    private void recordFailedAttempt(String clientKey) {
        Instant now = Instant.now();
        AttemptInfo info = loginAttempts.computeIfAbsent(clientKey, k -> new AttemptInfo());
        info.recordFailure(now);
    }

    /**
     * Check if client is locked out
     */
    private boolean isClientLockedOut(String clientKey) {
        AttemptInfo info = loginAttempts.get(clientKey);
        if (info == null) {
            return false;
        }
        return info.isLocked(Instant.now());
    }

    /**
     * Extract client key for rate limiting
     */
    private String extractClientKey(String clientInfo) {
        if (clientInfo == null) {
            return "unknown";
        }

        // Expected formats:
        // - "/127.0.0.1:54321"
        // - "/[::1]:54321"
        // - "127.0.0.1:54321"
        // - "[::1]:54321"
        String v = clientInfo.trim();
        if (v.startsWith("/")) {
            v = v.substring(1);
        }
        if (v.isEmpty()) {
            return "unknown";
        }

        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end > 1) {
                return v.substring(1, end);
            }
        }

        int colon = v.lastIndexOf(':');
        if (colon > 0) {
            return v.substring(0, colon);
        }
        return v;
    }

    /**
     * Start background task to cleanup expired sessions
     */
    private void startSessionCleanupTask() {
        Thread cleanupThread = new Thread(() -> {
            while (true) {
                try {
                    cleanupExpiredSessions();
                    Thread.sleep(TimeUnit.MINUTES.toMillis(5)); // Cleanup every 5 minutes
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("Error during session cleanup: " + e.getMessage());
                }
            }
        }, "sleuth-session-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Cleanup expired sessions and login attempts
     */
    private void cleanupExpiredSessions() {
        Instant now = Instant.now();

        // Remove expired sessions
        activeSessions.entrySet().removeIf(entry -> {
            SessionInfo session = entry.getValue();
            if (session.isExpired()) {
                auditLogger.logSessionEnd(session.sessionId, session.clientInfo, "Session expired");
                return true;
            }
            return false;
        });

        // Cleanup old/stale attempt records (do not clear everything).
        loginAttempts.entrySet().removeIf(entry -> {
            AttemptInfo info = entry.getValue();
            return info == null || info.isStale(now);
        });
    }

    /**
     * Get session information
     */
    public SessionInfo getSessionInfo(String sessionId) {
        return activeSessions.get(sessionId);
    }

    /**
     * Get active session count
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * Force logout all sessions (emergency)
     */
    public void forceLogoutAll() {
        activeSessions.forEach((sessionId, session) -> {
            auditLogger.logSessionEnd(sessionId, session.clientInfo, "Force logout - administrator action");
        });
        activeSessions.clear();
        auditLogger.logSystemEvent("FORCE_LOGOUT_ALL", "All sessions terminated by administrator");
    }

    // Result classes
    public static class AuthenticationResult {
        private final boolean success;
        private final String sessionId;
        private final UserRole role;
        private final String message;

        private AuthenticationResult(boolean success, String sessionId, UserRole role, String message) {
            this.success = success;
            this.sessionId = sessionId;
            this.role = role;
            this.message = message;
        }

        public static AuthenticationResult success(String sessionId, UserRole role) {
            return new AuthenticationResult(true, sessionId, role, null);
        }

        public static AuthenticationResult failure(String message) {
            return new AuthenticationResult(false, null, null, message);
        }

        public boolean isSuccess() { return success; }
        public String getSessionId() { return sessionId; }
        public UserRole getRole() { return role; }
        public String getMessage() { return message; }
    }

    public static class SessionValidationResult {
        private final boolean valid;
        private final UserRole role;
        private final String message;

        private SessionValidationResult(boolean valid, UserRole role, String message) {
            this.valid = valid;
            this.role = role;
            this.message = message;
        }

        public static SessionValidationResult valid(UserRole role) {
            return new SessionValidationResult(true, role, null);
        }

        public static SessionValidationResult invalid(String message) {
            return new SessionValidationResult(false, null, message);
        }

        public boolean isValid() { return valid; }
        public UserRole getRole() { return role; }
        public String getMessage() { return message; }
    }
}
