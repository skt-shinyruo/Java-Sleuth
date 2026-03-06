package com.javasleuth.launcher.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Launcher 命令行参数解析与规范化。
 *
 * <p>该类仅做解析，不执行 Attach/网络/终端交互，便于单测覆盖与演进。</p>
 */
public final class LauncherArgs {
    private final boolean help;
    private final boolean insecure;
    private final String insecureConfirm;
    private final String pid;
    private final String command;
    private final String scriptPath;
    private final boolean failFast;
    private final List<String> unknownArgs;

    private LauncherArgs(
        boolean help,
        boolean insecure,
        String insecureConfirm,
        String pid,
        String command,
        String scriptPath,
        boolean failFast,
        List<String> unknownArgs
    ) {
        this.help = help;
        this.insecure = insecure;
        this.insecureConfirm = insecureConfirm;
        this.pid = pid;
        this.command = command;
        this.scriptPath = scriptPath;
        this.failFast = failFast;
        this.unknownArgs = unknownArgs != null ? new ArrayList<>(unknownArgs) : new ArrayList<>();
    }

    public static LauncherArgs parse(String[] args) {
        boolean help = false;
        boolean insecure = false;
        String insecureConfirm = null;
        String pid = null;
        String command = null;
        String script = null;
        boolean failFast = false;
        List<String> unknown = new ArrayList<>();

        if (args == null) {
            return new LauncherArgs(false, false, null, null, null, null, false, Collections.emptyList());
        }

        for (int i = 0; i < args.length; i++) {
            String raw = args[i];
            if (raw == null) {
                continue;
            }
            String a = raw.trim();
            if (a.isEmpty()) {
                continue;
            }

            if ("--help".equalsIgnoreCase(a) || "-h".equalsIgnoreCase(a) || "-help".equalsIgnoreCase(a)) {
                help = true;
                continue;
            }

            if ("--insecure".equalsIgnoreCase(a) || "-insecure".equalsIgnoreCase(a)) {
                insecure = true;
                continue;
            }

            if ("--insecure-confirm".equalsIgnoreCase(a)) {
                if (i + 1 < args.length) {
                    insecureConfirm = safeValue(args[++i]);
                }
                continue;
            }

            if ("--pid".equalsIgnoreCase(a)) {
                if (i + 1 < args.length) {
                    pid = safeValue(args[++i]);
                }
                continue;
            }

            if ("--cmd".equalsIgnoreCase(a) || "--command".equalsIgnoreCase(a)) {
                if (i + 1 < args.length) {
                    command = safeValue(args[++i]);
                }
                continue;
            }

            if ("--script".equalsIgnoreCase(a)) {
                if (i + 1 < args.length) {
                    script = safeValue(args[++i]);
                }
                continue;
            }

            if ("--fail-fast".equalsIgnoreCase(a)) {
                failFast = true;
                continue;
            }

            if ("--continue-on-error".equalsIgnoreCase(a)) {
                failFast = false;
                continue;
            }

            unknown.add(a);
        }

        return new LauncherArgs(help, insecure, insecureConfirm, pid, command, script, failFast, unknown);
    }

    private static String safeValue(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    public boolean isHelp() {
        return help;
    }

    public boolean isInsecure() {
        return insecure;
    }

    public String getInsecureConfirm() {
        return insecureConfirm;
    }

    public String getPid() {
        return pid;
    }

    public String getCommand() {
        return command;
    }

    public String getScriptPath() {
        return scriptPath;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public List<String> getUnknownArgs() {
        return Collections.unmodifiableList(unknownArgs);
    }

    public LaunchMode getLaunchMode() {
        if (command != null || scriptPath != null) {
            return LaunchMode.HEADLESS;
        }
        return LaunchMode.INTERACTIVE;
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        LaunchMode mode = getLaunchMode();
        if (mode == LaunchMode.HEADLESS) {
            if (pid == null || pid.trim().isEmpty()) {
                errors.add("headless 模式需要指定 --pid <pid>");
            }
            if ((command == null || command.trim().isEmpty()) && (scriptPath == null || scriptPath.trim().isEmpty())) {
                errors.add("headless 模式需要指定 --cmd <command> 或 --script <file>");
            }
        }

        return errors;
    }

    public String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Java-Sleuth Launcher Usage:\n");
        sb.append("  Interactive:\n");
        sb.append("    java -jar java-sleuth-launcher-*-jar-with-dependencies.jar\n");
        sb.append("    java -jar ... --pid <pid>    # 直接 attach 指定 PID 后进入交互\n");
        sb.append("\n");
        sb.append("  Headless (automation):\n");
        sb.append("    java -jar ... --pid <pid> --cmd \"version\"\n");
        sb.append("    java -jar ... --pid <pid> --script /path/to/commands.txt [--fail-fast]\n");
        sb.append("\n");
        sb.append("  Security:\n");
        sb.append("    --insecure                # DEPRECATED (no-op): HMAC mode removed; server is loopback-only\n");
        sb.append("    --insecure-confirm \"I UNDERSTAND\"  # DEPRECATED (no-op)\n");
        return sb.toString();
    }
}
