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

public class SleuthLauncher {

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
        boolean attached = attacher.attach(
            selectedVm.id(),
            selectedVm.displayName(),
            agentJar,
            containerJar
        );
        if (!attached) {
            return 1;
        }

        if (parsed.getLaunchMode() == LaunchMode.HEADLESS) {
            return runHeadless(parsed);
        }

        startInteractiveSession();
        return 0;
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

    private void startInteractiveSession() {
        System.out.println("Connecting to Java-Sleuth agent...");

        try {
            ProductionConfig config = ProductionConfig.createDefault();
            SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
            int port = typed.server().getPort();
            boolean streamingEnabled = typed.protocol().isStreamingEnabled();
            int maxPayloadBytes = typed.protocol().getFrameMaxPayloadBytes();
            String connectHost = ConnectHostResolver.resolveConnectHost(typed.server().getBindAddress());
            int maxLineBytes = typed.protocol().getTextMaxLineBytes();

            ProtocolOutput output = new ConsoleProtocolOutput(System.out, System.err);
            try (ProtocolClient client = ProtocolClient.connectWithRetry(
                connectHost,
                port,
                "binary",
                streamingEnabled,
                maxPayloadBytes,
                maxLineBytes
            )) {
                System.out.println("Connected to agent. Starting interactive session...");
                System.out.println();
                new InteractiveShell(new DefaultStreamPolicy(), output).run(client);
            }

        } catch (IOException e) {
            System.err.println("Failed to connect to agent: " + e.getMessage());
            System.err.println("Make sure the target JVM is running and the agent is loaded.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }

        System.out.println("Session ended.");
    }

    private int runHeadless(LauncherArgs parsed) {
        ProductionConfig config = ProductionConfig.createDefault();
        SleuthConfig typed = SleuthConfigParser.parse(config.snapshot());
        int port = typed.server().getPort();
        boolean streamingEnabled = typed.protocol().isStreamingEnabled();
        int maxPayloadBytes = typed.protocol().getFrameMaxPayloadBytes();
        String connectHost = ConnectHostResolver.resolveConnectHost(typed.server().getBindAddress());
        int maxLineBytes = typed.protocol().getTextMaxLineBytes();

        ProtocolOutput output = new ConsoleProtocolOutput(System.out, System.err);
        try (ProtocolClient client = ProtocolClient.connectWithRetry(
            connectHost,
            port,
            "binary",
            streamingEnabled,
            maxPayloadBytes,
            maxLineBytes
        )) {
            HeadlessRunner runner = new HeadlessRunner(new DefaultStreamPolicy(), output, parsed.isFailFast());
            if (parsed.getCommand() != null) {
                return runner.runSingleCommand(client, parsed.getCommand());
            }
            if (parsed.getScriptPath() != null) {
                return runner.runScript(client, parsed.getScriptPath());
            }
            output.onStderrLine("Headless error: missing --cmd/--script");
            return 2;
        } catch (IOException e) {
            output.onStderrLine("Failed to connect to agent: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            output.onStderrLine("Headless error: " + e.getMessage());
            return 1;
        }
    }
}
