package com.javasleuth.launcher;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import com.javasleuth.command.protocol.Frame;
import com.javasleuth.command.protocol.FrameCodec;
import com.javasleuth.command.protocol.Utf8LineCodec;
import com.javasleuth.security.RequestSecurityManager;
import com.javasleuth.util.JarLocator;
import com.javasleuth.util.SleuthLogger;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SleuthLauncher {
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();
    private boolean insecureMode;

    public static void main(String[] args) {
        try {
            SleuthLauncher launcher = new SleuthLauncher();
            launcher.start(args);
        } catch (Exception e) {
            System.err.println("Failed to start Java-Sleuth: " + e.getMessage());
            if (Boolean.getBoolean("sleuth.launcher.debug")) {
                SleuthLogger.error("Launcher failure stacktrace (sleuth.launcher.debug=true)", e);
            }
            System.exit(1);
        }
    }

    public void start(String[] args) throws Exception {
        parseArgs(args);
        start();
    }

    public void start() throws Exception {
        System.out.println("Java-Sleuth - Lightweight Java Diagnostic Tool");
        System.out.println("Inspired by Arthas");
        System.out.println();

        File agentJar = JarLocator.locateAgentJar(SleuthLauncher.class);
        if (agentJar == null) {
            String override = System.getProperty(JarLocator.AGENT_JAR_OVERRIDE_PROPERTY);
            if (override == null || override.trim().isEmpty()) {
                override = System.getenv(JarLocator.AGENT_JAR_OVERRIDE_ENV);
            }
            if (override != null && !override.trim().isEmpty()) {
                System.err.println("Agent JAR not found at override path: " + override.trim());
            } else {
                System.err.println("Agent JAR not found on classpath/CodeSource/(core/target|target)/.");
            }
            System.err.println("Please build the project first with: mvn clean package");
            System.err.println("Or set -D" + JarLocator.AGENT_JAR_OVERRIDE_PROPERTY + "=<path> (or env " + JarLocator.AGENT_JAR_OVERRIDE_ENV + ")");
            return;
        }

        VirtualMachineDescriptor selectedVm = selectTargetJvm();
        if (selectedVm == null) {
            System.out.println("No JVM selected. Exiting...");
            return;
        }

        if (insecureMode && !confirmInsecureMode()) {
            System.out.println("Insecure mode confirmation rejected. Exiting...");
            return;
        }

        attachToJvm(selectedVm, agentJar);
        startInteractiveSession(selectedVm);
    }

    private void parseArgs(String[] args) {
        if (args == null) {
            return;
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            String a = arg.trim();
            if (a.isEmpty()) {
                continue;
            }
            if ("--insecure".equalsIgnoreCase(a) || "-insecure".equalsIgnoreCase(a)) {
                insecureMode = true;
            }
        }
    }

    private boolean confirmInsecureMode() throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        System.out.println("⚠️  You are about to enable insecure mode (security.mode=off).");
        System.out.println("    This is intended for local troubleshooting only and is unsafe if exposed via port-forwarding or proxies.");
        System.out.println("    Type 'I UNDERSTAND' to continue, or anything else to cancel.");

        String input = reader.readLine("Confirm insecure mode: ");
        return "I UNDERSTAND".equalsIgnoreCase(input != null ? input.trim() : "");
    }

    private VirtualMachineDescriptor selectTargetJvm() throws IOException {
        List<VirtualMachineDescriptor> vmList = VirtualMachine.list();

        if (vmList.isEmpty()) {
            System.out.println("No Java processes found.");
            return null;
        }

        List<VirtualMachineDescriptor> candidates = new ArrayList<>();
        for (VirtualMachineDescriptor vm : vmList) {
            String displayName = vm.displayName();
            if (displayName == null) {
                displayName = "";
            }
            if (displayName.contains("SleuthLauncher") || displayName.contains("java-sleuth")) {
                continue;
            }
            candidates.add(vm);
        }

        if (candidates.isEmpty()) {
            System.out.println("No attachable Java processes found (Java-Sleuth itself is excluded).");
            return null;
        }

        System.out.println("Available Java processes:");
        System.out.println("===============================================");

        for (int i = 0; i < candidates.size(); i++) {
            VirtualMachineDescriptor vm = candidates.get(i);
            String displayName = vm.displayName();
            if (displayName.isEmpty()) {
                displayName = "Unknown";
            }

            System.out.printf("[%d] PID: %-8s %s\n", i + 1, vm.id(), displayName);
        }

        System.out.println();

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        while (true) {
            try {
                String input = reader.readLine("Select a process (1-" + candidates.size() + ") or 'q' to quit: ");

                if ("q".equalsIgnoreCase(input.trim())) {
                    return null;
                }

                int selection = Integer.parseInt(input.trim());
                if (selection >= 1 && selection <= candidates.size()) {
                    return candidates.get(selection - 1);
                } else {
                    System.out.println("Invalid selection. Please enter a number between 1 and " + candidates.size());
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number or 'q' to quit.");
            }
        }
    }

    private void attachToJvm(VirtualMachineDescriptor vmDesc, File agentJar) throws Exception {
        System.out.println("Attaching to JVM: " + vmDesc.displayName() + " (PID: " + vmDesc.id() + ")");

        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(vmDesc);

            String agentPath = agentJar.getAbsolutePath();
            ProductionConfig config = ProductionConfig.getInstance();
            String configFile = System.getProperty("sleuth.config.file");
            if (configFile == null || configFile.trim().isEmpty()) {
                File local = new File("sleuth.properties");
                if (local.exists()) {
                    configFile = local.getAbsolutePath();
                }
            }

            String securityMode = config.getSecurityMode();
            String hmacSecret = config.getSecurityHmacSecret();
            String hmacSessionRole = config.getHmacSessionRole();

            if (insecureMode) {
                securityMode = "off";
                config.setRuntimeConfig("security.mode", "off");
            } else if ("hmac".equalsIgnoreCase(securityMode)) {
                // HMAC bootstrap is an optional helper: it should not silently force-enable HMAC.
                if (config.isHmacBootstrapOnAttachEnabled()) {
                    if (hmacSecret == null || hmacSecret.trim().isEmpty()) {
                        int bytes = config.getHmacBootstrapSecretBytes();
                        hmacSecret = generateHmacSecret(bytes);
                        config.setRuntimeConfig("security.hmac.secret", hmacSecret);
                    }
                    config.setRuntimeConfig("security.hmac.session.role", hmacSessionRole);
                }

                if (hmacSecret == null || hmacSecret.trim().isEmpty()) {
                    System.err.println("SECURITY ERROR: security.mode=hmac but empty security.hmac.secret");
                    System.err.println("Fix: set security.hmac.secret in configuration, or disable HMAC (security.mode=off)");
                    return;
                }
            }

            StringBuilder agentArgs = new StringBuilder();
            if (configFile != null && !configFile.trim().isEmpty()) {
                agentArgs.append("configFile=").append(configFile).append(";");
            }
            agentArgs.append("server.port=").append(config.getServerPort()).append(";");
            agentArgs.append("protocol.mode=").append(config.getProtocolMode()).append(";");
            agentArgs.append("protocol.streaming.enabled=").append(config.isStreamingEnabled()).append(";");
            agentArgs.append("security.mode=").append(securityMode).append(";");
            if ("hmac".equalsIgnoreCase(securityMode)) {
                agentArgs.append("security.hmac.secret=").append(hmacSecret).append(";");
                agentArgs.append("security.hmac.session.role=").append(hmacSessionRole).append(";");
            }

            vm.loadAgent(agentPath, agentArgs.toString());

            System.out.println("Agent attached successfully!");

            Thread.sleep(2000);

        } finally {
            if (vm != null) {
                vm.detach();
            }
        }
    }

    private void startInteractiveSession(VirtualMachineDescriptor vmDesc) {
        System.out.println("Connecting to Java-Sleuth agent...");

        try {
            Thread.sleep(1000);

            ProductionConfig config = ProductionConfig.getInstance();
            int port = config.getServerPort();
            String protocol = config.getProtocolMode();
            boolean framed = config.isFramedProtocolEnabled();
            boolean binary = config.isBinaryProtocolEnabled();
            boolean streamingEnabled = config.isStreamingEnabled();
            int maxPayloadBytes = config.getFrameMaxPayload();
            boolean handshakeEnabled = config.isHandshakeEnabled();

            String connectHost = resolveConnectHost(config.getServerBindAddress());
            int maxLineBytes = config.getInt(
                "protocol.text.max.line.bytes",
                Math.max(8192, maxPayloadBytes * 2)
            );

            try (Socket socket = new Socket(connectHost, port);
                 BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

                System.out.println("Connected to agent. Starting interactive session...");
                System.out.println();

                Terminal terminal = TerminalBuilder.builder().system(true).build();
                LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();

                String welcomeMessage = Utf8LineCodec.readLine(in, maxLineBytes);
                if (welcomeMessage != null) {
                    System.out.println(welcomeMessage);
                }

                DataInputStream binaryIn = null;
                DataOutputStream binaryOut = null;

                String connId = null;
                if (handshakeEnabled) {
                    // v2 signature binding: client proposes a per-connection connId; server mirrors it.
                    connId = generateNonce();
                    Utf8LineCodec.writeLine(out, buildHelloLine(protocol, connId), true);
                    String configLine = Utf8LineCodec.readLine(in, maxLineBytes);
                    if (configLine != null && configLine.startsWith("CONFIG")) {
                        Map<String, String> kv = parseHandshakeKv(configLine);
                        String negotiated = kv.get("protocol");
                        if (negotiated != null && !negotiated.trim().isEmpty()) {
                            protocol = negotiated.trim().toLowerCase();
                        }
                        binary = "binary".equals(protocol);
                        framed = "framed".equals(protocol);

                        if (kv.containsKey("streaming")) {
                            streamingEnabled = Boolean.parseBoolean(kv.get("streaming"));
                        }
                        if (kv.containsKey("maxpayload")) {
                            Integer parsed = parseIntSafe(kv.get("maxpayload"));
                            if (parsed != null && parsed > 0) {
                                maxPayloadBytes = parsed;
                            }
                        }
                        if (kv.containsKey("connid")) {
                            connId = kv.get("connid");
                        }
                    }
                }

                if (binary) {
                    Utf8LineCodec.writeLine(out, "UPGRADE BINARY", true);
                    String ok = Utf8LineCodec.readLine(in, maxLineBytes);
                    if (ok != null && "OK".equalsIgnoreCase(ok.trim())) {
                        binaryIn = new DataInputStream(in);
                        binaryOut = new DataOutputStream(out);
                    } else {
                        System.err.println("Binary upgrade failed, falling back to framed/legacy mode.");
                        binary = false;
                        framed = config.isFramedProtocolEnabled();
                        protocol = framed ? "framed" : "legacy";
                    }
                }

                RequestSecurityManager securityManager = RequestSecurityManager.getInstance();

                while (true) {
                    try {
                        String command = lineReader.readLine("sleuth> ");

                        if (command == null) {
                            break;
                        }

                        command = command.trim();
                        if (command.isEmpty()) {
                            continue;
                        }

                        String commandName = command.split("\\s+")[0].toLowerCase();
                        boolean stream = (binary || framed) && streamingEnabled &&
                                         ("watch".equals(commandName) ||
                                             "trace".equals(commandName) ||
                                             "monitor".equals(commandName) ||
                                             "tt".equals(commandName) ||
                                             "stack".equals(commandName));

                        if (binary && binaryIn != null && binaryOut != null) {
                            String signed = securityManager.signCommandV2(command, System.currentTimeMillis(), generateNonce(), connId);
                            BinaryFrameCodec.writeFrame(binaryOut, BinaryFrame.request(signed, stream), maxPayloadBytes);
                            while (true) {
                                BinaryFrame frame = BinaryFrameCodec.readFrame(binaryIn, maxPayloadBytes);
                                if (frame == null) {
                                    break;
                                }
                                if (frame.getType() == BinaryFrame.Type.DATA) {
                                    System.out.print(frame.getPayloadUtf8());
                                } else if (frame.getType() == BinaryFrame.Type.ERR) {
                                    System.err.print(frame.getPayloadUtf8());
                                } else if (frame.getType() == BinaryFrame.Type.END) {
                                    break;
                                }
                            }
                        } else if (framed) {
                            String signed = securityManager.signCommandV2(command, System.currentTimeMillis(), generateNonce(), connId);
                            String outbound = stream ? "STREAM " + signed : "CMD " + signed;
                            Utf8LineCodec.writeLine(out, outbound, true);

                            while (true) {
                                Frame frame = FrameCodec.readFrame(in, maxLineBytes);
                                if (frame == null) {
                                    break;
                                }
                                if (frame.getType() == Frame.Type.DATA) {
                                    System.out.println(frame.getPayload());
                                } else if (frame.getType() == Frame.Type.ERR) {
                                    System.err.println(frame.getPayload());
                                } else if (frame.getType() == Frame.Type.END) {
                                    break;
                                }
                            }
                        } else {
                            String signed = securityManager.signCommandV2(command, System.currentTimeMillis(), generateNonce(), connId);
                            Utf8LineCodec.writeLine(out, signed, true);

                            // Legacy mode: prefer END marker when server emits it (newer agents);
                            // otherwise fall back to short read-timeout heuristic for older agents.
                            StringBuilder response = new StringBuilder();
                            int originalTimeout = socket.getSoTimeout();
                            boolean sawEnd = false;
                            try {
                                socket.setSoTimeout(200);
                                while (true) {
                                    try {
                                        String line = Utf8LineCodec.readLine(in, maxLineBytes);
                                        if (line == null) {
                                            break;
                                        }
                                        if ("END".equals(line)) {
                                            sawEnd = true;
                                            break;
                                        }
                                        response.append(line).append("\n");
                                    } catch (java.net.SocketTimeoutException timeout) {
                                        if (sawEnd) {
                                            break;
                                        }
                                        // No END seen yet; assume server is legacy and response finished.
                                        break;
                                    }
                                }
                            } finally {
                                socket.setSoTimeout(originalTimeout);
                            }

                            System.out.print(response.toString());
                        }

                        if ("quit".equalsIgnoreCase(command)) {
                            break;
                        }

                    } catch (Exception e) {
                        System.err.println("Error: " + e.getMessage());
                    }
                }

            } catch (IOException e) {
                System.err.println("Failed to connect to agent: " + e.getMessage());
                System.err.println("Make sure the target JVM is running and the agent is loaded.");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Session ended.");
    }

    private static String resolveConnectHost(String bindAddress) {
        if (bindAddress == null) {
            return "127.0.0.1";
        }
        String v = bindAddress.trim();
        if (v.isEmpty()) {
            return "127.0.0.1";
        }
        String lower = v.toLowerCase();
        // Unspecified bind addresses are not connectable; use loopback for local attach sessions.
        if ("0.0.0.0".equals(lower) || "::".equals(lower) || "0:0:0:0:0:0:0:0".equals(lower)) {
            return "127.0.0.1";
        }
        return v;
    }

    private static String buildHelloLine(String preferredProtocol, String connId) {
        String requested = preferredProtocol != null ? preferredProtocol.trim().toLowerCase() : "legacy";
        String base = "HELLO v=1 protocols=binary,framed,legacy protocol=" + requested;
        if (connId != null && !connId.trim().isEmpty()) {
            return base + " connId=" + connId.trim();
        }
        return base;
    }

    private static Map<String, String> parseHandshakeKv(String line) {
        Map<String, String> kv = new HashMap<>();
        if (line == null) {
            return kv;
        }
        String[] tokens = line.trim().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            int idx = token.indexOf('=');
            if (idx <= 0 || idx >= token.length() - 1) {
                continue;
            }
            String k = token.substring(0, idx).trim().toLowerCase();
            String v = token.substring(idx + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) {
                kv.put(k, v);
            }
        }
        return kv;
    }

    private static Integer parseIntSafe(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String generateNonce() {
        byte[] bytes = new byte[12];
        NONCE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String generateHmacSecret(int bytes) {
        int size = bytes <= 0 ? 32 : Math.min(bytes, 128);
        byte[] buf = new byte[size];
        NONCE_RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
