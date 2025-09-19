package com.javasleuth.monitoring;

/**
 * JMX MBean interface for MetricsCollector monitoring and management
 */
public interface MetricsCollectorMBean {
    /**
     * Get server uptime in milliseconds
     */
    long getUptime();

    /**
     * Get total number of commands executed
     */
    long getTotalCommands();

    /**
     * Get total number of sessions
     */
    long getTotalSessions();

    /**
     * Get number of active sessions
     */
    int getActiveSessions();

    /**
     * Get total number of errors
     */
    long getTotalErrors();

    /**
     * Get error rate as percentage
     */
    double getErrorRate();

    /**
     * Get number of active connections
     */
    int getActiveConnections();

    /**
     * Get heap memory usage percentage
     */
    double getHeapUsagePercent();

    /**
     * Get current thread count
     */
    int getThreadCount();

    /**
     * Check if system is healthy
     */
    boolean isHealthy();

    /**
     * Reset all metrics
     */
    void resetMetrics();

    /**
     * Get most executed command
     */
    String getMostExecutedCommand();

    /**
     * Get slowest command
     */
    String getSlowestCommand();
}