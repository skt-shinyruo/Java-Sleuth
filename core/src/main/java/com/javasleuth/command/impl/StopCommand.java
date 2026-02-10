package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.util.SleuthLogger;

/**
 * Stop the agent inside the target JVM.
 *
 * <p>Note: this will shutdown the command processor and remove the transformer.
 */
public class StopCommand implements Command {
    private final Runnable shutdownHook;

    public StopCommand(Runnable shutdownHook) {
        this.shutdownHook = shutdownHook;
    }

    @Override
    public String execute(String[] args) {
        if (shutdownHook == null) {
            return "Stop is not supported in this runtime.";
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                shutdownHook.run();
            } catch (Throwable e) {
                SleuthLogger.warn("Stop command shutdown hook failed: " + e.getMessage(), e);
            }
        }, "sleuth-stop");
        t.setDaemon(true);
        t.start();
        return "Stopping Java-Sleuth agent...";
    }

    @Override
    public String getDescription() {
        return "Stop Java-Sleuth agent inside target JVM (shutdown command server and transformer)";
    }
}
