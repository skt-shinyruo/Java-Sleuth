package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.security.SecurityValidator;
import com.javasleuth.util.PerformanceOptimizer;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.management.MBeanServer;
import javax.management.ObjectName;

public class HeapDumpCommand implements Command {
    private final Instrumentation instrumentation;

    public HeapDumpCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        String fileName = null;
        boolean liveOnly = true;

        // Parse arguments
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("--all".equals(arg) || "-a".equals(arg)) {
                liveOnly = false;
            } else if ("--live".equals(arg) || "-l".equals(arg)) {
                liveOnly = true;
            } else if (arg.startsWith("--file=")) {
                fileName = arg.substring(7);
            } else if (!arg.startsWith("-")) {
                fileName = arg;
            } else {
                return "Unknown option: " + arg + ". Use --help for usage information.";
            }
        }

        // Security validation for filename
        if (fileName != null && !SecurityValidator.canAccessFile(fileName)) {
            return "Invalid or inaccessible file path: " + fileName;
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

        return PerformanceOptimizer.executeAsync(() -> createHeapDump(finalFileName, finalLiveOnly), "heapdump")
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

    private String getHelpText() {
        return "=== Heap Dump Command Help ===\n" +
               "Create a heap dump for memory analysis\n\n" +
               "Usage:\n" +
               "  heapdump [options] [filename]\n" +
               "  heapdump --help                Show this help message\n\n" +
               "Options:\n" +
               "  --live, -l                     Dump only live objects (default)\n" +
               "  --all, -a                      Dump all objects including unreachable ones\n" +
               "  --file=<filename>              Specify output filename\n\n" +
               "Examples:\n" +
               "  heapdump                       Create dump with auto-generated filename\n" +
               "  heapdump myapp.hprof           Create dump with specific filename\n" +
               "  heapdump --all app-full.hprof  Create dump including unreachable objects\n" +
               "  heapdump --live /tmp/heap.hprof Create live-only dump at specific path\n\n" +
               "File Naming:\n" +
               "- If no filename specified, auto-generates: heapdump-YYYYMMDD-HHMMSS.hprof\n" +
               "- .hprof extension is automatically added if missing\n" +
               "- Relative paths are relative to current working directory\n" +
               "- Use absolute paths for specific locations\n\n" +
               "Analysis Tools:\n" +
               "- Eclipse MAT (Memory Analyzer Tool) - Recommended\n" +
               "- jhat <filename> - Built-in Java heap analyzer\n" +
               "- VisualVM - GUI tool for heap analysis\n" +
               "- JProfiler - Commercial profiling tool\n" +
               "- FastThread.io - Online heap analyzer\n\n" +
               "Performance Notes:\n" +
               "- Heap dumps can be large (similar to heap size)\n" +
               "- Creating dumps may pause the application briefly\n" +
               "- --live option triggers GC and is typically faster\n" +
               "- Ensure sufficient disk space before creating dumps\n\n" +
               "Troubleshooting:\n" +
               "- Check file permissions and disk space\n" +
               "- Ensure target directory exists\n" +
               "- Some JVMs may not support heap dumping\n" +
               "- Use jcmd as alternative: jcmd <pid> GC.heap_dump <file>\n";
    }

    @Override
    public String getDescription() {
        return "Create heap dumps for memory analysis";
    }
}