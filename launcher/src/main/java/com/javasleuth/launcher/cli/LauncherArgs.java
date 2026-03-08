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
    private final String pid;
    private final String command;
    private final String scriptPath;
    private final boolean failFast;
    private final boolean restart;
    private final String authUser;
    private final String authPass;
    private final List<String> unknownArgs;

    private LauncherArgs(
        boolean help,
        String pid,
        String command,
        String scriptPath,
        boolean failFast,
        boolean restart,
        String authUser,
        String authPass,
        List<String> unknownArgs
    ) {
        this.help = help;
        this.pid = pid;
        this.command = command;
        this.scriptPath = scriptPath;
        this.failFast = failFast;
        this.restart = restart;
        this.authUser = authUser;
        this.authPass = authPass;
        this.unknownArgs = unknownArgs != null ? new ArrayList<>(unknownArgs) : new ArrayList<>();
    }

    public static LauncherArgs parse(String[] args) {
        boolean help = false;
        String pid = null;
        String command = null;
        String script = null;
        boolean failFast = false;
        boolean restart = false;
        String authUser = null;
        String authPass = null;
        List<String> unknown = new ArrayList<>();

        if (args == null) {
            return new LauncherArgs(false, null, null, null, false, false, null, null, Collections.emptyList());
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

            if ("--restart".equalsIgnoreCase(a)) {
                restart = true;
                continue;
            }

            if ("--auth-user".equalsIgnoreCase(a)) {
                if (i + 1 < args.length) {
                    authUser = safeValue(args[++i]);
                }
                continue;
            }

            if ("--auth-pass".equalsIgnoreCase(a) || "--auth-password".equalsIgnoreCase(a)) {
                if (i + 1 < args.length) {
                    authPass = safeValue(args[++i]);
                }
                continue;
            }

            unknown.add(a);
        }

        return new LauncherArgs(help, pid, command, script, failFast, restart, authUser, authPass, unknown);
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

    public boolean isRestart() {
        return restart;
    }

    public String getAuthUser() {
        return authUser;
    }

    public String getAuthPass() {
        return authPass;
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

        if (unknownArgs != null && !unknownArgs.isEmpty()) {
            errors.add("未知参数: " + String.join(", ", unknownArgs));
        }

        if ((authUser != null && authPass == null) || (authUser == null && authPass != null)) {
            errors.add("--auth-user 和 --auth-pass 必须同时指定");
        }

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
        sb.append("  Options:\n");
        sb.append("    --help | -h\n");
        sb.append("    --pid <pid>\n");
        sb.append("    --cmd <command>\n");
        sb.append("    --script <file>\n");
        sb.append("    --restart\n");
        sb.append("    --auth-user <user>\n");
        sb.append("    --auth-pass <pass>\n");
        sb.append("    --fail-fast\n");
        sb.append("    --continue-on-error\n");
        sb.append("\n");
        sb.append("  Interactive:\n");
        sb.append("    java -jar java-sleuth-launcher-*-jar-with-dependencies.jar\n");
        sb.append("    java -jar ... --pid <pid>    # 直接 attach 指定 PID 后进入交互\n");
        sb.append("    java -jar ... --pid <pid> --restart    # stop + re-attach（类似 arthas: stop 后重新 attach）\n");
        sb.append("\n");
        sb.append("  Headless (automation):\n");
        sb.append("    java -jar ... --pid <pid> --cmd \"version\"\n");
        sb.append("    java -jar ... --pid <pid> --restart --cmd \"version\"\n");
        sb.append("    java -jar ... --pid <pid> --script /path/to/commands.txt [--fail-fast]\n");
        return sb.toString();
    }
}
