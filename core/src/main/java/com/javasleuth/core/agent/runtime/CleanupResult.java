package com.javasleuth.core.agent.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CleanupResult {
    public enum StepStatus {
        SUCCESS,
        FAILED,
        SKIPPED
    }

    public static final class Step {
        private final String name;
        private final StepStatus status;
        private final String detail;

        private Step(String name, StepStatus status, String detail) {
            this.name = sanitize(name);
            this.status = status != null ? status : StepStatus.FAILED;
            this.detail = sanitize(detail);
        }

        public String getName() {
            return name;
        }

        public StepStatus getStatus() {
            return status;
        }

        public String getDetail() {
            return detail;
        }

        public boolean isFailure() {
            return status == StepStatus.FAILED;
        }

        private String format() {
            if (detail == null || detail.isEmpty()) {
                return name + "=" + status.name();
            }
            return name + "=" + status.name() + " (" + detail + ")";
        }
    }

    public static final class Builder {
        private final String scope;
        private final String reason;
        private final long startedAtMs;
        private final ArrayList<Step> steps = new ArrayList<Step>();

        private Builder(String scope, String reason) {
            this.scope = sanitize(scope);
            this.reason = sanitize(reason);
            this.startedAtMs = System.currentTimeMillis();
        }

        public Builder success(String name) {
            steps.add(new Step(name, StepStatus.SUCCESS, null));
            return this;
        }

        public Builder skipped(String name, String detail) {
            steps.add(new Step(name, StepStatus.SKIPPED, detail));
            return this;
        }

        public Builder failure(String name, Throwable t) {
            return failure(name, describeThrowable(t));
        }

        public Builder failure(String name, String detail) {
            steps.add(new Step(name, StepStatus.FAILED, detail));
            return this;
        }

        public CleanupResult build() {
            return new CleanupResult(scope, reason, startedAtMs, System.currentTimeMillis(), steps);
        }
    }

    private final String scope;
    private final String reason;
    private final long startedAtMs;
    private final long endedAtMs;
    private final List<Step> steps;
    private final int succeeded;
    private final int failed;
    private final int skipped;

    private CleanupResult(String scope, String reason, long startedAtMs, long endedAtMs, List<Step> steps) {
        this.scope = sanitize(scope);
        this.reason = sanitize(reason);
        this.startedAtMs = startedAtMs;
        this.endedAtMs = endedAtMs;
        ArrayList<Step> copy = new ArrayList<Step>();
        int ok = 0;
        int bad = 0;
        int skip = 0;
        if (steps != null) {
            for (Step step : steps) {
                if (step == null) {
                    continue;
                }
                copy.add(step);
                if (step.status == StepStatus.SUCCESS) {
                    ok++;
                } else if (step.status == StepStatus.SKIPPED) {
                    skip++;
                } else {
                    bad++;
                }
            }
        }
        this.steps = Collections.unmodifiableList(copy);
        this.succeeded = ok;
        this.failed = bad;
        this.skipped = skip;
    }

    public static Builder builder(String scope, String reason) {
        return new Builder(scope, reason);
    }

    public String getScope() {
        return scope;
    }

    public String getReason() {
        return reason;
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public long getEndedAtMs() {
        return endedAtMs;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public int getSucceeded() {
        return succeeded;
    }

    public int getFailed() {
        return failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public boolean isDegraded() {
        return failed > 0;
    }

    public String getStatusName() {
        return isDegraded() ? "PARTIAL" : "OK";
    }

    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(scope != null && !scope.isEmpty() ? scope : "cleanup")
            .append(" ")
            .append(getStatusName())
            .append(" (reason=")
            .append(reason != null && !reason.isEmpty() ? reason : "unknown")
            .append(", succeeded=")
            .append(succeeded)
            .append(", failed=")
            .append(failed)
            .append(", skipped=")
            .append(skipped)
            .append(")");
        if (isDegraded()) {
            sb.append(": ").append(formatFailures());
        }
        return sb.toString();
    }

    public String formatFailures() {
        StringBuilder sb = new StringBuilder();
        for (Step step : steps) {
            if (step == null || !step.isFailure()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(step.format());
        }
        return sb.toString();
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String msg = t.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return t.getClass().getName();
        }
        return t.getClass().getName() + ": " + sanitize(msg);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
