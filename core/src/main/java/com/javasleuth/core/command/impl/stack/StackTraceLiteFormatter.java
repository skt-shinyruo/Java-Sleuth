package com.javasleuth.core.command.impl.stack;

import com.javasleuth.bootstrap.data.StackTraceResult;
import com.javasleuth.foundation.util.LoadedClassResolver;
import java.time.LocalTime;
import java.util.List;

final class StackTraceLiteFormatter {
    private StackTraceLiteFormatter() {}

    static String buildBanner(
        String classPattern,
        String methodPattern,
        String stackId,
        int maxCount,
        long timeoutSeconds,
        int depth,
        List<LoadedClassResolver.Candidate> targets
    ) {
        StringBuilder banner = new StringBuilder();
        banner.append("Started stack trace ").append(classPattern).append(".").append(methodPattern).append("\n");
        banner.append("Stack ID: ").append(stackId).append("\n");
        banner.append("Classes instrumented: ").append(targets != null ? targets.size() : 0).append("\n");
        appendTargetSummary(banner, targets, 5);
        banner.append("Max events: ").append(maxCount)
            .append(", Timeout: ").append(timeoutSeconds).append("s")
            .append(", Depth: ").append(depth)
            .append("\n");
        return banner.toString().trim();
    }

    private static void appendTargetSummary(StringBuilder out, List<LoadedClassResolver.Candidate> targets, int maxLines) {
        if (targets == null || targets.isEmpty()) {
            return;
        }
        int limit = maxLines <= 0 ? 5 : maxLines;
        int shown = 0;
        for (LoadedClassResolver.Candidate target : targets) {
            if (target == null || shown >= limit) {
                continue;
            }
            out.append("  - ").append(target.getClassName())
                .append(" (loaderId=").append(LoadedClassResolver.formatLoaderId(target.getLoaderId())).append(")")
                .append("\n");
            shown++;
        }
        if (targets.size() > shown) {
            out.append("  ... ").append(targets.size() - shown).append(" more\n");
        }
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
