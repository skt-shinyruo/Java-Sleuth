package com.javasleuth.core.command.pipeline;

import com.javasleuth.core.command.CommandContext;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.PerformanceOptimizer;

public final class CacheInterceptor implements PipelineInterceptor<SyncInvocation, String> {
    @Override
    public String intercept(SyncInvocation ctx, PipelineHandler<SyncInvocation, String> next) throws Exception {
        if (ctx == null) {
            return next.handle(null);
        }

        CommandMeta meta = ctx.getMeta();
        boolean cacheable = meta != null && meta.isCacheable() && CommandCachePolicy.isSafeToCache(ctx.getCommandName(), ctx.getArgs());
        if (!cacheable) {
            return next.handle(ctx);
        }

        CommandContext commandContext = ctx.getContext();
        String clientKey = commandContext != null && commandContext.getClientId() != null ? commandContext.getClientId() : "unknown";
        String cacheKey = clientKey + ":" + ctx.getCommandName() + ":" + String.join(":", ctx.getArgs());

        try {
            return PerformanceOptimizer.getCachedResult(cacheKey, () -> {
                try {
                    return next.handle(ctx);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}
