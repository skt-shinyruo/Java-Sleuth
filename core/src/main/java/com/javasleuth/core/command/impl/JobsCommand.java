package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.JobManager;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.foundation.security.CommandMeta;
import java.time.Instant;
import java.util.List;

public class JobsCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("jobs")
        .description("Manage background jobs")
        .usage("jobs [list|tail|stop]")
        .meta(CommandMeta.operator(true, false))
        .subcommand(SubcommandSpec.of(
            "list",
            "List background jobs",
            CommandSpec.builder("list")
                .description("List background jobs")
                .usage("jobs list")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "tail",
            "Show recent job output",
            CommandSpec.builder("tail")
                .description("Show recent job output")
                .usage("jobs tail <job-id> [n] [--lines <int>]")
                .argument(ArgumentSpec.required("job-id"))
                .argument(ArgumentSpec.optional("n"))
                .option(OptionSpec.integer("lines").alias("--lines").range(1, 100000).build())
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "stop",
            "Stop a background job",
            CommandSpec.builder("stop")
                .description("Stop a background job")
                .usage("jobs stop <job-id>")
                .argument(ArgumentSpec.required("job-id"))
                .build()
        ))
        .example("jobs")
        .example("jobs tail job-1 100")
        .example("jobs stop job-1")
        .build();

    private final JobManager jobManager;

    public JobsCommand(JobManager jobManager) {
        if (jobManager == null) {
            throw new IllegalArgumentException("jobManager");
        }
        this.jobManager = jobManager;
    }

    public static CommandSpec spec() {
        return SPEC;
    }

    @Override
    public CommandSpec getSpec() {
        return SPEC;
    }

    @Override
    public String execute(String[] args) {
        ParsedCommand parsed = CommandSpecSupport.parsed(SPEC, args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(SPEC);
        }

        String action = parsed.subcommandName();
        if (action == null) {
            return list();
        }

        switch (action) {
            case "list":
                return list();
            case "tail":
                return tail(parsed);
            case "stop":
                return stop(parsed);
            default:
                return CommandHelpRenderer.render(SPEC);
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

    private String tail(ParsedCommand parsed) {
        jobManager.evictExpired();
        String jobId = parsed.argument("job-id");
        int n = CommandSpecSupport.intOptionOrArgument(parsed, "lines", "n", 50);
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

    private String stop(ParsedCommand parsed) {
        String jobId = parsed.argument("job-id");
        boolean ok = jobManager.stop(jobId);
        if (!ok) {
            return "Job not found: " + jobId;
        }
        return "Stop requested for job: " + jobId;
    }

    @Override
    public String getDescription() {
        return "Manage background jobs (list/tail/stop)";
    }
}
