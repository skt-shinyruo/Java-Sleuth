package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.JobManager;
import java.time.Instant;
import java.util.List;

public class JobsCommand implements Command {
    private final JobManager jobManager = JobManager.getInstance();

    @Override
    public String execute(String[] args) {
        if (args == null || args.length == 1) {
            return list();
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "list":
                return list();
            case "tail":
                return tail(args);
            case "stop":
                return stop(args);
            default:
                return "Unknown jobs action: " + action + "\n" + getHelp();
        }
    }

    private String list() {
        jobManager.evictExpired();
        List<JobManager.JobInfo> jobs = jobManager.list();
        if (jobs.isEmpty()) {
            return "No jobs.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ID\tSTATUS\tSTART\tNAME\tCOMMAND\n");
        for (JobManager.JobInfo j : jobs) {
            sb.append(j.getId()).append("\t")
                .append(j.getStatus()).append("\t")
                .append(Instant.ofEpochMilli(j.getStartEpochMs())).append("\t")
                .append(j.getName()).append("\t")
                .append(j.getCommandLine())
                .append("\n");
        }
        return sb.toString().trim();
    }

    private String tail(String[] args) {
        jobManager.evictExpired();
        if (args.length < 3) {
            return "Usage: jobs tail <job-id> [n]";
        }
        String jobId = args[2];
        int n = 50;
        if (args.length >= 4) {
            try {
                n = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {
                n = 50;
            }
        }
        List<String> lines = jobManager.tail(jobId, n);
        if (lines.isEmpty()) {
            return "No output for job: " + jobId;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    private String stop(String[] args) {
        if (args.length < 3) {
            return "Usage: jobs stop <job-id>";
        }
        String jobId = args[2];
        boolean ok = jobManager.stop(jobId);
        if (!ok) {
            return "Job not found: " + jobId;
        }
        return "Stop requested for job: " + jobId;
    }

    private String getHelp() {
        return "Jobs command usage:\n" +
            "  jobs | jobs list\n" +
            "  jobs tail <job-id> [n]\n" +
            "  jobs stop <job-id>\n";
    }

    @Override
    public String getDescription() {
        return "Manage background jobs (list/tail/stop)";
    }
}

