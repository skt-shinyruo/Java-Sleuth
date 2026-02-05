package com.javasleuth.command.pipeline;

@FunctionalInterface
public interface PipelineHandler<C, R> {
    R handle(C context) throws Exception;
}

