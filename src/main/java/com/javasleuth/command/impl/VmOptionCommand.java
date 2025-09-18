package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.regex.Pattern;

public class VmOptionCommand implements Command {
    private final Instrumentation instrumentation;
    private final RuntimeMXBean runtimeMXBean;

    public VmOptionCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        if (args.length == 1) {
            // List all VM options
            return listAllVmOptions();
        } else if (args.length == 2) {
            // Search VM options by pattern
            String pattern = args[1];
            return searchVmOptions(pattern);
        } else {
            return "Invalid arguments. Use: vmoption [pattern] or vmoption --help for help";
        }
    }

    private String listAllVmOptions() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== JVM Input Arguments ===\n");

        List<String> inputArguments = runtimeMXBean.getInputArguments();

        if (inputArguments.isEmpty()) {
            sb.append("No JVM arguments specified\n");
        } else {
            sb.append(String.format("Total VM options: %d\n\n", inputArguments.size()));

            // Categorize the options
            categorizeAndDisplayOptions(sb, inputArguments);
        }

        return sb.toString();
    }

    private String searchVmOptions(String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== JVM Options Search ===\n");
        sb.append("Pattern: ").append(pattern).append("\n\n");

        List<String> inputArguments = runtimeMXBean.getInputArguments();

        if (inputArguments.isEmpty()) {
            sb.append("No JVM arguments to search\n");
            return sb.toString();
        }

        // Convert wildcard pattern to regex
        String regex = pattern.replace("*", ".*").replace("?", ".?");
        Pattern compiledPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);

        int matchCount = 0;
        for (int i = 0; i < inputArguments.size(); i++) {
            String option = inputArguments.get(i);
            if (compiledPattern.matcher(option).find()) {
                sb.append(String.format("[%d] %s\n", i + 1, option));
                sb.append("    Category: ").append(categorizeOption(option)).append("\n");
                sb.append("    Description: ").append(getOptionDescription(option)).append("\n\n");
                matchCount++;
            }
        }

        if (matchCount == 0) {
            sb.append("No VM options found matching pattern: ").append(pattern);
        } else {
            sb.insert(sb.indexOf("Pattern:") + pattern.length() + 9,
                String.format(" (found %d matches)", matchCount));
        }

        return sb.toString();
    }

    private void categorizeAndDisplayOptions(StringBuilder sb, List<String> inputArguments) {
        // Memory options
        sb.append("Memory Options:\n");
        displayCategoryOptions(sb, inputArguments, "memory");

        // GC options
        sb.append("\nGarbage Collection Options:\n");
        displayCategoryOptions(sb, inputArguments, "gc");

        // Performance options
        sb.append("\nPerformance Options:\n");
        displayCategoryOptions(sb, inputArguments, "performance");

        // Debug options
        sb.append("\nDebugging Options:\n");
        displayCategoryOptions(sb, inputArguments, "debug");

        // Agent options
        sb.append("\nAgent Options:\n");
        displayCategoryOptions(sb, inputArguments, "agent");

        // System options
        sb.append("\nSystem Options:\n");
        displayCategoryOptions(sb, inputArguments, "system");

        // Other options
        sb.append("\nOther Options:\n");
        displayCategoryOptions(sb, inputArguments, "other");
    }

    private void displayCategoryOptions(StringBuilder sb, List<String> inputArguments, String category) {
        boolean hasOptions = false;
        for (int i = 0; i < inputArguments.size(); i++) {
            String option = inputArguments.get(i);
            if (categorizeOption(option).toLowerCase().contains(category)) {
                sb.append(String.format("  [%d] %s\n", i + 1, option));
                String description = getOptionDescription(option);
                if (!description.isEmpty()) {
                    sb.append("      ").append(description).append("\n");
                }
                hasOptions = true;
            }
        }
        if (!hasOptions) {
            sb.append("  None\n");
        }
    }

    private String categorizeOption(String option) {
        if (option.startsWith("-Xms") || option.startsWith("-Xmx") ||
            option.startsWith("-XX:NewRatio") || option.startsWith("-XX:SurvivorRatio") ||
            option.startsWith("-XX:PermSize") || option.startsWith("-XX:MaxPermSize") ||
            option.startsWith("-XX:MetaspaceSize") || option.startsWith("-XX:MaxMetaspaceSize") ||
            option.startsWith("-XX:NewSize") || option.startsWith("-XX:MaxNewSize")) {
            return "Memory";
        }

        if (option.startsWith("-XX:+Use") && option.contains("GC") ||
            option.startsWith("-XX:-Use") && option.contains("GC") ||
            option.startsWith("-XX:GC") || option.startsWith("-XX:MaxGCPauseMillis") ||
            option.startsWith("-XX:GCTimeRatio") || option.startsWith("-XX:ParallelGCThreads")) {
            return "Garbage Collection";
        }

        if (option.startsWith("-XX:+Optimize") || option.startsWith("-XX:-Optimize") ||
            option.startsWith("-XX:CompileThreshold") || option.startsWith("-XX:+TieredCompilation") ||
            option.startsWith("-XX:+UseCompression") || option.startsWith("-XX:+AggressiveOpts")) {
            return "Performance";
        }

        if (option.startsWith("-Xdebug") || option.startsWith("-agentlib:jdwp") ||
            option.startsWith("-XX:+PrintGC") || option.startsWith("-XX:+Verbose") ||
            option.startsWith("-XX:+TraceClass") || option.startsWith("-verbose")) {
            return "Debug";
        }

        if (option.startsWith("-javaagent") || option.startsWith("-agentpath") ||
            option.startsWith("-agentlib")) {
            return "Agent";
        }

        if (option.startsWith("-D") || option.startsWith("-Xbootclasspath") ||
            option.startsWith("-classpath") || option.startsWith("-cp")) {
            return "System";
        }

        return "Other";
    }

    private String getOptionDescription(String option) {
        if (option.startsWith("-Xms")) return "Initial heap size";
        if (option.startsWith("-Xmx")) return "Maximum heap size";
        if (option.startsWith("-XX:+UseG1GC")) return "Enable G1 garbage collector";
        if (option.startsWith("-XX:+UseParallelGC")) return "Enable parallel garbage collector";
        if (option.startsWith("-XX:+UseSerialGC")) return "Enable serial garbage collector";
        if (option.startsWith("-XX:+UseConcMarkSweepGC")) return "Enable CMS garbage collector";
        if (option.startsWith("-XX:NewRatio")) return "Ratio of old/young generation";
        if (option.startsWith("-XX:SurvivorRatio")) return "Ratio of eden/survivor space";
        if (option.startsWith("-XX:MaxPermSize")) return "Maximum permanent generation size";
        if (option.startsWith("-XX:MetaspaceSize")) return "Initial metaspace size";
        if (option.startsWith("-XX:MaxMetaspaceSize")) return "Maximum metaspace size";
        if (option.startsWith("-Xdebug")) return "Enable debugging support";
        if (option.startsWith("-verbose:gc")) return "Enable verbose garbage collection logging";
        if (option.startsWith("-verbose:class")) return "Enable verbose class loading logging";
        if (option.startsWith("-javaagent")) return "Load Java agent";
        if (option.startsWith("-D")) {
            String[] parts = option.split("=", 2);
            return "System property: " + parts[0].substring(2);
        }
        if (option.startsWith("-XX:+TieredCompilation")) return "Enable tiered compilation";
        if (option.startsWith("-XX:+UseCompressedOops")) return "Use compressed ordinary object pointers";
        if (option.startsWith("-XX:+PrintGCDetails")) return "Print detailed GC information";

        return "";
    }

    private String getHelpText() {
        return "=== VM Options Command Help ===\n" +
               "Display and analyze JVM startup options and arguments\n\n" +
               "Usage:\n" +
               "  vmoption                   List all VM options by category\n" +
               "  vmoption <pattern>         Search VM options using wildcards (* and ?)\n" +
               "  vmoption --help            Show this help message\n\n" +
               "Examples:\n" +
               "  vmoption                   Show all VM options categorized\n" +
               "  vmoption *gc*              Find all GC-related options\n" +
               "  vmoption -Xm*              Find all memory-related Xm options\n" +
               "  vmoption *agent*           Find all agent-related options\n" +
               "  vmoption -D*               Find all system property definitions\n\n" +
               "Categories:\n" +
               "- Memory: Heap size, metaspace, and memory-related options\n" +
               "- Garbage Collection: GC algorithm and tuning options\n" +
               "- Performance: Compilation and optimization options\n" +
               "- Debug: Debugging and logging options\n" +
               "- Agent: Java agent and instrumentation options\n" +
               "- System: System properties and classpath options\n" +
               "- Other: Miscellaneous options\n\n" +
               "Note: These are the actual options used to start the current JVM process.\n" +
               "Runtime modification of most VM options is not supported.\n";
    }

    @Override
    public String getDescription() {
        return "Display and analyze JVM startup options and arguments";
    }
}