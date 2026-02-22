package com.javasleuth.foundation.util;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;

public class JvmUtils {
    private static final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();

    public static String getCurrentJvmPid() {
        String name = runtimeMXBean.getName();
        return name.split("@")[0];
    }

    public static String getCurrentJvmDisplayName() {
        String vmName = runtimeMXBean.getVmName();
        String vmVersion = runtimeMXBean.getVmVersion();
        return vmName + " " + vmVersion;
    }

    public static boolean isJvmArgumentPresent(String argument) {
        return runtimeMXBean.getInputArguments().contains(argument);
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.2f KB", kb);
        }

        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.2f MB", mb);
        }

        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    public static String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %02dh %02dm %02ds", days, hours % 24, minutes % 60, seconds % 60);
        } else if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
