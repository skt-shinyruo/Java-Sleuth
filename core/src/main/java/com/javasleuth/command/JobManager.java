package com.javasleuth.command;

import com.javasleuth.util.RingBuffer;
import com.javasleuth.util.SleuthExecutors;
import com.javasleuth.util.SleuthLogContext;
import com.javasleuth.util.SleuthThreadFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    private final AtomicLong seq = new AtomicLong(1);
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    // Retention (best-effort) to limit memory.
    private volatile int maxJobs = 200;
    private volatile long jobTtlMs = 60L * 60L * 1000L; // 1 hour
    private volatile int maxOutputBytesPerJob = 256 * 1024; // 256 KiB

    // Execution limits (hard cap on concurrency + bounded queue).
    private volatile int maxRunning = 4;
    private volatile int queueCapacity = 20;
    private final Object executorLock = new Object();
    private volatile ThreadPoolExecutor executor;

    public JobManager() {}

    public void configureRetention(int maxJobs, long jobTtlMs, int maxOutputBytesPerJob) {
        if (maxJobs > 0) {
            this.maxJobs = maxJobs;
        }
        if (jobTtlMs > 0) {
            this.jobTtlMs = jobTtlMs;
        }
        if (maxOutputBytesPerJob > 0) {
            this.maxOutputBytesPerJob = maxOutputBytesPerJob;
        }
    }

    public void configureExecution(int maxRunning, int queueCapacity) {
        if (maxRunning > 0) {
            this.maxRunning = maxRunning;
        }
        if (queueCapacity > 0) {
            this.queueCapacity = queueCapacity;
        }
        synchronized (executorLock) {
            ThreadPoolExecutor ex = executor;
            if (ex == null) {
                ensureExecutor();
                return;
            }

            int mr = Math.max(1, Math.min(this.maxRunning, 64));
            int qc = Math.max(1, Math.min(this.queueCapacity, 10000));
            int oldQc = -1;
            try {
                BlockingQueue<Runnable> q = ex.getQueue();
                if (q != null) {
                    oldQc = q.size() + q.remainingCapacity();
                }
            } catch (Exception ignore) {
                oldQc = -1;
            }

            boolean needsRebuild = ex.getCorePoolSize() != mr || oldQc != -1 && oldQc != qc;
            if (!needsRebuild) {
                return;
            }

            // 尝试“无任务时”重建（队列容量无法动态变更）。
            boolean idle = false;
            try {
                idle = ex.getActiveCount() == 0 && (ex.getQueue() == null || ex.getQueue().isEmpty());
            } catch (Exception ignore) {
                idle = false;
            }
            if (idle) {
                ThreadPoolExecutor old = ex;
                executor = null;
                try {
                    old.shutdownNow();
                } catch (Exception ignore) {
                    // ignore
                }
                ensureExecutor();
                return;
            }

            // 有运行中的任务：仅调整线程数上限，队列容量保持不变。
            try {
                ex.setCorePoolSize(mr);
                ex.setMaximumPoolSize(mr);
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private ThreadPoolExecutor ensureExecutor() {
        ThreadPoolExecutor ex = executor;
        if (ex != null && !ex.isShutdown() && !ex.isTerminated()) {
            return ex;
        }
        synchronized (executorLock) {
            if (executor != null && !executor.isShutdown() && !executor.isTerminated()) {
                return executor;
            }
            // Recreate after shutdown (supports detach → re-attach in the same JVM).
            executor = null;
            int mr = Math.max(1, Math.min(maxRunning, 64));
            int qc = Math.max(1, Math.min(queueCapacity, 10000));
            BlockingQueue<Runnable> q = new LinkedBlockingQueue<>(qc);
            ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                mr,
                mr,
                60L,
                TimeUnit.SECONDS,
                q,
                SleuthThreadFactory.daemon("sleuth-job-worker"),
                new ThreadPoolExecutor.AbortPolicy()
            );
            tpe.allowCoreThreadTimeOut(true);
            executor = tpe;
            return tpe;
        }
    }

    public void evictExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Job> e : jobs.entrySet()) {
            Job j = e.getValue();
            if (j == null) {
                continue;
            }
            if (j.status == JobStatus.RUNNING) {
                continue;
            }
            Long end = j.endEpochMs;
            long base = end != null ? end : j.startEpochMs;
            if (jobTtlMs > 0 && now - base > jobTtlMs) {
                jobs.remove(e.getKey());
            }
        }

        // Cap total jobs (prefer dropping oldest completed jobs).
        if (maxJobs > 0 && jobs.size() > maxJobs) {
            List<Job> all = new ArrayList<>(jobs.values());
            all.sort((a, b) -> {
                long ta = a.endEpochMs != null ? a.endEpochMs : a.startEpochMs;
                long tb = b.endEpochMs != null ? b.endEpochMs : b.startEpochMs;
                return Long.compare(ta, tb);
            });
            int toRemove = jobs.size() - maxJobs;
            for (Job j : all) {
                if (toRemove <= 0) {
                    break;
                }
                if (j.status == JobStatus.RUNNING) {
                    continue;
                }
                jobs.remove(j.id);
                toRemove--;
            }
        }
    }

    public String submitStreamJob(String name, String commandLine, StreamJob job) {
        Objects.requireNonNull(job, "job");
        evictExpired();

        ThreadPoolExecutor ex = ensureExecutor();
        String id = String.format(Locale.ROOT, "job-%d", seq.getAndIncrement());
        Job j = new Job(id, name, commandLine, maxOutputBytesPerJob);
        jobs.put(id, j);

        final CommandContext capturedContext = CommandContextHolder.get();
        final String capturedClientId = capturedContext != null ? capturedContext.getClientId() : null;
        final String capturedSessionId = capturedContext != null ? capturedContext.getSessionId() : null;
        final String capturedConnId = capturedContext != null ? capturedContext.getConnId() : null;
        final String capturedCommand = capturedContext != null ? capturedContext.getCommandName() : null;
        final String logCommand = capturedCommand != null ? capturedCommand : (name == null ? null : name.trim());

        try {
            Future<?> f = ex.submit(() -> {
                j.thread = Thread.currentThread();
                try {
                    try {
                        if (capturedContext != null) {
                            CommandContextHolder.set(capturedContext);
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    try {
                        if (capturedClientId != null || capturedSessionId != null || capturedConnId != null) {
                            SleuthLogContext.setConnection(capturedClientId, capturedSessionId, capturedConnId);
                        }
                        if (logCommand != null && !logCommand.isEmpty()) {
                            SleuthLogContext.setCommand(logCommand);
                        }
                    } catch (Exception ignore) {
                        // ignore
                    }
                    runJob(j, job);
                } finally {
                    try {
                        CommandContextHolder.clear();
                    } catch (Exception ignore) {
                        // ignore
                    }
                    try {
                        SleuthLogContext.clear();
                    } catch (Exception ignore) {
                        // ignore
                    }
                    j.thread = null;
                }
            });
            j.future = f;
        } catch (RejectedExecutionException rejected) {
            jobs.remove(id);
            throw new RejectedExecutionException("Too many background jobs running/queued: maxRunning=" +
                Math.max(1, maxRunning) + ", queueCapacity=" + Math.max(1, queueCapacity));
        }
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
        Future<?> f = j.future;
        if (f != null) {
            try {
                f.cancel(true);
            } catch (Exception ignore) {
                // ignore
            }
        }
        Thread t = j.thread;
        if (t != null) {
            t.interrupt();
        } else {
            // Not started yet (still in queue) or already finished.
            if (f != null && f.isCancelled() && j.status == JobStatus.RUNNING) {
                j.stop();
            }
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

    public void shutdown(String reason) {
        stopAll(reason == null ? "shutdown" : reason);

        ThreadPoolExecutor ex;
        synchronized (executorLock) {
            ex = executor;
            executor = null;
        }
        SleuthExecutors.shutdownAndAwait(ex, "job-manager", 5, TimeUnit.SECONDS);
        jobs.clear();
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
        private final int maxOutputBytes;
        private final AtomicLong outputBytes = new AtomicLong(0);
        private volatile Thread thread;
        private volatile Future<?> future;

        private Job(String id, String name, String commandLine, int maxOutputBytes) {
            this.id = id;
            this.name = name == null ? "job" : name;
            this.commandLine = commandLine == null ? "" : commandLine;
            this.maxOutputBytes = Math.max(0, maxOutputBytes);
        }

        private void append(String line) {
            if (line == null) {
                return;
            }
            if (maxOutputBytes > 0 && outputBytes.get() >= maxOutputBytes) {
                return;
            }
            String[] parts = line.split("\n", -1);
            for (String p : parts) {
                if (p == null) {
                    continue;
                }
                String trimmed = p.replace("\r", "");
                if (trimmed.isEmpty()) {
                    continue;
                }
                int bytes = trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                long next = outputBytes.addAndGet(bytes);
                if (maxOutputBytes > 0 && next > maxOutputBytes) {
                    return;
                }
                output.add(trimmed);
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
