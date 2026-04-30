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
import com.javasleuth.foundation.util.WildcardMatcher;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class LoggerCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("logger")
        .description("Manage java.util.logging loggers")
        .usage("logger [list|set]")
        .meta(CommandMeta.operator(true, false))
        .subcommand(SubcommandSpec.of(
            "list",
            "List loggers",
            CommandSpec.builder("list")
                .description("List loggers")
                .usage("logger list [pattern] [limit] [--limit <int>]")
                .argument(ArgumentSpec.optional("pattern"))
                .argument(ArgumentSpec.optional("limit"))
                .option(OptionSpec.integer("limit").alias("--limit").defaultValue(50).range(1, 100000).build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "set",
            "Set a logger level",
            CommandSpec.builder("set")
                .description("Set a logger level")
                .usage("logger set <name> <LEVEL>")
                .argument(ArgumentSpec.required("name"))
                .argument(ArgumentSpec.required("level"))
                .build()
        ))
        .example("logger list com.example.* 100")
        .example("logger set com.example.Service INFO")
        .build();

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
            return list(null, 50);
        }

        switch (action) {
            case "list":
                return list(
                    parsed.argument("pattern"),
                    CommandSpecSupport.intOptionOrArgument(parsed, "limit", "limit", 50)
                );
            case "set":
                return set(parsed);
            default:
                return CommandHelpRenderer.render(SPEC);
        }
    }

    private String list(String pattern, int limit) {
        LogManager mgr = LogManager.getLogManager();
        Enumeration<String> names = mgr.getLoggerNames();
        List<String> rows = new ArrayList<>();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name == null) {
                continue;
            }
            if (pattern != null && !pattern.trim().isEmpty() && !WildcardMatcher.matches(name, pattern)) {
                continue;
            }
            Logger logger = mgr.getLogger(name);
            Level level = logger != null ? logger.getLevel() : null;
            Level effective = getEffectiveLevel(logger);
            rows.add(name + "\tlevel=" + (level == null ? "<null>" : level.getName()) +
                "\teffective=" + (effective == null ? "<null>" : effective.getName()));
            if (rows.size() >= limit) {
                break;
            }
        }
        if (rows.isEmpty()) {
            return "No loggers.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("NAME\tLEVEL\tEFFECTIVE\n");
        for (String r : rows) {
            sb.append(r).append("\n");
        }
        return sb.toString().trim();
    }

    private String set(ParsedCommand parsed) {
        String name = parsed.argument("name");
        String levelRaw = parsed.argument("level");
        Level level;
        try {
            level = Level.parse(levelRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "Invalid level: " + levelRaw;
        }

        Logger logger = Logger.getLogger(name);
        logger.setLevel(level);
        return "Logger updated: " + name + " level=" + level.getName();
    }

    private Level getEffectiveLevel(Logger logger) {
        Logger cur = logger;
        while (cur != null) {
            Level l = cur.getLevel();
            if (l != null) {
                return l;
            }
            cur = cur.getParent();
        }
        return null;
    }

    @Override
    public String getDescription() {
        return "Manage java.util.logging loggers (list/set)";
    }
}
