package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.foundation.security.AuditLogger;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class AuditCommand implements Command {
    private final AuditLogger auditLogger;

    public AuditCommand(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length == 1) {
            return auditLogger.getAuditStatus();
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "status":
                return auditLogger.getAuditStatus();

            case "tail":
                int lines = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                return tailAuditLog("sleuth-audit.log", lines);

            case "security":
                int securityLines = args.length > 2 ? Integer.parseInt(args[2]) : 20;
                return tailAuditLog("sleuth-security.log", securityLines);

            case "search":
                if (args.length < 3) {
                    return "Usage: audit search <pattern> [lines]";
                }
                String pattern = args[2];
                int searchLines = args.length > 3 ? Integer.parseInt(args[3]) : 50;
                return searchAuditLog("sleuth-audit.log", pattern, searchLines);

            case "clear":
                return clearAuditLogs();

            case "summary":
                return getAuditSummary();

            default:
                return "Unknown audit action: " + action + "\n" +
                       "Available actions: status, tail [lines], security [lines], search <pattern> [lines], clear, summary";
        }
    }

    private String tailAuditLog(String filename, int lines) {
        StringBuilder result = new StringBuilder();
        result.append("=== LAST ").append(lines).append(" AUDIT LOG ENTRIES ===\n");

        File logFile = new File(filename);
        if (!logFile.exists()) {
            return "Audit log file not found: " + filename;
        }

        try {
            List<String> lastLines = getLastNLines(logFile, lines);
            for (String line : lastLines) {
                result.append(line).append("\n");
            }
        } catch (IOException e) {
            return "Error reading audit log: " + e.getMessage();
        }

        return result.toString();
    }

    private String searchAuditLog(String filename, String pattern, int maxLines) {
        StringBuilder result = new StringBuilder();
        result.append("=== AUDIT LOG SEARCH RESULTS (").append(pattern).append(") ===\n");

        File logFile = new File(filename);
        if (!logFile.exists()) {
            return "Audit log file not found: " + filename;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < maxLines) {
                if (line.toLowerCase().contains(pattern.toLowerCase())) {
                    result.append(line).append("\n");
                    count++;
                }
            }

            if (count == 0) {
                result.append("No entries found matching pattern: ").append(pattern).append("\n");
            } else if (count >= maxLines) {
                result.append("... (showing first ").append(maxLines).append(" matches)\n");
            }
        } catch (IOException e) {
            return "Error searching audit log: " + e.getMessage();
        }

        return result.toString();
    }

    private String clearAuditLogs() {
        StringBuilder result = new StringBuilder();
        result.append("=== AUDIT LOG CLEANUP ===\n");

        // Note: In production, you might want more sophisticated log rotation
        File auditLog = new File("sleuth-audit.log");
        File securityLog = new File("sleuth-security.log");

        try {
            if (auditLog.exists()) {
                PrintWriter writer = new PrintWriter(auditLog);
                writer.close();
                result.append("Audit log cleared: sleuth-audit.log\n");
            }

            if (securityLog.exists()) {
                PrintWriter writer = new PrintWriter(securityLog);
                writer.close();
                result.append("Security log cleared: sleuth-security.log\n");
            }

            result.append("Log files have been cleared successfully\n");
        } catch (IOException e) {
            result.append("Error clearing logs: ").append(e.getMessage()).append("\n");
        }

        return result.toString();
    }

    private String getAuditSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== AUDIT SUMMARY ===\n");

        // Get basic status
        summary.append(auditLogger.getAuditStatus());

        // File statistics
        File auditLog = new File("sleuth-audit.log");
        File securityLog = new File("sleuth-security.log");

        summary.append("\n-- Log File Statistics --\n");
        if (auditLog.exists()) {
            summary.append("Audit Log Size: ").append(formatFileSize(auditLog.length())).append("\n");
            summary.append("Audit Log Lines: ").append(countLines(auditLog)).append("\n");
        } else {
            summary.append("Audit Log: Not found\n");
        }

        if (securityLog.exists()) {
            summary.append("Security Log Size: ").append(formatFileSize(securityLog.length())).append("\n");
            summary.append("Security Log Lines: ").append(countLines(securityLog)).append("\n");
        } else {
            summary.append("Security Log: Not found\n");
        }

        // Recent activity summary
        try {
            summary.append("\n-- Recent Activity Summary --\n");
            List<String> recentLines = getLastNLines(auditLog, 10);
            int connectionCount = 0;
            int commandCount = 0;
            int errorCount = 0;

            for (String line : recentLines) {
                if (line.contains("CONNECTION")) connectionCount++;
                if (line.contains("COMMAND")) commandCount++;
                if (line.contains("ERROR")) errorCount++;
            }

            summary.append("Recent Connections: ").append(connectionCount).append("\n");
            summary.append("Recent Commands: ").append(commandCount).append("\n");
            summary.append("Recent Errors: ").append(errorCount).append("\n");

        } catch (IOException e) {
            summary.append("Could not analyze recent activity: ").append(e.getMessage()).append("\n");
        }

        return summary.toString();
    }

    private List<String> getLastNLines(File file, int n) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > n) {
                    lines.remove(0);
                }
            }
        }
        return lines;
    }

    private int countLines(File file) {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.readLine() != null) {
                count++;
            }
        } catch (IOException e) {
            return -1;
        }
        return count;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    @Override
    public String getDescription() {
        return "View and manage audit logs (usage: audit [status|tail|security|search|clear|summary])";
    }
}
