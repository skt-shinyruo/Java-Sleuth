package com.javasleuth.core.command.impl.stack;

import com.javasleuth.bootstrap.data.StackTraceResult;
import java.time.LocalTime;

final class StackTraceLiteFormatter {
    private StackTraceLiteFormatter() {}

    static String buildBanner(
        Class<?> target,
        String methodPattern,
        String stackId,
        int maxCount,
        long timeoutSeconds,
        int depth
    ) {
        StringBuilder banner = new StringBuilder();
        banner.append("Started stack trace ").append(target.getName()).append(".").append(methodPattern).append("\n");
        banner.append("Stack ID: ").append(stackId).append("\n");
        banner.append("Max events: ").append(maxCount)
            .append(", Timeout: ").append(timeoutSeconds).append("s")
            .append(", Depth: ").append(depth)
            .append("\n");
        return banner.toString().trim();
    }

    static String formatResult(StackTraceResult r, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", idx));
        sb.append(String.format("%s ", LocalTime.now().toString()));
        sb.append(r.getClassName()).append(".").append(r.getMethodName());
        sb.append(" [").append(r.getThreadName()).append("#").append(r.getThreadId()).append("]");
        sb.append("\n");

        StackTraceElement[] st = r.getStackTrace();
        if (st != null) {
            for (StackTraceElement e : st) {
                if (e == null) {
                    continue;
                }
                sb.append("    at ").append(e.toString()).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
