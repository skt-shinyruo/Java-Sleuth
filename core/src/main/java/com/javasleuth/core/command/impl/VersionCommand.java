package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;

public class VersionCommand implements Command {
    @Override
    public String execute(String[] args) {
        Package p = VersionCommand.class.getPackage();
        String impl = p != null ? p.getImplementationVersion() : null;
        String title = p != null ? p.getImplementationTitle() : null;
        if (impl == null || impl.trim().isEmpty()) {
            impl = "unknown";
        }
        if (title == null || title.trim().isEmpty()) {
            title = "Java-Sleuth";
        }
        return title + " version: " + impl;
    }

    @Override
    public String getDescription() {
        return "Show Java-Sleuth version";
    }
}
