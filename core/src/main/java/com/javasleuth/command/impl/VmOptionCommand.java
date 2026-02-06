package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.security.SecurityValidator;
import com.javasleuth.util.WildcardMatcher;
import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * HotSpot VM options (simplified).
 *
 * <p>Arthas has a rich vmoption; here we focus on the core:
 * - list/get/set via HotSpotDiagnosticMXBean
 */
public class VmOptionCommand implements Command {
    private final Instrumentation instrumentation;
    private final HotSpotDiagnosticMXBean hotSpot;

    public VmOptionCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.hotSpot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    }

    @Override
    public String execute(String[] args) {
        if (args == null || args.length == 1) {
            return list(null, 100);
        }
        if ("--help".equals(args[1]) || "-h".equals(args[1])) {
            return getHelpText();
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "list":
                return list(args.length >= 3 ? args[2] : null, args.length >= 4 ? parseInt(args[3], 100) : 100);
            case "get":
                return get(args);
            case "set":
                return set(args);
            default:
                // Backward-compat: `vmoption <pattern>` behaves like list by pattern.
                return list(args[1], 100);
        }
    }

    private String list(String pattern, int limit) {
        if (hotSpot == null) {
            return "HotSpotDiagnosticMXBean not available (non-HotSpot JVM or restricted environment).";
        }
        List<VMOption> options = hotSpot.getDiagnosticOptions();
        StringBuilder sb = new StringBuilder();
        sb.append("=== VM Options (HotSpotDiagnosticMXBean) ===\n");
        if (pattern != null && !pattern.trim().isEmpty()) {
            sb.append("Pattern: ").append(pattern).append("\n");
        }
        int shown = 0;
        for (VMOption o : options) {
            if (o == null) {
                continue;
            }
            if (pattern != null && !pattern.trim().isEmpty() && !WildcardMatcher.matches(o.getName(), pattern)) {
                continue;
            }
            sb.append(o.getName())
                .append(" = ")
                .append(SecurityValidator.maskSensitiveValue(o.getName(), o.getValue()))
                .append(" (writeable=")
                .append(o.isWriteable())
                .append(", origin=")
                .append(o.getOrigin())
                .append(")\n");
            shown++;
            if (shown >= limit) {
                break;
            }
        }
        if (shown == 0) {
            sb.append("(no options matched)\n");
        }
        return sb.toString().trim();
    }

    private String get(String[] args) {
        if (hotSpot == null) {
            return "HotSpotDiagnosticMXBean not available.";
        }
        if (args.length < 3) {
            return "Usage: vmoption get <name>";
        }
        String name = args[2];
        try {
            VMOption o = hotSpot.getVMOption(name);
            return o.getName() + " = " + SecurityValidator.maskSensitiveValue(o.getName(), o.getValue()) +
                " (writeable=" + o.isWriteable() + ", origin=" + o.getOrigin() + ")";
        } catch (Exception e) {
            return "Failed to get option: " + name + " (" + e.getMessage() + ")";
        }
    }

    private String set(String[] args) {
        if (hotSpot == null) {
            return "HotSpotDiagnosticMXBean not available.";
        }
        if (args.length < 4) {
            return "Usage: vmoption set <name> <value>";
        }
        String name = args[2];
        String value = args[3];
        try {
            VMOption o = hotSpot.getVMOption(name);
            if (!o.isWriteable()) {
                return "Option is not writeable: " + name;
            }
            hotSpot.setVMOption(name, value);
            VMOption updated = hotSpot.getVMOption(name);
            return "Option updated: " + updated.getName() + " = " +
                SecurityValidator.maskSensitiveValue(updated.getName(), updated.getValue());
        } catch (Exception e) {
            return "Failed to set option: " + name + " (" + e.getMessage() + ")";
        }
    }

    private int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String getHelpText() {
        return "=== VM Options Command Help ===\n" +
            "Display and modify HotSpot diagnostic VM options (simplified)\n\n" +
            "Usage:\n" +
            "  vmoption                         List diagnostic options\n" +
            "  vmoption list [pattern] [limit]   List options by name pattern\n" +
            "  vmoption get <name>               Show one option\n" +
            "  vmoption set <name> <value>       Set writeable option (admin only)\n" +
            "  vmoption --help                   Show this help message\n\n" +
            "Examples:\n" +
            "  vmoption list *GC* 20\n" +
            "  vmoption get PrintGCDetails\n" +
            "  vmoption set PrintGCDetails true\n";
    }

    @Override
    public String getDescription() {
        return "Manage HotSpot diagnostic VM options (list/get/set)";
    }
}

