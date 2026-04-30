package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.SecurityValidator;
import com.javasleuth.foundation.util.PerformanceOptimizer;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public class HeapDumpCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("heapdump")
        .description("Create heap dumps for memory analysis")
        .usage("heapdump [--live|--all] [--file <filename>] [filename]")
        .meta(CommandMeta.admin(false, false)
            .withDangerous(true)
            .withImpact(CommandMeta.ImpactLevel.HIGH)
            .withRateLimit(2)
            .withCapability(CommandCapability.WRITES_DISK))
        .argument(ArgumentSpec.optional("filename"))
        .option(OptionSpec.flag("live").alias("-l").build())
        .option(OptionSpec.flag("all").alias("-a").build())
        .option(OptionSpec.string("file").build())
        .example("heapdump --all app-full.hprof")
        .build();

    private final Instrumentation instrumentation;
    private final PerformanceOptimizer performanceOptimizer;

    public HeapDumpCommand(Instrumentation instrumentation, PerformanceOptimizer performanceOptimizer) {
        this.instrumentation = instrumentation;
        if (performanceOptimizer == null) {
            throw new IllegalArgumentException("performanceOptimizer");
        }
        this.performanceOptimizer = performanceOptimizer;
    }

    public static CommandSpec spec() {
        return SPEC;
    }

    @Override
    public CommandSpec getSpec() {
        return SPEC;
    }

    @Override
    public String execute(String[] args) throws Exception {
        ParsedCommand parsed = CommandSpecSupport.parsed(SPEC, args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(SPEC);
        }

        String fileName = parsed.isOptionExplicit("file") ? parsed.stringOption("file") : parsed.argument("filename");
        boolean liveOnly = !Boolean.TRUE.equals(parsed.booleanOption("all"));
        if (Boolean.TRUE.equals(parsed.booleanOption("live"))) {
            liveOnly = true;
        }

        // Security validation for filename (heapdump writes a new file)
        if (fileName != null && !SecurityValidator.canWriteFile(fileName)) {
            return "Invalid or non-writable file path: " + fileName;
        }

        // Generate default filename if not provided
        if (fileName == null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
            fileName = "heapdump-" + sdf.format(new Date()) + ".hprof";
        }

        // Ensure .hprof extension
        if (!fileName.endsWith(".hprof")) {
            fileName += ".hprof";
        }

        // Make variables effectively final for lambda
        final String finalFileName = fileName;
        final boolean finalLiveOnly = liveOnly;

        return performanceOptimizer.executeAsync(() -> createHeapDump(finalFileName, finalLiveOnly), "heapdump")
                .get(); // Block to wait for completion since heap dumps need to complete
    }

    private String createHeapDump(String fileName, boolean liveOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Heap Dump ===\n");
        sb.append("Creating heap dump...\n");
        sb.append("File: ").append(fileName).append("\n");
        sb.append("Live objects only: ").append(liveOnly).append("\n\n");

        try {
            File dumpFile = new File(fileName);
            String absolutePath = dumpFile.getAbsolutePath();

            // Get heap dump size estimation before dump
            long beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long startTime = System.currentTimeMillis();

            // Create heap dump using HotSpot diagnostic MBean
            boolean success = createHeapDumpUsingMBean(absolutePath, liveOnly);

            if (success) {
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                // Get file size
                long fileSize = dumpFile.length();

                sb.append("Heap dump created successfully!\n");
                sb.append("Location: ").append(absolutePath).append("\n");
                sb.append("File size: ").append(formatBytes(fileSize)).append("\n");
                sb.append("Duration: ").append(duration).append(" ms\n");
                sb.append("Memory used during dump: ~").append(formatBytes(beforeMemory)).append("\n\n");

                sb.append("Analysis suggestions:\n");
                sb.append("- Use Eclipse MAT (Memory Analyzer Tool) to analyze the dump\n");
                sb.append("- Use jhat: jhat ").append(fileName).append("\n");
                sb.append("- Use VisualVM to open and analyze the heap dump\n");
                sb.append("- Use JProfiler or other profiling tools\n\n");

                sb.append("Common analysis commands:\n");
                sb.append("- jhat -port 7000 ").append(fileName).append(" (starts web server on port 7000)\n");
                sb.append("- Look for memory leaks, largest objects, and dominator trees\n");
            } else {
                sb.append("Failed to create heap dump using MBean interface.\n");
                sb.append("This might be due to:\n");
                sb.append("- Insufficient permissions\n");
                sb.append("- Unsupported JVM\n");
                sb.append("- Invalid file path\n");
                sb.append("- Insufficient disk space\n");
            }

        } catch (Exception e) {
            sb.append("Error creating heap dump: ").append(e.getMessage()).append("\n");
            sb.append("Possible solutions:\n");
            sb.append("- Check file permissions and disk space\n");
            sb.append("- Ensure directory exists\n");
            sb.append("- Try a different file location\n");
            sb.append("- Use jcmd <pid> GC.run_finalization; jcmd <pid> VM.gc; jcmd <pid> GC.heap_dump <file>\n");
        }

        return sb.toString();
    }

    private boolean createHeapDumpUsingMBean(String fileName, boolean liveOnly) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName("com.sun.management:type=HotSpotDiagnostic");

            // Check if the MBean is available
            if (!server.isRegistered(objectName)) {
                return false;
            }

            // Invoke the dumpHeap operation
            server.invoke(objectName, "dumpHeap",
                new Object[]{fileName, liveOnly},
                new String[]{"java.lang.String", "boolean"});

            return true;
        } catch (Exception e) {
            // Try alternative approach using sun.management.ManagementFactoryHelper
            try {
                Class<?> helperClass = Class.forName("sun.management.ManagementFactoryHelper");
                Object hotspotMBean = helperClass.getMethod("getHotspotDiagnosticMXBean").invoke(null);

                hotspotMBean.getClass()
                    .getMethod("dumpHeap", String.class, boolean.class)
                    .invoke(hotspotMBean, fileName, liveOnly);

                return true;
            } catch (Exception ex) {
                // If both approaches fail, return false
                return false;
            }
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    @Override
    public String getDescription() {
        return "Create heap dumps for memory analysis";
    }
}
