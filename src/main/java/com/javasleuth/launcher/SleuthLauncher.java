package com.javasleuth.launcher;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.command.protocol.BinaryFrame;
import com.javasleuth.command.protocol.BinaryFrameCodec;
import com.javasleuth.command.protocol.Frame;
import com.javasleuth.command.protocol.FrameCodec;
import com.javasleuth.security.RequestSecurityManager;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SleuthLauncher {
    private static final String AGENT_JAR_NAME = "java-sleuth-1.0.0-jar-with-dependencies.jar";
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();

    public static void main(String[] args) {
        try {
            SleuthLauncher launcher = new SleuthLauncher();
            launcher.start();
        } catch (Exception e) {
            System.err.println("Failed to start Java-Sleuth: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void start() throws Exception {
        System.out.println("Java-Sleuth - Lightweight Java Diagnostic Tool");
        System.out.println("Inspired by Arthas");
        System.out.println();

        if (!isAgentJarAvailable()) {
            System.err.println("Agent JAR not found: " + AGENT_JAR_NAME);
            System.err.println("Please build the project first with: mvn clean package");
            return;
        }

        VirtualMachineDescriptor selectedVm = selectTargetJvm();
        if (selectedVm == null) {
            System.out.println("No JVM selected. Exiting...");
            return;
        }

        attachToJvm(selectedVm);
        startInteractiveSession(selectedVm);
    }

    private boolean isAgentJarAvailable() {
        File agentJar = new File("target/" + AGENT_JAR_NAME);
        return agentJar.exists();
    }

    private VirtualMachineDescriptor selectTargetJvm() throws IOException {
        List<VirtualMachineDescriptor> vmList = VirtualMachine.list();

        if (vmList.isEmpty()) {
            System.out.println("No Java processes found.");
            return null;
        }

        System.out.println("Available Java processes:");
        System.out.println("===============================================");

        for (int i = 0; i < vmList.size(); i++) {
            VirtualMachineDescriptor vm = vmList.get(i);
            String displayName = vm.displayName();
            if (displayName.isEmpty()) {
                displayName = "Unknown";
            }

            if (displayName.contains("SleuthLauncher") || displayName.contains("java-sleuth")) {
                continue;
            }

            System.out.printf("[%d] PID: %-8s %s\n", i + 1, vm.id(), displayName);
        }

        System.out.println();

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        while (true) {
            try {
                String input = reader.readLine("Select a process (1-" + vmList.size() + ") or 'q' to quit: ");

                if ("q".equalsIgnoreCase(input.trim())) {
                    return null;
                }

                int selection = Integer.parseInt(input.trim());
                if (selection >= 1 && selection <= vmList.size()) {
                    VirtualMachineDescriptor selected = vmList.get(selection - 1);

                    if (selected.displayName().contains("SleuthLauncher") ||
                        selected.displayName().contains("java-sleuth")) {
                        System.out.println("Cannot attach to Java-Sleuth itself. Please select another process.");
                        continue;
                    }

                    return selected;
                } else {
                    System.out.println("Invalid selection. Please enter a number between 1 and " + vmList.size());
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number or 'q' to quit.");
            }
        }
    }

    private void attachToJvm(VirtualMachineDescriptor vmDesc) throws Exception {
        System.out.println("Attaching to JVM: " + vmDesc.displayName() + " (PID: " + vmDesc.id() + ")");

        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(vmDesc);

            String agentPath = new File("target/" + AGENT_JAR_NAME).getAbsolutePath();
            ProductionConfig config = ProductionConfig.getInstance();
            String configFile = System.getProperty("sleuth.config.file");
            if (configFile == null || configFile.trim().isEmpty()) {
                File local = new File("sleuth.properties");
                if (local.exists()) {
                    configFile = local.getAbsolutePath();
                }
            }

            StringBuilder agentArgs = new StringBuilder();
            if (configFile != null && !configFile.trim().isEmpty()) {
                agentArgs.append("configFile=").append(configFile).append(";");
            }
            agentArgs.append("server.port=").append(config.getServerPort()).append(";");
            agentArgs.append("protocol.mode=").append(config.getProtocolMode()).append(";");
            agentArgs.append("protocol.streaming.enabled=").append(config.isStreamingEnabled()).append(";");

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

            try (Socket socket = new Socket("localhost", port);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                System.out.println("Connected to agent. Starting interactive session...");
                System.out.println();

                Terminal terminal = TerminalBuilder.builder().system(true).build();
                LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();

                String welcomeMessage = reader.readLine();
                if (welcomeMessage != null) {
                    System.out.println(welcomeMessage);
                }

                DataInputStream binaryIn = null;
                DataOutputStream binaryOut = null;

                if (handshakeEnabled) {
                    writer.println(buildHelloLine(protocol));
                    writer.flush();
                    String configLine = reader.readLine();
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
                    }
                }

                if (binary) {
                    writer.println("UPGRADE BINARY");
                    writer.flush();
                    String ok = reader.readLine();
                    if (ok != null && "OK".equalsIgnoreCase(ok.trim())) {
                        binaryIn = new DataInputStream(socket.getInputStream());
                        binaryOut = new DataOutputStream(socket.getOutputStream());
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
                                         ("watch".equals(commandName) || "trace".equals(commandName));

                        if (binary && binaryIn != null && binaryOut != null) {
                            String signed = securityManager.signCommand(command, System.currentTimeMillis(), generateNonce());
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
                            String signed = securityManager.signCommand(command, System.currentTimeMillis(), generateNonce());
                            String outbound = stream ? "STREAM " + signed : "CMD " + signed;
                            writer.println(outbound);

                            while (true) {
                                Frame frame = FrameCodec.readFrame(reader);
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
                            String signed = securityManager.signCommand(command, System.currentTimeMillis(), generateNonce());
                            writer.println(signed);

                            StringBuilder response = new StringBuilder();
                            String line;
                            long startTime = System.currentTimeMillis();

                            while ((line = reader.readLine()) != null) {
                                response.append(line).append("\n");

                                if (System.currentTimeMillis() - startTime > 100 &&
                                    !reader.ready()) {
                                    break;
                                }
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

    private static String buildHelloLine(String preferredProtocol) {
        String requested = preferredProtocol != null ? preferredProtocol.trim().toLowerCase() : "legacy";
        return "HELLO v=1 protocols=binary,framed,legacy protocol=" + requested;
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
}
