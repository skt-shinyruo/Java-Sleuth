package com.javasleuth.command;

import com.javasleuth.config.ProductionConfig;
import com.javasleuth.command.session.ClientDisconnectedException;
import com.javasleuth.command.pipeline.CacheInterceptor;
import com.javasleuth.command.pipeline.CommandExecutionEngine;
import com.javasleuth.command.pipeline.GuardedStreamInterceptor;
import com.javasleuth.command.pipeline.PipelineChain;
import com.javasleuth.command.pipeline.PrecheckDecision;
import com.javasleuth.command.pipeline.PrecheckPipeline;
import com.javasleuth.command.pipeline.OutputSanitizeInterceptor;
import com.javasleuth.command.pipeline.StreamInvocation;
import com.javasleuth.command.pipeline.SyncInvocation;
import com.javasleuth.security.AuthorizationManager;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.InputValidator;
import com.javasleuth.util.SleuthLogger;
import java.util.Arrays;
import java.util.Locale;

public class CommandPipeline {
    public static class PrecheckResult {
        private final boolean ok;
        private final String error;
        private final String[] args;

        private PrecheckResult(boolean ok, String error, String[] args) {
            this.ok = ok;
            this.error = error;
            this.args = args;
        }

        public static PrecheckResult ok(String[] args) {
            return new PrecheckResult(true, null, args);
        }

        public static PrecheckResult denied(String error) {
            return new PrecheckResult(false, error, null);
        }

        public static PrecheckResult denied(String error, String[] args) {
            return new PrecheckResult(false, error, args);
        }

        public boolean isOk() {
            return ok;
        }

        public String getError() {
            return error;
        }

        public String[] getArgs() {
            return args;
        }
    }

    public static class Result {
        private final boolean success;
        private final String output;
        private final String error;

        public Result(boolean success, String output, String error) {
            this.success = success;
            this.output = output;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getError() {
            return error;
        }
    }

    public static class StreamResult {
        private final boolean success;
        private final String error;

        public StreamResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }

        public static StreamResult ok() {
            return new StreamResult(true, null);
        }

        public static StreamResult failed(String error) {
            return new StreamResult(false, error);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getError() {
            return error;
        }
    }

    private final InputValidator inputValidator;
    private final ProductionConfig config;

    private final PrecheckPipeline precheckPipeline;
    private final CommandExecutionEngine executionEngine;
    private final PipelineChain<SyncInvocation, String> syncChain;
    private final PipelineChain<StreamInvocation, StreamResult> streamChain;

    public CommandPipeline(InputValidator inputValidator, AuthorizationManager authorizationManager, ProductionConfig config) {
        this(inputValidator, authorizationManager, DangerousCommandConfirmationManager.getInstance(), config);
    }

    public CommandPipeline(
        InputValidator inputValidator,
        AuthorizationManager authorizationManager,
        DangerousCommandConfirmationManager dangerousConfirm,
        ProductionConfig config
    ) {
        this.inputValidator = inputValidator;
        this.config = config;
        this.precheckPipeline = new PrecheckPipeline(inputValidator, authorizationManager, dangerousConfirm, config);
        this.executionEngine = new CommandExecutionEngine(config);

        this.syncChain = PipelineChain.of(
            inv -> executionEngine.executeSync(inv.getCommand(), inv.getArgs(), inv.getMeta(), inv.getTimeoutMs(), inv.getContext()),
            Arrays.asList(
                new OutputSanitizeInterceptor(inputValidator),
                new CacheInterceptor()
            )
        );

        this.streamChain = PipelineChain.of(
            inv -> {
                executionEngine.executeStream(inv.getCommand(), inv.getArgs(), inv.getMeta(), inv.getTimeoutMs(), inv.getSink(), inv.getContext());
                return StreamResult.ok();
            },
            Arrays.asList(new GuardedStreamInterceptor(inputValidator))
        );
    }

    public void shutdown() {
        try {
            executionEngine.shutdown();
        } catch (Exception ignore) {
            // ignore
        }
    }

    public Result execute(CommandRegistry.Entry entry, String[] args, CommandContext context) {
        String commandName = args[0].toLowerCase(Locale.ROOT);
        PrecheckResult precheck = precheck(entry, commandName, args, context);
        if (!precheck.isOk()) {
            return new Result(false, null, precheck.getError());
        }
        return executePrechecked(entry, precheck.getArgs(), context);
    }

    public Result executePrechecked(CommandRegistry.Entry entry, String[] args, CommandContext context) {
        if (args == null || args.length == 0) {
            return new Result(false, null, "Empty command");
        }
        String commandName = args[0].toLowerCase(Locale.ROOT);
        if (entry == null || entry.getCommand() == null) {
            return new Result(false, null, "Unknown command: " + commandName);
        }

        try {
            long timeoutMs = config.getCommandTimeout();
            SyncInvocation inv = new SyncInvocation(entry, entry.getCommand(), entry.getMeta(), commandName, args, context, timeoutMs);
            String output = syncChain.handle(inv);
            return new Result(true, output, null);
        } catch (Exception e) {
            String errorId = CommandErrorMapper.newErrorId();
            SleuthLogger.error("Command execution failed (errorId=" + errorId + ")", e);
            String userError = CommandErrorMapper.toUserMessage(e, errorId, context);
            return new Result(false, null, userError);
        }
    }

    public String validateAndAuthorize(CommandRegistry.Entry entry, String commandName, String[] args, CommandContext context) {
        PrecheckResult pre = precheck(entry, commandName, args, context);
        return pre.isOk() ? null : pre.getError();
    }

    public PrecheckResult precheck(CommandRegistry.Entry entry, String commandName, String[] args, CommandContext context) {
        PrecheckDecision decision = precheckPipeline.precheck(entry, commandName, args, context);
        if (decision != null && decision.isOk()) {
            return PrecheckResult.ok(decision.getArgs());
        }
        String error = decision != null ? decision.getError() : "Command denied";
        String[] outArgs = decision != null ? decision.getArgs() : args;
        return PrecheckResult.denied(error, outArgs);
    }

    public StreamResult executeStreamPrechecked(CommandRegistry.Entry entry, String[] args, CommandContext context, StreamSink sink) throws Exception {
        if (args == null || args.length == 0) {
            return StreamResult.failed("Empty command");
        }
        Command command = entry != null ? entry.getCommand() : null;
        if (!(command instanceof StreamCommand)) {
            return StreamResult.failed("Command is not streamable");
        }

        String commandName = args[0].toLowerCase(Locale.ROOT);
        long timeoutMs = config.getCommandTimeout();
        StreamInvocation inv = new StreamInvocation(entry, (StreamCommand) command, entry != null ? entry.getMeta() : null, commandName, args, context, timeoutMs, sink);
        try {
            return streamChain.handle(inv);
        } catch (ClientDisconnectedException e) {
            throw e;
        }
    }
}
