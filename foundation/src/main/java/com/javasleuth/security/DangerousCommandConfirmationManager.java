package com.javasleuth.security;

import com.javasleuth.config.ProductionConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 危险命令二次确认管理器。
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>默认安全：危险命令必须显式二次确认才能执行</li>
 *   <li>低侵入：通过追加参数 --confirm <token> 完成确认，不改变原命令语义</li>
 *   <li>抗误触：首次请求只返回挑战 token，不执行实际操作</li>
 *   <li>可审计：challenge/confirm 都会记录到审计日志</li>
 * </ul>
 */
public class DangerousCommandConfirmationManager {
    public static class ConfirmationResult {
        private final boolean allowed;
        private final String error;
        private final String[] normalizedArgs;

        private ConfirmationResult(boolean allowed, String error, String[] normalizedArgs) {
            this.allowed = allowed;
            this.error = error;
            this.normalizedArgs = normalizedArgs;
        }

        public static ConfirmationResult allowed(String[] normalizedArgs) {
            return new ConfirmationResult(true, null, normalizedArgs);
        }

        public static ConfirmationResult denied(String error) {
            return new ConfirmationResult(false, error, null);
        }

        public static ConfirmationResult denied(String error, String[] normalizedArgs) {
            return new ConfirmationResult(false, error, normalizedArgs);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getError() {
            return error;
        }

        public String[] getNormalizedArgs() {
            return normalizedArgs;
        }
    }

    private static class Pending {
        final String token;
        final long expiresAtMs;

        Pending(String token, long expiresAtMs) {
            this.token = token;
            this.expiresAtMs = expiresAtMs;
        }
    }

    private static DangerousCommandConfirmationManager instance;

    private final ProductionConfig config;
    private final AuditLogger auditLogger;
    private final SecureRandom random;

    // key: sessionId:hash(normalizedArgs) -> Pending
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private DangerousCommandConfirmationManager() {
        this.config = ProductionConfig.getInstance();
        this.auditLogger = AuditLogger.getInstance();
        this.random = new SecureRandom();
    }

    public static synchronized DangerousCommandConfirmationManager getInstance() {
        if (instance == null) {
            instance = new DangerousCommandConfirmationManager();
        }
        return instance;
    }

    public static synchronized void shutdownInstance() {
        DangerousCommandConfirmationManager inst = instance;
        if (inst == null) {
            return;
        }
        try {
            inst.pending.clear();
        } catch (Exception ignore) {
            // ignore
        } finally {
            instance = null;
        }
    }

    public ConfirmationResult confirmIfRequired(String sessionId,
                                               String clientInfo,
                                               String commandName,
                                               String[] args,
                                               CommandMeta meta) {
        String[] normalized = normalizeArgs(args);
        boolean requireDangerous = meta != null && meta.isDangerous();
        boolean requireImpactHigh = meta != null &&
            meta.getImpactLevel() == CommandMeta.ImpactLevel.HIGH &&
            config.isHighImpactConfirmEnabled();

        if (!requireDangerous && !requireImpactHigh) {
            return ConfirmationResult.allowed(normalized);
        }

        boolean dangerousEnabled = config.getBoolean("security.dangerous.confirm.enabled", true);
        if (!dangerousEnabled && !requireImpactHigh) {
            return ConfirmationResult.allowed(normalized);
        }
        if (!dangerousEnabled && requireImpactHigh) {
            // Continue: impact-high confirmation is separately configurable.
        }

        String provided = extractConfirmToken(args);
        long ttlMs = config.getLong("security.dangerous.confirm.ttl.ms", 60000);
        if (ttlMs <= 0) {
            ttlMs = 60000;
        }
        long now = System.currentTimeMillis();

        String key = buildKey(sessionId, normalized);
        pruneIfNeeded(now);

        String reason =
            requireDangerous && requireImpactHigh ? "dangerous+impact_high" :
                (requireDangerous ? "dangerous" : "impact_high");

        if (provided == null || provided.trim().isEmpty()) {
            Pending p = issueToken(now, ttlMs);
            pending.put(key, p);
            auditLogger.logPrivilegedOperation(sessionId, commandName, reason + " confirm_required ttlMs=" + ttlMs);
            return ConfirmationResult.denied(buildChallengeMessage(normalized, p.token, ttlMs), normalized);
        }

        Pending existing = pending.get(key);
        if (existing == null || existing.expiresAtMs < now) {
            pending.remove(key);
            Pending p = issueToken(now, ttlMs);
            pending.put(key, p);
            auditLogger.logPrivilegedOperation(sessionId, commandName, reason + " confirm_expired_or_missing ttlMs=" + ttlMs);
            return ConfirmationResult.denied(buildChallengeMessage(normalized, p.token, ttlMs), normalized);
        }

        if (!constantTimeEquals(existing.token, provided.trim())) {
            auditLogger.logSecurityViolation(sessionId, clientInfo, "DANGEROUS_CONFIRM", "Invalid confirm token");
            return ConfirmationResult.denied("无效的确认 token。请重新执行命令以获取新的确认 token。", normalized);
        }

        pending.remove(key);
        auditLogger.logPrivilegedOperation(sessionId, commandName, reason + " confirm_accepted");
        return ConfirmationResult.allowed(normalized);
    }

