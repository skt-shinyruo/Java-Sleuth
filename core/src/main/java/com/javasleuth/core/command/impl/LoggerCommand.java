package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class LoggerCommand implements Command {
    @Override
    public String execute(String[] args) {
        if (args == null || args.length == 1) {
            return list(null, 50);
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list":
                return list(args.length >= 3 ? args[2] : null, args.length >= 4 ? parseInt(args[3], 50) : 50);
            case "set":
                return set(args);
            default:
                return "Unknown logger action: " + action + "\n" + getHelp();
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

    private String set(String[] args) {
        if (args.length < 4) {
            return "Usage: logger set <name> <LEVEL>";
        }
        String name = args[2];
        String levelRaw = args[3];
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

    private int parseInt(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String getHelp() {
        return "Logger command usage:\n" +
            "  logger list [pattern] [limit]\n" +
            "  logger set <name> <LEVEL>\n";
    }

    @Override
    public String getDescription() {
        return "Manage java.util.logging loggers (list/set)";
    }
}
