package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.core.enhancement.session.EnhancementSessionCloseSummary;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.core.enhancement.session.EnhancementSessionSnapshot;
import com.javasleuth.foundation.security.CommandMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnhanceCommand implements Command, SpecBackedCommand {
    private final EnhancementSessionRegistry registry;

    public EnhanceCommand(EnhancementSessionRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry");
        }
        this.registry = registry;
    }

    public static CommandSpec spec() {
        return CommandSpec.builder("enhance")
            .description("List and stop active enhancement sessions")
            .usage("enhance <sessions|stop> [arguments] [options]")
            .meta(CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.MEDIUM))
            .subcommand(SubcommandSpec.builder("sessions")
                .description("List active enhancement sessions")
                .option(OptionSpec.string("kind").alias("--kind").build())
                .build())
            .subcommand(SubcommandSpec.builder("stop")
                .description("Stop active enhancement sessions")
                .argument(ArgumentSpec.optional("session-id"))
                .option(OptionSpec.string("kind").alias("--kind").build())
                .option(OptionSpec.string("client").alias("--client").build())
                .build())
            .example("enhance sessions")
            .example("enhance sessions --kind watch")
            .example("enhance stop <session-id>")
            .example("enhance stop --client <client-id>")
            .example("enhance stop --kind trace")
            .build();
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    @Override
    public String execute(String[] args) {
        if (args == null || args.length < 2) {
            return CommandHelpRenderer.render(spec());
        }
        ParsedCommand parsed = parsedOrFallback(args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(spec());
        }
        String subcommand = parsed.subcommandName();
        if ("sessions".equals(subcommand)) {
            return listSessions(parsed);
        }
        if ("stop".equals(subcommand)) {
            return stopSessions(parsed);
        }
        return CommandHelpRenderer.render(spec());
    }

    @Override
    public String getDescription() {
        return "List and stop active enhancement sessions";
    }

    private String listSessions(ParsedCommand parsed) {
        String rawKind = parsed.stringOption("kind");
        EnhancementSessionKind kind = parseKind(rawKind);
        if (rawKind != null && kind == null) {
            return unknownKind(rawKind);
        }

        List<EnhancementSessionSnapshot> sessions = new ArrayList<EnhancementSessionSnapshot>();
        for (EnhancementSessionSnapshot snapshot : registry.list()) {
            if (kind == null || kind == snapshot.getKind()) {
                sessions.add(snapshot);
            }
        }
        if (sessions.isEmpty()) {
            return "No matching enhancement sessions.";
        }

        StringBuilder out = new StringBuilder();
        out.append("ID\tKIND\tCLIENT\tCOMMAND\tCLASS\tMETHOD\tDETAILS\n");
        for (EnhancementSessionSnapshot snapshot : sessions) {
            out.append(value(snapshot.getSessionId())).append('\t')
                .append(snapshot.getKind()).append('\t')
                .append(value(snapshot.getClientId())).append('\t')
                .append(value(snapshot.getCommandName())).append('\t')
                .append(classValue(snapshot)).append('\t')
                .append(value(snapshot.getMethodPattern())).append('\t')
                .append(value(snapshot.getDetails()))
                .append('\n');
        }
        return out.toString().trim();
    }

    private String stopSessions(ParsedCommand parsed) {
        String sessionId = trimToNull(parsed.argument("session-id"));
        String rawKind = parsed.stringOption("kind");
        String clientId = trimToNull(parsed.stringOption("client"));

        int selectors = 0;
        selectors += sessionId != null ? 1 : 0;
        selectors += rawKind != null ? 1 : 0;
        selectors += clientId != null ? 1 : 0;
        if (selectors == 0) {
            return "Usage: enhance stop <session-id> | enhance stop --kind <kind> | enhance stop --client <client-id>";
        }
        if (selectors > 1) {
            return "Specify only one enhancement session selector: session id, --kind, or --client.";
        }

        EnhancementSessionCloseSummary summary;
        if (sessionId != null) {
            summary = registry.closeOneSummary(sessionId, "enhance_stop");
        } else if (clientId != null) {
            summary = registry.closeByClientSummary(clientId, "enhance_stop");
        } else {
            EnhancementSessionKind kind = parseKind(rawKind);
            if (kind == null) {
                return unknownKind(rawKind);
            }
            summary = registry.closeByKind(kind, "enhance_stop");
        }
        return formatCloseSummary(summary);
    }

    private ParsedCommand parsedOrFallback(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
        return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
    }

    private String formatCloseSummary(EnhancementSessionCloseSummary summary) {
        if (summary == null || summary.getTotal() == 0 || (summary.getClosed() == 0 && summary.getFailed() == 0)) {
            return "No matching enhancement sessions.";
        }
        StringBuilder out = new StringBuilder();
        out.append("Enhancement sessions: total=").append(summary.getTotal())
            .append(", closed=").append(summary.getClosed())
            .append(", missing=").append(summary.getMissing())
            .append(", failed=").append(summary.getFailed());
        if (!summary.getFailureMessages().isEmpty()) {
            out.append("\nFailures:");
            for (Map.Entry<String, String> failure : summary.getFailureMessages().entrySet()) {
                out.append("\n  ").append(failure.getKey()).append(": ").append(failure.getValue());
            }
        }
        return out.toString();
    }

    private EnhancementSessionKind parseKind(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT).replace('-', '_');
        for (EnhancementSessionKind kind : EnhancementSessionKind.values()) {
            if (kind.name().equals(normalized)) {
                return kind;
            }
        }
        return null;
    }

    private String unknownKind(String rawKind) {
        return "Unknown enhancement session kind: " + rawKind + ". Allowed: watch, trace, monitor, stack, tt, vmtool, other";
    }

    private static String classValue(EnhancementSessionSnapshot snapshot) {
        if (snapshot.getClassPattern() != null) {
            return snapshot.getClassPattern();
        }
        if (!snapshot.getTargetClassNames().isEmpty()) {
            return join(snapshot.getTargetClassNames());
        }
        return "-";
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String value(String raw) {
        return raw == null || raw.trim().isEmpty() ? "-" : raw;
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