    private void pruneIfNeeded(long now) {
        int max = config.getInt("security.dangerous.confirm.cache.size", 2000);
        if (max > 0 && pending.size() > max) {
            pending.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAtMs < now);
            if (pending.size() > max) {
                // 最差兜底：避免无界增长（本地排障场景下清空可接受）
                pending.clear();
            }
        } else {
            // 轻量清理：仅在有需要时执行
            pending.entrySet().removeIf(e -> e.getValue() == null || e.getValue().expiresAtMs < now);
        }
    }

    private Pending issueToken(long now, long ttlMs) {
        int bytes = config.getInt("security.dangerous.confirm.token.bytes", 12);
        int size = bytes <= 0 ? 12 : Math.min(bytes, 64);
        byte[] buf = new byte[size];
        random.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        return new Pending(token, now + ttlMs);
    }

    private static String[] normalizeArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new String[0];
        }

        List<String> out = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }

            if ("--confirm".equalsIgnoreCase(t) || "-confirm".equalsIgnoreCase(t)) {
                // skip token value if present
                if (i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            if (t.toLowerCase().startsWith("--confirm=")) {
                continue;
            }
            out.add(t);
        }

        return out.toArray(new String[0]);
    }

    private static String extractConfirmToken(String[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }
            if ("--confirm".equalsIgnoreCase(t) || "-confirm".equalsIgnoreCase(t)) {
                if (i + 1 < args.length) {
                    return args[i + 1];
                }
                return null;
            }
            String lower = t.toLowerCase();
            if (lower.startsWith("--confirm=")) {
                return t.substring("--confirm=".length());
            }
        }
        return null;
    }

    private static String buildChallengeMessage(String[] normalizedArgs, String token, long ttlMs) {
        long seconds = Math.max(1, ttlMs / 1000);
        String base = joinArgs(normalizedArgs);
        return "⚠️ 高风险命令需要二次确认（有效期 " + seconds + "s）。\n" +
            "请重新执行并追加：--confirm <token>\n" +
            "示例：\n" +
            "  " + base + " --confirm " + token;
    }

    private static String joinArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null) {
                continue;
            }
            String t = a.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static String buildKey(String sessionId, String[] normalizedArgs) {
        String sid = (sessionId == null || sessionId.trim().isEmpty()) ? "anonymous" : sessionId.trim();
        String signature = joinArgs(normalizedArgs);
        String hash = sha256Hex(signature);
        return sid + ":" + hash;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((input != null ? input : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input != null ? input.hashCode() : 0);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < x.length; i++) {
            r |= (x[i] ^ y[i]);
        }
        return r == 0;
    }
}
