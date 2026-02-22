package com.javasleuth.foundation.config.model;

/**
 * 日志相关配置：控制台输出、审计落盘与性能日志开关等。
 */
public final class LoggingConfig {
    private final String level;
    private final boolean consoleEnabled;
    private final boolean auditEnabled;
    private final boolean auditConsoleEnabled;
    private final String auditFilePath;
    private final String securityFilePath;
    private final boolean performanceEnabled;

    public LoggingConfig(
        String level,
        boolean consoleEnabled,
        boolean auditEnabled,
        boolean auditConsoleEnabled,
        String auditFilePath,
        String securityFilePath,
        boolean performanceEnabled
    ) {
        this.level = level;
        this.consoleEnabled = consoleEnabled;
        this.auditEnabled = auditEnabled;
        this.auditConsoleEnabled = auditConsoleEnabled;
        this.auditFilePath = auditFilePath;
        this.securityFilePath = securityFilePath;
        this.performanceEnabled = performanceEnabled;
    }

    public String getLevel() {
        return level;
    }

    public boolean isConsoleEnabled() {
        return consoleEnabled;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public boolean isAuditConsoleEnabled() {
        return auditConsoleEnabled;
    }

    public String getAuditFilePath() {
        return auditFilePath;
    }

    public String getSecurityFilePath() {
        return securityFilePath;
    }

    public boolean isPerformanceEnabled() {
        return performanceEnabled;
    }
}

