package com.javasleuth.launcher;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.*;
import java.net.Socket;
import java.util.List;

public class SleuthLauncher {
    private static final String AGENT_JAR_NAME = "java-sleuth-1.0.0-jar-with-dependencies.jar";
    private static final int DEFAULT_PORT = 3658;

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
            vm.loadAgent(agentPath);

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

            try (Socket socket = new Socket("localhost", DEFAULT_PORT);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

                System.out.println("Connected to agent. Starting interactive session...");
                System.out.println();

                Terminal terminal = TerminalBuilder.builder().system(true).build();
                LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();

                String welcomeMessage = reader.readLine();
                if (welcomeMessage != null) {
                    System.out.println(welcomeMessage);
                }

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

                        writer.println(command);

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
}