package com.javasleuth.command;

import com.javasleuth.util.RingBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process background job manager for long-running diagnostic tasks.
 *
 * <p>Design goals:
 * - No remote execution model; jobs are local to the attached JVM.
 * - Best-effort cancellation via interrupt + cancellation flag.
 * - Tail-able outputs via a ring buffer.
 */
public final class JobManager {
    public enum JobStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        STOPPED
    }

    public interface StreamJob {
        void run(StreamSink sink) throws Exception;
    }

    public static final class JobInfo {
        private final String id;
        private final String name;
        private final String commandLine;
        private final JobStatus status;
        private final long startEpochMs;
        private final Long endEpochMs;
        private final String error;

        private JobInfo(String id, String name, String commandLine, JobStatus status,
                        long startEpochMs, Long endEpochMs, String error) {
            this.id = id;
            this.name = name;
            this.commandLine = commandLine;
            this.status = status;
            this.startEpochMs = startEpochMs;
            this.endEpochMs = endEpochMs;
            this.error = error;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getCommandLine() { return commandLine; }
        public JobStatus getStatus() { return status; }
        public long getStartEpochMs() { return startEpochMs; }
        public Long getEndEpochMs() { return endEpochMs; }
        public String getError() { return error; }
    }

    private static final JobManager INSTANCE = new JobManager();

    public static JobManager getInstance() {
        return INSTANCE;
    }

    private final AtomicLong seq = new AtomicLong(1);
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    private JobManager() {}

    public String submitStreamJob(String name, String commandLine, StreamJob job) {
        Objects.requireNonNull(job, "job");
        String id = String.format(Locale.ROOT, "job-%d", seq.getAndIncrement());
        Job j = new Job(id, name, commandLine);
        jobs.put(id, j);

        Thread t = new Thread(() -> runJob(j, job), "sleuth-job-" + id);
        t.setDaemon(true);
        j.thread = t;
        t.start();
        return id;
    }

    public List<JobInfo> list() {
        if (jobs.isEmpty()) {
            return Collections.emptyList();
        }
        List<JobInfo> out = new ArrayList<>();
        for (Job j : jobs.values()) {
            out.add(j.snapshot());
        }
        out.sort((a, b) -> Long.compare(b.getStartEpochMs(), a.getStartEpochMs()));
        return out;
    }

    public List<String> tail(String jobId, int lines) {
        Job j = jobs.get(jobId);
        if (j == null) {
            return Collections.emptyList();
        }
        return j.output.tail(lines);
    }

    public boolean stop(String jobId) {
        Job j = jobs.get(jobId);
        if (j == null) {
            return false;
        }
        j.cancelled.set(true);
        Thread t = j.thread;
        if (t != null) {
            t.interrupt();
        }
        // status will be updated by job thread
        return true;
    }

    public int stopAll(String reason) {
        int stopped = 0;
        for (String id : jobs.keySet()) {
            boolean ok = stop(id);
            if (ok) {
                stopped++;
                Job j = jobs.get(id);
                if (j != null) {
                    j.append("[jobs] stop requested: " + (reason == null ? "" : reason));
                }
            }
        }
        return stopped;
    }

    private void runJob(Job j, StreamJob job) {
        j.append("[jobs] started at " + Instant.ofEpochMilli(j.startEpochMs));
        StreamSink sink = new JobStreamSink(j);
        try {
            job.run(sink);
            if (!j.cancelled.get()) {
                j.complete(null);
            } else {
                j.stop();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            j.stop();
        } catch (Exception e) {
            j.fail(e);
        } finally {
            j.append("[jobs] end status=" + j.status);
        }
    }

    private static final class Job {
        private final String id;
        private final String name;
        private final String commandLine;
        private final long startEpochMs = System.currentTimeMillis();
        private volatile Long endEpochMs;
        private volatile JobStatus status = JobStatus.RUNNING;
        private volatile String error;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final RingBuffer<String> output = new RingBuffer<>(2000);
        private volatile Thread thread;

        private Job(String id, String name, String commandLine) {
            this.id = id;
            this.name = name == null ? "job" : name;
            this.commandLine = commandLine == null ? "" : commandLine;
        }

        private void append(String line) {
            if (line == null) {
                return;
            }
            String[] parts = line.split("\n", -1);
            for (String p : parts) {
                if (p == null) {
                    continue;
                }
                String trimmed = p.replace("\r", "");
                if (!trimmed.isEmpty()) {
                    output.add(trimmed);
                }
            }
        }

        private void complete(String summary) {
            status = JobStatus.COMPLETED;
            endEpochMs = System.currentTimeMillis();
            if (summary != null && !summary.isEmpty()) {
                append("[jobs] " + summary);
            }
        }

        private void stop() {
            status = JobStatus.STOPPED;
            endEpochMs = System.currentTimeMillis();
        }

        private void fail(Exception e) {
            status = JobStatus.FAILED;
            endEpochMs = System.currentTimeMillis();
            error = e == null ? "unknown" : (e.getClass().getName() + ": " + e.getMessage());
            append("[jobs] failed: " + error);
        }

        private JobInfo snapshot() {
            return new JobInfo(id, name, commandLine, status, startEpochMs, endEpochMs, error);
        }
    }

    private static final class JobStreamSink implements StreamSink {
        private final Job job;

        private JobStreamSink(Job job) {
            this.job = job;
        }

        @Override
        public void send(String data) {
            job.append(data);
        }

        @Override
        public void close(String summary) {
            job.complete(summary);
        }

        @Override
        public void error(String message) {
            job.append("[error] " + message);
        }
    }
}

