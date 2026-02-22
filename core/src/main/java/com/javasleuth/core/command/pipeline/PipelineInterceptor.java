package com.javasleuth.core.command.pipeline;

@FunctionalInterface
public interface PipelineInterceptor<C, R> {
    R intercept(C context, PipelineHandler<C, R> next) throws Exception;
}
