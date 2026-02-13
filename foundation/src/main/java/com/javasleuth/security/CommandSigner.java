package com.javasleuth.security;

/**
 * Minimal client-side command signing abstraction.
 *
 * <p>Implementations may either:</p>
 * <ul>
 *   <li>return the raw command unchanged (no signing)</li>
 *   <li>wrap it into a signed command envelope (e.g. {@code SIG ...})</li>
 * </ul>
 *
 * <p>This interface intentionally only covers the client needs. Server-side verification
 * remains in {@link RequestSecurityManager}.</p>
 */
public interface CommandSigner {

    /**
     * Sign a command for transport.
     *
     * @param command raw command line, e.g. {@code "version"}
     * @param timestampMs client timestamp (epoch millis)
     * @param nonce base64url nonce
     * @param connId connection binding id (sid in SIG envelope)
     * @return signed command line (or raw command if signing disabled)
     */
    String sign(String command, long timestampMs, String nonce, String connId);

    /**
     * No-op signer that returns the raw command unchanged.
     */
    static CommandSigner noop() {
        return (command, timestampMs, nonce, connId) -> command;
    }
}

