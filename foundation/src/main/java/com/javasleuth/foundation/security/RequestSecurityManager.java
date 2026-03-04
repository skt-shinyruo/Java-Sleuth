package com.javasleuth.foundation.security;

import com.javasleuth.foundation.command.protocol.KvLineCodec;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.schema.SleuthConfigSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional request integrity / anti-replay protection.
 *
 * Default: disabled (security.mode=off)
 * Supported: hmac (security.mode=hmac)
 *
 * Request wrapper format (command line level):
 *   SIG ts=<epoch_ms> nonce=<base64url> sid=<conn_id> sig=<hex> cmd=<base64url>
 *
 * Signature base string:
 *   sid + "\\n" + ts + "\\n" + nonce + "\\n" + cmd
 */
public class RequestSecurityManager implements CommandSigner {
    public static class VerificationResult {
        private final boolean ok;
        private final String command;
        private final String error;

        private VerificationResult(boolean ok, String command, String error) {
            this.ok = ok;
            this.command = command;
            this.error = error;
        }

        public static VerificationResult ok(String command) {
            return new VerificationResult(true, command, null);
        }

        public static VerificationResult denied(String error) {
            return new VerificationResult(false, null, error);
        }

        public boolean isOk() {
            return ok;
        }

        public String getCommand() {
            return command;
        }

        public String getError() {
            return error;
        }
    }

    private final ConfigView config;
    private final AuditLogger auditLogger;

