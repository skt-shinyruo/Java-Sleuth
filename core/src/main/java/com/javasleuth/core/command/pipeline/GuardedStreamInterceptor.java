package com.javasleuth.core.command.pipeline;

import com.javasleuth.core.command.CommandPipeline;
import com.javasleuth.core.command.session.ClientDisconnectedException;
import com.javasleuth.foundation.security.InputValidator;

public final class GuardedStreamInterceptor implements PipelineInterceptor<StreamInvocation, CommandPipeline.StreamResult> {
    private final InputValidator inputValidator;

    public GuardedStreamInterceptor(InputValidator inputValidator) {
        this.inputValidator = inputValidator;
    }

    @Override
    public CommandPipeline.StreamResult intercept(
        StreamInvocation ctx,
        PipelineHandler<StreamInvocation, CommandPipeline.StreamResult> next
    ) throws Exception {
        if (ctx == null) {
            return next.handle(null);
        }

        GuardedStreamSink guarded = new GuardedStreamSink(ctx.getSink(), inputValidator);
        StreamInvocation wrapped = ctx.withSink(guarded);

        try {
            CommandPipeline.StreamResult r = next.handle(wrapped);
            guarded.ensureClosed();
            return r != null ? r : CommandPipeline.StreamResult.ok();
        } catch (ClientDisconnectedException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : "Command execution failed";
            guarded.error(message);
            return CommandPipeline.StreamResult.failed(message);
        }
    }
}
