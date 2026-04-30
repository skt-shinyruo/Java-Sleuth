package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.AuditLogger;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class AuditCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("audit")
        .description("View and manage audit logs")
        .usage("audit [status|tail|security|search|clear|summary]")
        .meta(CommandMeta.admin(false, false).withAudit(false))
        .subcommand(SubcommandSpec.of(
            "status",
            "Show audit status",
            CommandSpec.builder("status")
                .description("Show audit status")
                .usage("audit status")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "tail",
            "Show recent audit log entries",
            CommandSpec.builder("tail")
                .description("Show recent audit log entries")
                .usage("audit tail [lines] [--lines <int>]")
                .argument(ArgumentSpec.optional("lines"))
                .option(OptionSpec.integer("lines").alias("--lines").defaultValue(20).range(1, 100000).build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "security",
            "Show recent security log entries",
            CommandSpec.builder("security")
                .description("Show recent security log entries")
                .usage("audit security [lines] [--lines <int>]")
                .argument(ArgumentSpec.optional("lines"))
                .option(OptionSpec.integer("lines").alias("--lines").defaultValue(20).range(1, 100000).build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "search",
            "Search audit log entries",
            CommandSpec.builder("search")
                .description("Search audit log entries")
                .usage("audit search <pattern> [lines] [--lines <int>]")
                .argument(ArgumentSpec.required("pattern"))
                .argument(ArgumentSpec.optional("lines"))
                .option(OptionSpec.integer("lines").alias("--lines").defaultValue(50).range(1, 100000).build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "clear",
            "Clear audit log files",
            CommandSpec.builder("clear")
                .description("Clear audit log files")
                .usage("audit clear")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "summary",
            "Show audit summary",
            CommandSpec.builder("summary")
                .description("Show audit summary")
                .usage("audit summary")
                .build()
        ))
        .example("audit tail 50")
        .example("audit search COMMAND --lines 100")
        .build();

    private final AuditLogger auditLogger;

    public AuditCommand(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public static CommandSpec spec() {
        return SPEC;
    }

    @Override
    public CommandSpec getSpec() {
        return SPEC;
    }

    @Override
    public String execute(String[] args) throws Exception {
        ParsedCommand parsed = CommandSpecSupport.parsed(SPEC, args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(SPEC);
        }

        String action = parsed.subcommandName();
        if (action == null) {
            return auditLogger.getAuditStatus();
        }

        switch (action) {
            case "status":
                return auditLogger.getAuditStatus();

            case "tail":
                int lines = lineCount(parsed, 20);
                return tailAuditLog("sleuth-audit.log", lines);

            case "security":
                int securityLines = lineCount(parsed, 20);
                return tailAuditLog("sleuth-security.log", securityLines);

            case "search":
                String pattern = parsed.argument("pattern");
                int searchLines = lineCount(parsed, 50);
                return searchAuditLog("sleuth-audit.log", pattern, searchLines);

            case "clear":
                return clearAuditLogs();

            case "summary":
                return getAuditSummary();

            default:
                return CommandHelpRenderer.render(SPEC);
        }
    }

    private int lineCount(ParsedCommand parsed, int defaultValue) {
        if (parsed.isOptionExplicit("lines")) {
            Integer lines = parsed.intOption("lines");
            return lines != null ? lines : defaultValue;
        }
        String raw = parsed.argument("lines");
        return raw == null ? defaultValue : Integer.parseInt(raw);
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
