package com.javasleuth.launcher.shell;

import java.util.Locale;

/**
 * 默认 streaming 策略：对齐 Arthas 高频长输出命令集合。
 */
public final class DefaultStreamPolicy implements StreamPolicy {
    @Override
    public boolean isStreamingCommand(String command) {
        if (command == null) {
            return false;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+");
        String name = parts.length > 0 ? parts[0] : "";
        name = name.toLowerCase(Locale.ROOT);
        return "watch".equals(name)
            || "trace".equals(name)
            || "monitor".equals(name)
            || "tt".equals(name)
            || "stack".equals(name);
    }
}

