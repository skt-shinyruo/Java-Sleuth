package com.javasleuth.launcher.jvm;

import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import java.util.List;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * 基于 JLine 的交互式 JVM 选择器。
 *
 * <p>仅负责展示与输入校验，保持“展示序号 = 可选序号”的一致性。</p>
 */
public final class JlineJvmSelector implements JvmSelector {

    @Override
    public VirtualMachineDescriptor select(List<VirtualMachineDescriptor> candidates) throws IOException {
        if (candidates == null || candidates.isEmpty()) {
            System.out.println("No attachable Java processes found (Java-Sleuth itself is excluded).");
            return null;
        }

        System.out.println("Available Java processes:");
        System.out.println("===============================================");

        for (int i = 0; i < candidates.size(); i++) {
            VirtualMachineDescriptor vm = candidates.get(i);
            String displayName = vm != null ? vm.displayName() : "";
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = "Unknown";
            }
            String pid = vm != null ? vm.id() : "unknown";
            System.out.printf("[%d] PID: %-8s %s\n", i + 1, pid, displayName);
        }

        System.out.println();

        Terminal terminal = TerminalBuilder.builder().system(true).build();
        LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

        while (true) {
            String input;
            try {
                input = reader.readLine("Select a process (1-" + candidates.size() + ") or 'q' to quit: ");
            } catch (Exception e) {
                return null;
            }
            if (input == null) {
                return null;
            }
            String trimmed = input.trim();
            if ("q".equalsIgnoreCase(trimmed)) {
                return null;
            }
            try {
                int selection = Integer.parseInt(trimmed);
                if (selection >= 1 && selection <= candidates.size()) {
                    return candidates.get(selection - 1);
                }
                System.out.println("Invalid selection. Please enter a number between 1 and " + candidates.size());
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number or 'q' to quit.");
            }
        }
    }
}

