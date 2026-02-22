package com.javasleuth.core.command.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 轻量的 Interceptor 链编排器（Java 8）。
 *
 * <p>用于将原本隐式的执行流程显式化，提升可测试性与可扩展性。</p>
 */
public final class PipelineChain<C, R> implements PipelineHandler<C, R> {
    private final PipelineHandler<C, R> handler;

    private PipelineChain(PipelineHandler<C, R> handler) {
        this.handler = handler;
    }

    public static <C, R> PipelineChain<C, R> of(
        PipelineHandler<C, R> terminal,
        List<? extends PipelineInterceptor<C, R>> interceptors
    ) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal handler is null");
        }
        List<? extends PipelineInterceptor<C, R>> safe = interceptors != null ? interceptors : Collections.emptyList();
        List<PipelineInterceptor<C, R>> list = new ArrayList<>(safe.size());
        list.addAll(safe);

        PipelineHandler<C, R> composed = terminal;
        for (int i = list.size() - 1; i >= 0; i--) {
            PipelineInterceptor<C, R> interceptor = list.get(i);
            PipelineHandler<C, R> next = composed;
            composed = ctx -> interceptor.intercept(ctx, next);
        }
        return new PipelineChain<>(composed);
    }

    @Override
    public R handle(C context) throws Exception {
        return handler.handle(context);
    }
}
