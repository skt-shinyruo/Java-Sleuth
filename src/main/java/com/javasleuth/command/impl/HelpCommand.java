package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.util.Map;

public class HelpCommand implements Command {
    private final Map<String, Command> commands;

    public HelpCommand(Map<String, Command> commands) {
        this.commands = commands;
    }

    @Override
    public String execute(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Java-Sleuth Help ===\n");
        sb.append("Available commands:\n\n");

        for (Map.Entry<String, Command> entry : commands.entrySet()) {
            sb.append(String.format("  %-12s - %s\n",
                entry.getKey(), entry.getValue().getDescription()));
        }

        sb.append("\nFor detailed help on a specific command, use: <command> --help\n");
        return sb.toString();
    }

    @Override
    public String getDescription() {
        return "Show help information for all commands";
    }
}