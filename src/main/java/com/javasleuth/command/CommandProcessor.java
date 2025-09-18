package com.javasleuth.command;

import com.javasleuth.command.impl.*;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class CommandProcessor {
    private static final int DEFAULT_PORT = 3658;
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConcurrentHashMap<String, Command> commands;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public CommandProcessor(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.commands = new ConcurrentHashMap<>();
        initializeCommands();
    }

    private void initializeCommands() {
        // Existing commands
        commands.put("dashboard", new DashboardCommand(instrumentation));
        commands.put("thread", new ThreadCommand(instrumentation));
        commands.put("sc", new SearchClassCommand(instrumentation));
        commands.put("sm", new SearchMethodCommand(instrumentation));
        commands.put("watch", new WatchCommand(instrumentation, transformer));
        commands.put("trace", new TraceCommand(instrumentation, transformer));
        commands.put("redefine", new RedefineCommand(instrumentation));
        commands.put("mc", new MemoryCompilerCommand());
        commands.put("retransform", new RetransformCommand(instrumentation));

        // Phase 4 - High Priority Commands
        commands.put("jvm", new JvmCommand(instrumentation));
        commands.put("sysprop", new SysPropCommand(instrumentation));
        commands.put("sysenv", new SysEnvCommand(instrumentation));
        commands.put("vmoption", new VmOptionCommand(instrumentation));
        commands.put("memory", new MemoryCommand(instrumentation));
        commands.put("heapdump", new HeapDumpCommand(instrumentation));

        // Phase 5 - Critical Production Commands
        commands.put("jad", new JadCommand(instrumentation));
        commands.put("classloader", new ClassLoaderCommand(instrumentation));
        commands.put("mbean", new MBeanCommand(instrumentation));

        // System commands
        commands.put("help", new HelpCommand(commands));
        commands.put("quit", new QuitCommand());
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            serverSocket = new ServerSocket(DEFAULT_PORT);
            System.out.println("Java-Sleuth listening on port " + DEFAULT_PORT);

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    Thread clientThread = new Thread(() -> handleClient(clientSocket));
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Error accepting client connection: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to start command processor: " + e.getMessage());
        }
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

            writer.println("Welcome to Java-Sleuth! Type 'help' for available commands.");

            String line;
            while ((line = reader.readLine()) != null && running.get()) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                String commandName = parts[0].toLowerCase();

                Command command = commands.get(commandName);
                if (command != null) {
                    try {
                        String result = command.execute(parts);
                        writer.println(result);

                        if ("quit".equals(commandName)) {
                            break;
                        }
                    } catch (Exception e) {
                        writer.println("Error executing command: " + e.getMessage());
                    }
                } else {
                    writer.println("Unknown command: " + commandName + ". Type 'help' for available commands.");
                }
            }
        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public void shutdown() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}