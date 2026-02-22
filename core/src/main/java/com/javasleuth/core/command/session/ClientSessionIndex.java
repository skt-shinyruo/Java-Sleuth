package com.javasleuth.core.command.session;

/**
 * clientId -> sessionId 的轻量索引封装。
 *
 * <p>用于替代在多个组件之间直接共享 ConcurrentHashMap，显式化会话映射边界，降低耦合与穿透。</p>
 */
public final class ClientSessionIndex {
    private final java.util.concurrent.ConcurrentMap<String, String> sessionByClient =
        new java.util.concurrent.ConcurrentHashMap<>();

    public String get(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return null;
        }
        return sessionByClient.get(clientId);
    }

    public String getOrDefault(String clientId, String defaultValue) {
        String v = get(clientId);
        return v != null ? v : defaultValue;
    }

    public void put(String clientId, String sessionId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        sessionByClient.put(clientId, sessionId);
    }

    public String remove(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return null;
        }
        return sessionByClient.remove(clientId);
    }

    public void clear() {
        sessionByClient.clear();
    }
}
