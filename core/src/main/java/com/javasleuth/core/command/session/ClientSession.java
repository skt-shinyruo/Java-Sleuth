package com.javasleuth.core.command.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个客户端会话的生命周期与资源清理容器。
 *
 * <p>目标：把“会话断开后仍继续运行的后台任务/增强/队列”收敛到可被统一关闭的集合。</p>
 */
public class ClientSession {
    private volatile String sessionId;
    private final String clientId;
    private final String clientInfo;
    private final long startEpochMs;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Runnable> cleanupActions = new ConcurrentHashMap<>();

    public ClientSession(String sessionId, String clientId, String clientInfo) {
        this.sessionId = sessionId;
        this.clientId = clientId;
        this.clientInfo = clientInfo;
        this.startEpochMs = System.currentTimeMillis();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientInfo() {
        return clientInfo;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    public void registerCleanup(String key, Runnable action) {
        if (key == null || key.trim().isEmpty() || action == null) {
            return;
        }
        if (closed.get()) {
            try {
                action.run();
            } catch (Exception ignore) {
                // ignore
            }
            return;
        }
        cleanupActions.put(key, action);
    }

    public void removeCleanup(String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }
        cleanupActions.remove(key);
    }

    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Runnable r : cleanupActions.values()) {
            if (r == null) {
                continue;
            }
            try {
                r.run();
            } catch (Exception ignore) {
                // ignore
            }
        }
        cleanupActions.clear();
    }
}
