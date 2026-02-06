package com.javasleuth.command.pipeline;

/**
 * 缓存策略：用于判断某条命令是否“语义上安全可缓存”。
 *
 * <p>注意：是否开启缓存还受 CommandMeta.cacheable 影响。</p>
 */
public final class CommandCachePolicy {
    private CommandCachePolicy() {
    }

    public static boolean isSafeToCache(String commandName, String[] args) {
        if (commandName == null) {
            return false;
        }
        // Avoid caching context-sensitive commands.
        if ("session".equals(commandName)) {
            return false;
        }
        // dashboard realtime explicitly requests no caching (still allow cached default dashboard).
        if ("dashboard".equals(commandName) && args != null) {
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a == null) {
                    continue;
                }
                if ("realtime".equalsIgnoreCase(a.trim())) {
                    return false;
                }
            }
        }
        // Avoid caching state-changing subcommands.
        if ("sysprop".equals(commandName) && args != null && args.length > 1) {
            if ("set".equalsIgnoreCase(args[1])) {
                return false;
            }
        }
        return true;
    }
}

