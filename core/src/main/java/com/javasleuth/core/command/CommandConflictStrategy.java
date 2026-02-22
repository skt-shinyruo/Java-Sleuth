package com.javasleuth.core.command;

/**
 * 命令注册冲突策略（显式类型化）。
 *
 * <p>用于控制当 builtin 与 plugin（或多个插件）提供同名命令时的取舍策略，避免在运行时依赖
 * 隐式字符串约定扩散到多处。</p>
 */
public enum CommandConflictStrategy {
    /**
     * 冲突即失败：保留已存在命令，不覆盖。
     */
    FAIL,

    /**
     * 优先插件：插件命令覆盖 builtin（或覆盖旧插件）。
     */
    PREFER_PLUGIN,

    /**
     * 优先 builtin：builtin 命令覆盖插件；默认策略。
     */
    PREFER_BUILTIN;

    public static CommandConflictStrategy fromConfig(String raw) {
        if (raw == null) {
            return PREFER_BUILTIN;
        }
        String v = raw.trim().toLowerCase();
        if (v.isEmpty()) {
            return PREFER_BUILTIN;
        }
        if ("fail".equals(v)) {
            return FAIL;
        }
        if ("prefer-plugin".equals(v) || "plugin".equals(v)) {
            return PREFER_PLUGIN;
        }
        if ("prefer-builtin".equals(v) || "builtin".equals(v)) {
            return PREFER_BUILTIN;
        }
        return PREFER_BUILTIN;
    }
}

