package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;

public class QuitCommand implements Command {
    @Override
    public String execute(String[] args) throws Exception {
        return "Goodbye!";
    }

    @Override
    public String getDescription() {
        return "Exit the Java-Sleuth session";
    }
}