    // Key: bindingId:nonce -> timestamp
    // bindingId is per-connection (sid from handshake).
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    /**
     * 构造注入路径（推荐）。
     *
     * <p>注意：该构造器不再对 null 依赖做隐式单例回退，以避免“看似可注入、实际全局获取”的不透明依赖来源。</p>
     */
    public RequestSecurityManager(ConfigView config, AuditLogger auditLogger) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (auditLogger == null) {
            throw new IllegalArgumentException("auditLogger is required");
        }
        this.config = config;
        this.auditLogger = auditLogger;
    }

    /**
     * 默认装配（显式列出依赖来源，避免构造器内部隐式 getInstance 回退）。
     */
    public static RequestSecurityManager createDefault() {
        ProductionConfig config = ProductionConfig.createDefault();
        return new RequestSecurityManager(config, new AuditLogger(config));
    }

    public void shutdown() {
        try {
            seenNonces.clear();
        } catch (Exception ignore) {
            // ignore
        }
    }

    public VerificationResult verifyAndExtract(String sessionId, String raw) {
        String mode = SleuthConfigSchema.SECURITY_MODE.read(config);
        if (mode == null || "off".equalsIgnoreCase(mode)) {
            return VerificationResult.ok(raw != null ? raw : "");
        }

        if (!"hmac".equalsIgnoreCase(mode)) {
            return VerificationResult.denied("Unsupported security.mode: " + mode);
        }

        String secret = SleuthConfigSchema.SECURITY_HMAC_SECRET.read(config);
        if (secret == null || secret.trim().isEmpty()) {
            return VerificationResult.denied("security.hmac.secret is required when security.mode=hmac");
        }

        if (raw == null || !raw.startsWith("SIG ")) {
            return VerificationResult.denied("Security mode hmac enabled: command must be signed (SIG ...)");
        }

        Map<String, String> kv = KvLineCodec.parseAfterVerb(raw);
        if (kv.containsKey("v")) {
            return VerificationResult.denied("Unsupported SIG field: v");
        }
        String tsStr = kv.get("ts");
        String nonce = kv.get("nonce");
        String sigHex = kv.get("sig");
        String cmdB64 = kv.get("cmd");
        String sid = kv.get("sid");
        if (sid == null || sid.trim().isEmpty()) {
            return VerificationResult.denied("SIG requires sid");
        }
        if (tsStr == null || nonce == null || sigHex == null || cmdB64 == null) {
            return VerificationResult.denied("Invalid SIG format: required fields ts/nonce/sig/cmd");
        }
        Long ts = parseLongSafe(tsStr);
        if (ts == null) {
            return VerificationResult.denied("Invalid SIG ts value");
        }

        long now = System.currentTimeMillis();
        long windowMs = SleuthConfigSchema.SECURITY_HMAC_TIMESTAMP_WINDOW_MS.read(config);
        if (windowMs <= 0) {
            windowMs = 30000;
        }
        if (Math.abs(now - ts) > windowMs) {
            return VerificationResult.denied("Signature timestamp out of window");
        }

        // Replay detection must be bound to the signature binding id.
        String bindingId = sid.trim();
        String nonceKey = bindingId + ":" + nonce;
        Long existing = seenNonces.putIfAbsent(nonceKey, ts);
        if (existing != null) {
            auditLogger.logSecurityViolation(bindingId, "replay", "NONCE_REUSE", "Nonce reuse detected");
            return VerificationResult.denied("Replay detected (nonce reused)");
        }

        int maxNonces = SleuthConfigSchema.SECURITY_HMAC_NONCE_CACHE_SIZE.read(config);
        if (maxNonces > 0 && seenNonces.size() > maxNonces) {
            pruneOldNonces(now, windowMs);
        }

        String base = bindingId + "\n" + tsStr + "\n" + nonce + "\n" + cmdB64;
        byte[] expected;
        try {
            expected = hmacSha256(secret, base);
        } catch (Exception e) {
            return VerificationResult.denied("HMAC initialization failed");
        }

        byte[] provided = hexToBytes(sigHex);
        if (provided == null) {
            return VerificationResult.denied("Invalid SIG sig value");
        }

        if (!MessageDigest.isEqual(expected, provided)) {
            auditLogger.logSecurityViolation(bindingId, "hmac", "SIG_MISMATCH", "Signature mismatch");
            return VerificationResult.denied("Signature mismatch");
        }

        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(cmdB64);
        } catch (IllegalArgumentException e) {
            return VerificationResult.denied("Invalid SIG cmd value");
        }
        String command = new String(decoded, StandardCharsets.UTF_8);
        return VerificationResult.ok(command);
    }


    public String signCommand(String command, long timestampMs, String nonce, String sid) {
        String mode = SleuthConfigSchema.SECURITY_MODE.read(config);
        if (mode == null || !"hmac".equalsIgnoreCase(mode)) {
            return command;
        }
        String secret = SleuthConfigSchema.SECURITY_HMAC_SECRET.read(config);
        if (secret == null || secret.trim().isEmpty()) {
            return command;
        }

        if (sid == null || sid.trim().isEmpty()) {
            throw new IllegalArgumentException("sid is required for SIG");
        }
        String cmdB64 = Base64.getUrlEncoder().withoutPadding().encodeToString((command != null ? command : "").getBytes(StandardCharsets.UTF_8));
        String tsStr = String.valueOf(timestampMs);

        String binding = sid.trim();
        String base = binding + "\n" + tsStr + "\n" + nonce + "\n" + cmdB64;
        try {
            byte[] mac = hmacSha256(secret, base);
            String sigHex = bytesToHex(mac);
            return "SIG ts=" + tsStr + " nonce=" + nonce + " sid=" + binding + " sig=" + sigHex + " cmd=" + cmdB64;
        } catch (Exception e) {
            return command;
        }
    }

    @Override
    public String sign(String command, long timestampMs, String nonce, String connId) {
        return signCommand(command, timestampMs, nonce, connId);
    }

    private void pruneOldNonces(long now, long windowMs) {
        long cutoff = now - (windowMs * 2);
        seenNonces.entrySet().removeIf(e -> e.getValue() == null || e.getValue() < cutoff);
    }


    private static Long parseLongSafe(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static byte[] hmacSha256(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) {
            return null;
        }
        String v = hex.trim();
        if (v.length() == 0 || (v.length() % 2) != 0) {
            return null;
        }
        int len = v.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            int hi = hexCharToInt(v.charAt(i * 2));
            int lo = hexCharToInt(v.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexCharToInt(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return 10 + (c - 'a');
        }
        if (c >= 'A' && c <= 'F') {
            return 10 + (c - 'A');
        }
        return -1;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
