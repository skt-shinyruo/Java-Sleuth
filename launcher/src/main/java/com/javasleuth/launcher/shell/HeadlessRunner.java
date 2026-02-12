package com.javasleuth.launcher.shell;

import com.javasleuth.launcher.client.CommandResult;
import com.javasleuth.launcher.client.ProtocolClient;
import com.javasleuth.launcher.client.ProtocolOutput;
import com.javasleuth.security.SecurityValidator;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Headless 模式执行器（自动化/脚本）。
 *
 * <p>仅负责驱动 ProtocolClient 执行命令序列，不负责 Attach/JVM 发现。</p>
 */
public final class HeadlessRunner {
    private final StreamPolicy streamPolicy;
    private final ProtocolOutput output;
    private final boolean failFast;

    public HeadlessRunner(StreamPolicy streamPolicy, ProtocolOutput output, boolean failFast) {
        this.streamPolicy = streamPolicy;
        this.output = output;
        this.failFast = failFast;
    }

    public int runSingleCommand(ProtocolClient client, String command) {
        if (command == null || command.trim().isEmpty()) {
            if (output != null) {
                output.onStderrLine("Headless error: --cmd is empty");
            }
            return 2;
        }

        boolean stream = streamPolicy != null && streamPolicy.isStreamingCommand(command);
        CommandResult result = client.execute(command, stream, output);
        return result.isOk() ? 0 : 1;
    }

    public int runScript(ProtocolClient client, String scriptPath) {
        if (scriptPath == null || scriptPath.trim().isEmpty()) {
            if (output != null) {
                output.onStderrLine("Headless error: --script is empty");
            }
            return 2;
        }
        if (!SecurityValidator.canReadFile(scriptPath)) {
            if (output != null) {
                output.onStderrLine("Headless error: script file is not readable or not allowed: " + scriptPath);
            }
            return 2;
        }

        File file = new File(scriptPath);
        if (!file.exists() || !file.isFile()) {
            if (output != null) {
                output.onStderrLine("Headless error: script file not found: " + scriptPath);
            }
            return 2;
        }

        int executed = 0;
        int failed = 0;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                executed++;
                boolean stream = streamPolicy != null && streamPolicy.isStreamingCommand(trimmed);
                CommandResult result = client.execute(trimmed, stream, output);
                if (!result.isOk()) {
                    failed++;
                    if (output != null && result.getErrorMessage() != null) {
                        output.onStderrLine("Command failed: " + trimmed);
                        output.onStderrLine("Error: " + result.getErrorMessage());
                    }
                    if (failFast) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (output != null) {
                output.onStderrLine("Headless error: failed to read/execute script: " + e.getMessage());
            }
            return 2;
        }

        if (output != null) {
            output.onStdoutLine("Headless summary: executed=" + executed + ", failed=" + failed);
        }
        return failed == 0 ? 0 : 1;
    }
}

