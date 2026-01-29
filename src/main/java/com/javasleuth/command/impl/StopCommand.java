package com.javasleuth.command.impl;

import com.javasleuth.agent.SleuthAgent;
import com.javasleuth.command.Command;

/**
 * Stop the agent inside the target JVM.
 *
 * <p>Note: this will shutdown the command processor and remove the transformer.
 */
public class StopCommand implements Command {
    @Override
    public String execute(String[] args) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            SleuthAgent.shutdown();
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

