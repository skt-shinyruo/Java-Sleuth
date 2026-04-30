package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.SecurityValidator;
import com.javasleuth.foundation.util.WildcardMatcher;
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
public class VmOptionCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("vmoption")
        .description("Manage HotSpot diagnostic VM options")
        .usage("vmoption [pattern] | vmoption [list|get|set]")
        .meta(CommandMeta.operator(true, false).withSubcommandRole("set", UserRole.ADMIN))
        .argument(ArgumentSpec.optional("pattern"))
        .argument(ArgumentSpec.optional("limit"))
        .unknownSubcommandAsArgument(true)
        .subcommand(SubcommandSpec.of(
            "list",
            "List diagnostic VM options",
            CommandSpec.builder("list")
                .description("List diagnostic VM options")
                .usage("vmoption list [pattern] [limit] [--limit <int>]")
                .argument(ArgumentSpec.optional("pattern"))
                .argument(ArgumentSpec.optional("limit"))
                .option(OptionSpec.integer("limit").alias("--limit").defaultValue(100).range(1, 100000).build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "get",
            "Show one VM option",
            CommandSpec.builder("get")
                .description("Show one VM option")
                .usage("vmoption get <name>")
                .argument(ArgumentSpec.required("name"))
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "set",
            "Set a writeable VM option",
            CommandSpec.builder("set")
                .description("Set a writeable VM option")
                .usage("vmoption set <name> <value>")
                .argument(ArgumentSpec.required("name"))
                .argument(ArgumentSpec.required("value"))
                .build()
        ))
        .example("vmoption list *GC* 20")
        .example("vmoption get PrintGCDetails")
        .example("vmoption set PrintGCDetails true")
        .build();

    private final Instrumentation instrumentation;
    private final HotSpotDiagnosticMXBean hotSpot;

    public VmOptionCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.hotSpot = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    }

    public static CommandSpec spec() {
        return SPEC;
    }

    @Override
    public CommandSpec getSpec() {
        return SPEC;
    }

    @Override
    public String execute(String[] args) {
        ParsedCommand parsed = CommandSpecSupport.parsed(SPEC, args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(SPEC);
        }

        String action = parsed.subcommandName();
        if (action == null) {
            return list(
                parsed.argument("pattern"),
                CommandSpecSupport.intOptionOrArgument(parsed, "limit", "limit", 100)
            );
        }
        switch (action) {
            case "list":
                return list(
                    parsed.argument("pattern"),
                    CommandSpecSupport.intOptionOrArgument(parsed, "limit", "limit", 100)
                );
            case "get":
                return get(parsed);
            case "set":
                return set(parsed);
            default:
                return CommandHelpRenderer.render(SPEC);
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

    private String get(ParsedCommand parsed) {
        if (hotSpot == null) {
            return "HotSpotDiagnosticMXBean not available.";
        }
        String name = parsed.argument("name");
        try {
            VMOption o = hotSpot.getVMOption(name);
            return o.getName() + " = " + SecurityValidator.maskSensitiveValue(o.getName(), o.getValue()) +
                " (writeable=" + o.isWriteable() + ", origin=" + o.getOrigin() + ")";
        } catch (Exception e) {
            return "Failed to get option: " + name + " (" + e.getMessage() + ")";
        }
    }

    private String set(ParsedCommand parsed) {
        if (hotSpot == null) {
            return "HotSpotDiagnosticMXBean not available.";
        }
        String name = parsed.argument("name");
        String value = parsed.argument("value");
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

    @Override
    public String getDescription() {
        return "Manage HotSpot diagnostic VM options (list/get/set)";
    }
}
