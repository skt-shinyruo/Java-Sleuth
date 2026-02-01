package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.util.StringUtils;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ThreadCommand implements Command {
    private final ThreadMXBean threadMXBean;

    public ThreadCommand(Instrumentation instrumentation) {
        this.threadMXBean = ManagementFactory.getThreadMXBean();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1) {
            String option = args[1].toLowerCase();
            switch (option) {
                case "-b":
                case "--blocked":
                    return getBlockedThreads();
                case "-d":
                case "--deadlock":
                    return getDeadlockInfo();
                case "-n":
                case "--top":
                    int n = args.length > 2 ? parseInt(args[2], 5) : 5;
                    long intervalMs = 200;
                    for (int i = 3; i < args.length; i++) {
                        String a = args[i];
                        if ("-i".equals(a) || "--interval".equals(a)) {
                            if (i + 1 < args.length) {
                                intervalMs = parseLong(args[++i], 200);
                            }
                        }
                    }
                    return getTopCpuThreads(n, intervalMs);
                case "-h":
                case "--help":
                    return getHelp();
                default:
                    try {
                        long threadId = Long.parseLong(args[1]);
                        return getThreadDetails(threadId);
                    } catch (NumberFormatException e) {
                        return "Invalid thread ID: " + args[1] + "\n" + getHelp();
                    }
            }
        }

        return getAllThreads();
    }

    private String getTopCpuThreads(int topN, long intervalMs) {
        if (!threadMXBean.isThreadCpuTimeSupported()) {
            return "Thread CPU time is not supported on this JVM.";
        }
        if (!threadMXBean.isThreadCpuTimeEnabled()) {
            try {
                threadMXBean.setThreadCpuTimeEnabled(true);
            } catch (Exception ignored) {
                // best-effort
            }
        }

        long[] ids = threadMXBean.getAllThreadIds();
        Map<Long, Long> before = new HashMap<>();
        for (long id : ids) {
            long v = threadMXBean.getThreadCpuTime(id);
            if (v >= 0) {
                before.put(id, v);
            }
        }

        try {
            Thread.sleep(Math.max(1, intervalMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while sampling threads.";
        }

        long[] ids2 = threadMXBean.getAllThreadIds();
        Map<Long, Long> delta = new HashMap<>();
        for (long id : ids2) {
            long after = threadMXBean.getThreadCpuTime(id);
            Long b = before.get(id);
            if (after >= 0 && b != null) {
                long d = Math.max(0, after - b);
                delta.put(id, d);
            }
        }

        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(delta.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        StringBuilder sb = new StringBuilder();
        sb.append("=== Thread Top CPU (delta) ===\n");
        sb.append("Interval: ").append(intervalMs).append(" ms\n\n");
        sb.append(String.format("%-5s %-12s %-12s %-15s %s\n", "ID", "CPU_DELTA", "CPU_TOTAL", "STATE", "NAME"));
        sb.append(StringUtils.repeat('=', 80)).append("\n");

        int shown = 0;
        for (Map.Entry<Long, Long> e : sorted) {
            if (shown >= topN) {
                break;
            }
            long id = e.getKey();
            long dNs = e.getValue();
            long totalNs = threadMXBean.getThreadCpuTime(id);
            ThreadInfo info = threadMXBean.getThreadInfo(id);
            String state = info != null ? info.getThreadState().toString() : "N/A";
            String name = info != null ? info.getThreadName() : "N/A";

            sb.append(String.format("%-5d %-12s %-12s %-15s %s\n",
                id,
                (dNs / 1_000_000) + "ms",
                (totalNs >= 0 ? (totalNs / 1_000_000) + "ms" : "N/A"),
                state,
                truncate(name, 40)
            ));
            shown++;
        }

        if (shown == 0) {
            sb.append("No threads with CPU time.\n");
        }
        return sb.toString().trim();
    }

    private String getAllThreads() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Thread Information ===\n");

        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds());
        Arrays.sort(threadInfos, new Comparator<ThreadInfo>() {
            @Override
            public int compare(ThreadInfo t1, ThreadInfo t2) {
                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return -1;
                return Long.compare(t1.getThreadId(), t2.getThreadId());
            }
        });

        sb.append(String.format("%-5s %-15s %-10s %-30s %s\n",
            "ID", "STATE", "PRIORITY", "NAME", "CPU TIME"));
        sb.append(StringUtils.repeat('=', 80)).append("\n");

        for (ThreadInfo info : threadInfos) {
            if (info != null) {
                long cpuTime = threadMXBean.isThreadCpuTimeSupported() ?
                    threadMXBean.getThreadCpuTime(info.getThreadId()) / 1_000_000 : -1;

                sb.append(String.format("%-5d %-15s %-10s %-30s %s\n",
                    info.getThreadId(),
                    info.getThreadState().toString(),
                    "N/A",
                    truncate(info.getThreadName(), 30),
                    cpuTime >= 0 ? cpuTime + "ms" : "N/A"));
            }
        }

        sb.append("\nSummary:\n");
        sb.append("Total threads: ").append(threadMXBean.getThreadCount()).append("\n");
        sb.append("Peak threads: ").append(threadMXBean.getPeakThreadCount()).append("\n");
        sb.append("Daemon threads: ").append(threadMXBean.getDaemonThreadCount()).append("\n");

        return sb.toString();
    }

    private String getThreadDetails(long threadId) {
        ThreadInfo info = threadMXBean.getThreadInfo(threadId, Integer.MAX_VALUE);
        if (info == null) {
            return "Thread with ID " + threadId + " not found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Thread Details ===\n");
        sb.append("ID: ").append(info.getThreadId()).append("\n");
        sb.append("Name: ").append(info.getThreadName()).append("\n");
        sb.append("State: ").append(info.getThreadState()).append("\n");

        if (threadMXBean.isThreadCpuTimeSupported()) {
            long cpuTime = threadMXBean.getThreadCpuTime(threadId);
            sb.append("CPU Time: ").append(cpuTime / 1_000_000).append(" ms\n");
        }

        sb.append("Blocked Time: ").append(info.getBlockedTime()).append(" ms\n");
        sb.append("Blocked Count: ").append(info.getBlockedCount()).append("\n");
        sb.append("Waited Time: ").append(info.getWaitedTime()).append(" ms\n");
        sb.append("Waited Count: ").append(info.getWaitedCount()).append("\n");

        if (info.getLockInfo() != null) {
            sb.append("Lock Info: ").append(info.getLockInfo()).append("\n");
        }

        if (info.getLockName() != null) {
            sb.append("Lock Name: ").append(info.getLockName()).append("\n");
        }

        if (info.getLockOwnerName() != null) {
            sb.append("Lock Owner: ").append(info.getLockOwnerName())
              .append(" (ID: ").append(info.getLockOwnerId()).append(")\n");
        }

        sb.append("\nStack Trace:\n");
        StackTraceElement[] stackTrace = info.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            sb.append("  at ").append(element.toString()).append("\n");
        }

        return sb.toString();
    }

    private String getBlockedThreads() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Blocked Threads ===\n");

        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds());
        boolean foundBlocked = false;

        for (ThreadInfo info : threadInfos) {
            if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
                foundBlocked = true;
                sb.append("Thread ID: ").append(info.getThreadId()).append("\n");
                sb.append("Name: ").append(info.getThreadName()).append("\n");
                sb.append("Blocked Count: ").append(info.getBlockedCount()).append("\n");
                if (info.getLockOwnerName() != null) {
                    sb.append("Blocked on lock owned by: ").append(info.getLockOwnerName())
                      .append(" (ID: ").append(info.getLockOwnerId()).append(")\n");
                }
                sb.append("\n");
            }
        }

        if (!foundBlocked) {
            sb.append("No blocked threads found\n");
        }

        return sb.toString();
    }

    private String getDeadlockInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Deadlock Detection ===\n");

        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        if (deadlockedThreads == null || deadlockedThreads.length == 0) {
            sb.append("No deadlocks detected\n");
            return sb.toString();
        }

        sb.append("DEADLOCK DETECTED!\n");
        sb.append("Deadlocked threads: ").append(deadlockedThreads.length).append("\n\n");

        ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(deadlockedThreads, Integer.MAX_VALUE);
        for (ThreadInfo info : threadInfos) {
            if (info != null) {
                sb.append("Thread: ").append(info.getThreadName())
                  .append(" (ID: ").append(info.getThreadId()).append(")\n");
                sb.append("State: ").append(info.getThreadState()).append("\n");

                if (info.getLockInfo() != null) {
                    sb.append("Waiting for lock: ").append(info.getLockInfo()).append("\n");
                }

                if (info.getLockOwnerName() != null) {
                    sb.append("Lock owned by: ").append(info.getLockOwnerName())
                      .append(" (ID: ").append(info.getLockOwnerId()).append(")\n");
                }

                sb.append("Stack trace:\n");
                for (StackTraceElement element : info.getStackTrace()) {
                    sb.append("  ").append(element.toString()).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String getHelp() {
        return "Thread command usage:\n" +
               "  thread              - Show all threads\n" +
               "  thread <id>         - Show details for specific thread ID\n" +
               "  thread -n <N> -i <ms> - Show top N threads by CPU delta over interval\n" +
               "  thread -b|--blocked - Show only blocked threads\n" +
               "  thread -d|--deadlock- Show deadlock information\n" +
               "  thread -h|--help    - Show this help\n";
    }

    private int parseInt(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(String v, long def) {
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength - 3) + "...";
    }

    @Override
    public String getDescription() {
        return "Display thread information and detect deadlocks";
    }
}
