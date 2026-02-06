package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.monitoring.MetricsCollector;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class MetricsCommand implements Command {
    private final MetricsCollector metricsCollector;
    private final ObjectMapper objectMapper;

    public MetricsCommand(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1) {
            String format = args[1].toLowerCase();
            switch (format) {
                case "json":
                    return getJsonMetrics();
                case "summary":
                    return getSummaryMetrics();
                case "detailed":
                default:
                    return getDetailedMetrics();
            }
        }

        return getDetailedMetrics();
    }

    private String getDetailedMetrics() {
        return metricsCollector.getDetailedMetrics();
    }

    private String getSummaryMetrics() {
        Map<String, Object> metrics = metricsCollector.getMetricsMap();
        StringBuilder summary = new StringBuilder();

        summary.append("=== METRICS SUMMARY ===\n");
        summary.append("Uptime: ").append(formatDuration((Long) metrics.get("uptime"))).append("\n");
        summary.append("Total Commands: ").append(metrics.get("totalCommands")).append("\n");
        summary.append("Active Sessions: ").append(metrics.get("activeSessions")).append("\n");
        summary.append("Error Rate: ").append(String.format("%.2f%%", metrics.get("errorRate"))).append("\n");
        summary.append("Heap Usage: ").append(String.format("%.1f%%", metrics.get("heapUsagePercent"))).append("\n");
        summary.append("Thread Count: ").append(metrics.get("threadCount")).append("\n");

        return summary.toString();
    }

    private String getJsonMetrics() throws Exception {
        Map<String, Object> metrics = metricsCollector.getMetricsMap();
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics);
    }

    @Override
    public String getDescription() {
        return "Display detailed system and application metrics (usage: metrics [detailed|summary|json])";
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60000) {
            return String.format("%.1fs", durationMs / 1000.0);
        } else if (durationMs < 3600000) {
            long minutes = durationMs / 60000;
            long seconds = (durationMs % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        } else {
            long hours = durationMs / 3600000;
            long minutes = (durationMs % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }
}