package com.javasleuth.launcher;

/**
 * Launcher 入口（composition root）。
 *
 * <p>负责参数解析、组件装配与流程编排：JVM 发现/选择 → Attach 注入 → 交互/脚本协议会话。</p>
 */
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.javasleuth.bootstrap.util.JarLocator;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.launcher.attach.AgentArgsBuilder;
import com.javasleuth.launcher.attach.AgentAttacher;
import com.javasleuth.launcher.attach.ToolsAttachApi;
import com.javasleuth.launcher.cli.LaunchMode;
import com.javasleuth.launcher.cli.LauncherArgs;
import com.javasleuth.launcher.client.ConnectHostResolver;
import com.javasleuth.launcher.client.ConsoleProtocolOutput;
import com.javasleuth.launcher.client.ProtocolClient;
import com.javasleuth.launcher.client.ProtocolOutput;
import com.javasleuth.launcher.jvm.AttachJvmDiscovery;
import com.javasleuth.launcher.jvm.JlineJvmSelector;
import com.javasleuth.launcher.jvm.JvmDiscovery;
import com.javasleuth.launcher.jvm.JvmSelector;
import com.javasleuth.launcher.shell.DefaultStreamPolicy;
import com.javasleuth.launcher.shell.HeadlessRunner;
import com.javasleuth.launcher.shell.InteractiveShell;
import com.javasleuth.foundation.util.SleuthLogger;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SleuthLauncher {
    private static final int CONNECT_OVERALL_TIMEOUT_MS = 15000;
    private static final int RESTART_OVERALL_TIMEOUT_MS = 30000;
    private static final int STOP_CONNECT_TIMEOUT_MS = 800;
    private static final int STOP_HANDSHAKE_TIMEOUT_MS = 3000;

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            SleuthLauncher launcher = new SleuthLauncher();
            exitCode = launcher.start(args);
        } catch (Exception e) {
            System.err.println("Failed to start Java-Sleuth: " + e.getMessage());
            if (Boolean.getBoolean("sleuth.launcher.debug")) {
                SleuthLogger.error("Launcher failure stacktrace (sleuth.launcher.debug=true)", e);
            }
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    public int start(String[] args) throws Exception {
        LauncherArgs parsed = LauncherArgs.parse(args);
        if (parsed.isHelp()) {
            System.out.println(parsed.usage());
            return 0;
        }
        List<String> errors = parsed.validate();
        if (!errors.isEmpty()) {
            for (String err : errors) {
                System.err.println("Argument error: " + err);
            }
            System.err.println();
            System.err.println(parsed.usage());
            return 2;
        }

        System.out.println("Java-Sleuth - Lightweight Java Diagnostic Tool");
        System.out.println("Inspired by Arthas");
        System.out.println();

        File agentJar = JarLocator.locateAgentJar(SleuthLauncher.class);
        if (agentJar == null) {
            reportMissingAgentJar();
            return 1;
        }

        File containerJar = JarLocator.locateAgentContainerJar(SleuthLauncher.class);
        if (containerJar == null && JarLocator.isBootstrapAgentJar(agentJar)) {
            reportMissingContainerJar();
            return 1;
        }

        JvmDiscovery discovery = new AttachJvmDiscovery();
        JvmSelector selector = new JlineJvmSelector();
        VirtualMachineDescriptor selectedVm = selectTargetJvm(parsed, discovery, selector);
        if (selectedVm == null) {
            System.out.println("No JVM selected. Exiting...");
            return 0;
        }

        AgentAttacher attacher = new AgentAttacher(new ToolsAttachApi(), new AgentArgsBuilder());
        AgentClientConfig clientConfig = AgentClientConfig.fromLocalConfig();

        ProtocolOutput output = new ConsoleProtocolOutput(System.out, System.err);

        if (parsed.isRestart()) {
            int pid = safeParsePid(selectedVm.id());
            if (pid > 0) {
                long listenPid = TcpListenPidFinder.findTcpListenProcessBestEffort(clientConfig.port);
                if (listenPid > 0 && listenPid != pid) {
                    output.onStderrLine(
                        "Refusing to restart: target pid=" + pid + " but port " + clientConfig.port +
                            " is currently listened by pid=" + listenPid + ". " +
                            "Stop the other process/agent first, or change server.port."
                    );
                    return 1;
                }
            }

            StopAttempt stopAttempt = stopExistingAgentIfRunning(clientConfig, parsed, output);
            if (stopAttempt == StopAttempt.FAILED) {
                output.onStderrLine("Restart aborted: failed to stop existing agent.");
                return 1;
            }
            if (stopAttempt == StopAttempt.STOP_SENT) {
                waitForAgentDownBestEffort(clientConfig, 15000, output);
            }
        }

        try (ProtocolClient client = attachAndConnect(
            attacher,
            parsed,
            selectedVm.id(),
            selectedVm.displayName(),
            agentJar,
            containerJar,
            clientConfig,
            output
        )) {
            if (client == null) {
                return 1;
            }

            if (parsed.getLaunchMode() == LaunchMode.HEADLESS) {
                return runHeadless(parsed, client, output);
            }

            runInteractive(client, output);
            return 0;
        }
    }

    private static ProtocolClient attachAndConnect(
        AgentAttacher attacher,
        LauncherArgs parsed,
        String pid,
        String displayName,
        File agentJar,
        File containerJar,
        AgentClientConfig cfg,
        ProtocolOutput output
    ) {
        if (!parsed.isRestart()) {
            boolean attached = attachOnce(attacher, pid, displayName, agentJar, containerJar);
            if (!attached) {
                return null;
            }
            try {
                return connectWithRetry(cfg, CONNECT_OVERALL_TIMEOUT_MS);
            } catch (IOException e) {
                if (output != null) {
                    output.onStderrLine("Failed to connect to agent: " + e.getMessage());
                }
                return null;
            }
        }

        long start = System.currentTimeMillis();
        long deadline = start + RESTART_OVERALL_TIMEOUT_MS;
        int attempts = 0;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            boolean attached = attachOnce(attacher, pid, displayName, agentJar, containerJar);
            if (!attached) {
                return null;
            }

            int remaining = (int) Math.min(Integer.MAX_VALUE, Math.max(0, deadline - System.currentTimeMillis()));
            int connectBudget = Math.max(1000, Math.min(5000, remaining));
            try {
                return connectWithRetry(cfg, connectBudget);
            } catch (IOException ignored) {
                // Possibly still shutting down / lifecycle gate not released yet.
            }

            sleepBestEffort(Math.min(800, remaining));
        }

        if (output != null) {
            output.onStderrLine("Restart attach timed out after " + (System.currentTimeMillis() - start) + "ms (attempts=" + attempts + ")");
        }
        return null;
    }

    private static boolean attachOnce(AgentAttacher attacher, String pid, String displayName, File agentJar, File containerJar) {
        try {
            return attacher.attach(pid, displayName, agentJar, containerJar);
        } catch (Exception e) {
            System.err.println("Attach failed: " + e.getMessage());
            if (Boolean.getBoolean("sleuth.launcher.debug")) {
                SleuthLogger.error("Attach failure stacktrace (sleuth.launcher.debug=true)", e);
            }
            return false;
        }
    }

    private static ProtocolClient connectWithRetry(AgentClientConfig cfg, int overallTimeoutMs) throws IOException {
        return ProtocolClient.connectWithRetry(
            cfg.connectHost,
            cfg.port,
            "binary",
            cfg.streamingEnabled,
            cfg.maxPayloadBytes,
            cfg.maxLineBytes,
            overallTimeoutMs,
            5000,
            15000
        );
    }

    private static int runHeadless(LauncherArgs parsed, ProtocolClient client, ProtocolOutput output) {
        HeadlessRunner runner = new HeadlessRunner(new DefaultStreamPolicy(), output, parsed.isFailFast());
        if (parsed.getCommand() != null) {
            return runner.runSingleCommand(client, parsed.getCommand());
        }
        if (parsed.getScriptPath() != null) {
            return runner.runScript(client, parsed.getScriptPath());
        }
        if (output != null) {
            output.onStderrLine("Headless error: missing --cmd/--script");
        }
        return 2;
    }

    private static void runInteractive(ProtocolClient client, ProtocolOutput output) throws Exception {
        new InteractiveShell(new DefaultStreamPolicy(), output).run(client);
    }

    private enum StopAttempt {
        NOT_RUNNING,
        STOP_SENT,
        FAILED
    }

    private static StopAttempt stopExistingAgentIfRunning(AgentClientConfig cfg, LauncherArgs parsed, ProtocolOutput output) {
        try (ProtocolClient client = ProtocolClient.connect(
            cfg.connectHost,
            cfg.port,
            "binary",
            cfg.streamingEnabled,
            cfg.maxPayloadBytes,
            cfg.maxLineBytes,
            com.javasleuth.foundation.security.CommandSigner.noop(),
            STOP_CONNECT_TIMEOUT_MS,
            STOP_HANDSHAKE_TIMEOUT_MS
        )) {
            if (output != null) {
                output.onStdoutLine("Restart: connected to existing agent, issuing stop...");
            }
            boolean stopped = executeStopSequence(client, parsed, output);
            return stopped ? StopAttempt.STOP_SENT : StopAttempt.FAILED;
        } catch (IOException e) {
            if (output != null) {
                output.onStdoutLine("Restart: no existing agent reachable on " + cfg.connectHost + ":" + cfg.port + " (skipping stop)");
            }
            return StopAttempt.NOT_RUNNING;
        } catch (Exception e) {
            if (output != null) {
                output.onStderrLine("Restart: failed to stop existing agent: " + e.getMessage());
            }
            return StopAttempt.FAILED;
        }
    }

    private static boolean executeStopSequence(ProtocolClient client, LauncherArgs parsed, ProtocolOutput output) {
        CapturingProtocolOutput capture = new CapturingProtocolOutput(output);
        if (client == null) {
            return false;
        }

        // 1) Try stop directly.
        if (client.execute("stop", false, capture).isOk()) {
            return true;
        }

        String err = capture.getStderr();
        if (err == null) {
            err = "";
        }

        // 2) If auth is needed and credentials are provided, try auth and then stop again.
        if (RestartSupport.looksLikeAuthIssue(err) && parsed.getAuthUser() != null && parsed.getAuthPass() != null) {
            CapturingProtocolOutput authOut = new CapturingProtocolOutput(output);
            String cmd = "auth " + parsed.getAuthUser() + " " + parsed.getAuthPass();
            if (!client.execute(cmd, false, authOut).isOk()) {
                if (output != null) {
                    output.onStderrLine("Restart: auth failed: " + authOut.getStderr());
                }
                return false;
            }

            CapturingProtocolOutput retry = new CapturingProtocolOutput(output);
            if (client.execute("stop", false, retry).isOk()) {
                return true;
            }
            err = retry.getStderr();
            if (err == null) {
                err = "";
            }
        } else if (RestartSupport.looksLikeAuthIssue(err) && (parsed.getAuthUser() == null || parsed.getAuthPass() == null)) {
            if (output != null) {
                output.onStderrLine("Restart: stop requires authentication; provide --auth-user/--auth-pass or run stop manually.");
            }
            return false;
        }

        // 3) If dangerous confirm token is provided by server, re-run stop with token.
        String token = RestartSupport.extractConfirmTokenBestEffort(err);
        if (token != null) {
            CapturingProtocolOutput confirm = new CapturingProtocolOutput(output);
            if (client.execute("stop --confirm " + token, false, confirm).isOk()) {
                return true;
            }
            if (output != null) {
                output.onStderrLine("Restart: stop confirm failed: " + confirm.getStderr());
            }
            return false;
        }

        if (output != null) {
            output.onStderrLine("Restart: stop failed: " + err);
        }
        return false;
    }

    private static void waitForAgentDownBestEffort(AgentClientConfig cfg, long timeoutMs, ProtocolOutput output) {
        long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            try (ProtocolClient ignored = ProtocolClient.connect(
                cfg.connectHost,
                cfg.port,
                "binary",
                cfg.streamingEnabled,
                cfg.maxPayloadBytes,
                cfg.maxLineBytes,
                com.javasleuth.foundation.security.CommandSigner.noop(),
                400,
                800
            )) {
                sleepBestEffort(200);
            } catch (IOException e) {
                if (output != null) {
                    output.onStdoutLine("Restart: agent is down.");
                }
                return;
            } catch (Exception e) {
                return;
            }
        }
        if (output != null) {
            output.onStderrLine("Restart: timed out waiting for agent to stop (timeoutMs=" + timeoutMs + ")");
        }
    }

    private static void sleepBestEffort(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static int safeParsePid(String pid) {
        if (pid == null) {
            return -1;
        }
        String t = pid.trim();
        if (t.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final class CapturingProtocolOutput implements ProtocolOutput {
        private final ProtocolOutput delegate;
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();

        private CapturingProtocolOutput(ProtocolOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onStdoutLine(String line) {
            stdout.append(line).append('\n');
            if (delegate != null) {
                delegate.onStdoutLine(line);
            }
        }

        @Override
        public void onStderrLine(String line) {
            stderr.append(line).append('\n');
            if (delegate != null) {
                delegate.onStderrLine(line);
            }
        }

        @Override
        public void onStdoutChunk(String chunk) {
            stdout.append(chunk);
            if (delegate != null) {
                delegate.onStdoutChunk(chunk);
            }
        }

        @Override
        public void onStderrChunk(String chunk) {
            stderr.append(chunk);
            if (delegate != null) {
                delegate.onStderrChunk(chunk);
            }
        }

        public String getStdout() {
            return stdout.toString();
        }

        public String getStderr() {
            return stderr.toString();
        }
    }

    private static final class AgentClientConfig {
        final String connectHost;
        final int port;
        final boolean streamingEnabled;
        final int maxPayloadBytes;
        final int maxLineBytes;

        private AgentClientConfig(String connectHost, int port, boolean streamingEnabled, int maxPayloadBytes, int maxLineBytes) {
            this.connectHost = connectHost;
            this.port = port;
            this.streamingEnabled = streamingEnabled;
            this.maxPayloadBytes = maxPayloadBytes;
            this.maxLineBytes = maxLineBytes;
        }

        static AgentClientConfig fromLocalConfig() {
            ProductionConfig config = ProductionConfig.createDefault();
            SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
            int port = typed.server().getPort();
            boolean streamingEnabled = typed.protocol().isStreamingEnabled();
            int maxPayloadBytes = typed.protocol().getFrameMaxPayloadBytes();
            String connectHost = ConnectHostResolver.resolveConnectHost(typed.server().getBindAddress());
            int maxLineBytes = typed.protocol().getTextMaxLineBytes();
            return new AgentClientConfig(connectHost, port, streamingEnabled, maxPayloadBytes, maxLineBytes);
        }
    }

    private static final class TcpListenPidFinder {
        private TcpListenPidFinder() {}

        static long findTcpListenProcessBestEffort(int port) {
            if (port <= 0) {
                return -1;
            }
            String os = System.getProperty("os.name");
            if (os != null && os.toLowerCase().contains("windows")) {
                return findOnWindowsBestEffort(port);
            }
            return findOnUnixBestEffort(port);
        }

        private static long findOnUnixBestEffort(int port) {
            // lsof is widely available on Linux/macOS; best-effort only.
            String[] cmd = new String[] { "lsof", "-t", "-s", "TCP:LISTEN", "-i", "TCP:" + port };
            String out = Exec.readFirstLine(cmd, 1500);
            if (out == null) {
                return -1;
            }
            try {
                return Long.parseLong(out.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static long findOnWindowsBestEffort(int port) {
            String[] lines = Exec.readAllLines(new String[] { "netstat", "-ano", "-p", "TCP" }, 3000);
            if (lines == null || lines.length == 0) {
                return -1;
            }
            String needle = ":" + port;
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!trimmed.toUpperCase().contains("LISTENING")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                if (parts.length != 5) {
                    continue;
                }
                if (!parts[1].endsWith(needle)) {
                    continue;
                }
                try {
                    return Long.parseLong(parts[4]);
                } catch (NumberFormatException ignore) {
                    // ignore
                }
            }
            return -1;
        }
    }

    private static final class Exec {
        private Exec() {}

        static String readFirstLine(String[] cmd, long timeoutMs) {
            String[] lines = readAllLines(cmd, timeoutMs);
            if (lines == null || lines.length == 0) {
                return null;
            }
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String t = line.trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return null;
        }

        static String[] readAllLines(String[] cmd, long timeoutMs) {
            if (cmd == null || cmd.length == 0) {
                return null;
            }
            Process p = null;
            try {
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                boolean done = p.waitFor(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroyForcibly();
                    return null;
                }
                java.io.InputStream in = p.getInputStream();
                java.util.ArrayList<String> lines = new java.util.ArrayList<>();
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines.add(line);
                    }
                }
                return lines.toArray(new String[0]);
            } catch (Exception ignore) {
                if (p != null) {
                    try {
                        p.destroyForcibly();
                    } catch (Exception ignored2) {
                        // ignore
                    }
                }
                return null;
            }
        }
    }

    private static void reportMissingAgentJar() {
        String override = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            override = System.getenv(JarLocator.AGENT_JAR_OVERRIDE_ENV);
        }
        if (override != null && !override.trim().isEmpty()) {
            System.err.println("Agent JAR not found at override path: " + override.trim());
        } else {
            System.err.println("Agent JAR not found on classpath/CodeSource/(agent/target|core/target|target|lib)/.");
        }
        System.err.println("Please build the project first with: mvn clean package");
        System.err.println("Or set -D" + JarLocator.AGENT_JAR_OVERRIDE_PROPERTY + "=<path> (or env " + JarLocator.AGENT_JAR_OVERRIDE_ENV + ")");
    }

    private static void reportMissingContainerJar() {
        String override = System.getProperty(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            override = System.getenv(JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_ENV);
        }
        if (override != null && !override.trim().isEmpty()) {
            System.err.println("Agent CONTAINER JAR not found at override path: " + override.trim());
        } else {
            System.err.println("Agent CONTAINER JAR not found on classpath/CodeSource/(container/target|target|lib)/.");
        }
        System.err.println("Please build the project first with: mvn clean package");
        System.err.println("Or set -D" + JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_PROPERTY + "=<path> (or env " + JarLocator.AGENT_CONTAINER_JAR_OVERRIDE_ENV + ")");
    }

    private VirtualMachineDescriptor selectTargetJvm(LauncherArgs args, JvmDiscovery discovery, JvmSelector selector) throws IOException {
        String pid = args != null ? args.getPid() : null;
        if (pid != null && !pid.trim().isEmpty()) {
            VirtualMachineDescriptor vm = discovery.findByPid(pid.trim());
            if (vm == null) {
                System.err.println("Target JVM not found by PID: " + pid);
            }
            return vm;
        }

        List<VirtualMachineDescriptor> candidates = discovery.listAttachableCandidates();
        if (candidates == null) {
            candidates = new ArrayList<>();
        }
        if (candidates.isEmpty()) {
            System.out.println("No Java processes found.");
            return null;
        }
        return selector.select(candidates);
    }
}
