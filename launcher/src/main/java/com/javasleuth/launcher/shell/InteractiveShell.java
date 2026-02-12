package com.javasleuth.launcher.shell;

import com.javasleuth.launcher.client.CommandResult;
import com.javasleuth.launcher.client.ProtocolClient;
import com.javasleuth.launcher.client.ProtocolOutput;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * 交互式 Shell（基于 JLine）。
 *
 * <p>仅负责读取命令与展示输出，不包含协议握手、Attach 或 JVM 发现逻辑。</p>
 */
public final class InteractiveShell {
    private final StreamPolicy streamPolicy;
    private final ProtocolOutput output;

    public InteractiveShell(StreamPolicy streamPolicy, ProtocolOutput output) {
        this.streamPolicy = streamPolicy;
        this.output = output;
    }

    public void run(ProtocolClient client) throws Exception {
        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).build();

        String welcome = client != null ? client.getWelcomeMessage() : null;
        if (welcome != null && !welcome.trim().isEmpty()) {
            if (output != null) {
                output.onStdoutLine(welcome);
            }
        }

        while (true) {
            String command;
            try {
                command = lineReader.readLine("sleuth> ");
            } catch (Exception e) {
                break;
            }

            if (command == null) {
                break;
            }

            String trimmed = command.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            boolean stream = streamPolicy != null && streamPolicy.isStreamingCommand(trimmed);
            CommandResult result = client.execute(trimmed, stream, output);
            if (!result.isOk() && result.getErrorMessage() != null && output != null) {
                output.onStderrLine("Error: " + result.getErrorMessage());
            }

            if ("quit".equalsIgnoreCase(trimmed)) {
                break;
            }
        }
    }
}

