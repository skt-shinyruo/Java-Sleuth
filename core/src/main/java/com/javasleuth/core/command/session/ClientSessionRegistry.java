package com.javasleuth.core.command.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 客户端会话注册表（按 clientId 维度）。
 *
 * <p>该对象作为“断连清理”的统一入口：CommandProcessor 负责创建/关闭，会话内命令可注册清理动作。</p>
 */
public class ClientSessionRegistry {
    private final ConcurrentMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    public ClientSessionRegistry() {}

    public void shutdown(String reason) {
        try {
            for (String clientId : new java.util.ArrayList<>(sessions.keySet())) {
                close(clientId, reason != null ? reason : "shutdown");
            }
        } catch (Exception ignore) {
            // ignore
        } finally {
            try {
                sessions.clear();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    public ClientSession open(String clientId, String clientInfo, String sessionId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return new ClientSession(sessionId, "unknown", clientInfo);
        }
        ClientSession session = new ClientSession(sessionId, clientId, clientInfo);
        sessions.put(clientId, session);
        return session;
    }

    public ClientSession get(String clientId) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return null;
        }
        return sessions.get(clientId);
    }

    public void updateSessionId(String clientId, String sessionId) {
        ClientSession session = get(clientId);
        if (session == null) {
            return;
        }
        session.setSessionId(sessionId);
    }

    public void close(String clientId, String reason) {
        if (clientId == null || clientId.trim().isEmpty()) {
            return;
        }
        ClientSession session = sessions.remove(clientId);
        if (session == null) {
            return;
        }
        session.close(reason);
    }
}
