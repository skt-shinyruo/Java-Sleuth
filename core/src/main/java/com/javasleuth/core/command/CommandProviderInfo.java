package com.javasleuth.core.command;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Stable provider-level plugin contract metadata.
 *
 * <p>Namespace + API version + capabilities let the registry govern external command providers without
 * relying only on bare command names.</p>
 */
public final class CommandProviderInfo {
    public static final String CURRENT_API_VERSION = "1";

    private final String providerName;
    private final String namespace;
    private final String apiVersion;
    private final Set<String> capabilities;
    private final boolean exposeUnqualifiedCommands;
    private final boolean builtin;

    private CommandProviderInfo(
        String providerName,
        String namespace,
        String apiVersion,
        Set<String> capabilities,
        boolean exposeUnqualifiedCommands,
        boolean builtin
    ) {
        this.providerName = providerName;
        this.namespace = namespace;
        this.apiVersion = apiVersion;
        this.capabilities = capabilities != null ? capabilities : Collections.<String>emptySet();
        this.exposeUnqualifiedCommands = exposeUnqualifiedCommands;
        this.builtin = builtin;
    }

    public static CommandProviderInfo legacy(String providerName) {
        String normalizedName = sanitizeProviderName(providerName, "legacy");
        return new CommandProviderInfo(
            normalizedName,
            normalizedName,
            CURRENT_API_VERSION,
            Collections.<String>emptySet(),
            true,
            false
        );
    }

    public static CommandProviderInfo builtin(String providerName, Collection<String> capabilities) {
        String normalizedName = sanitizeProviderName(providerName, "builtin");
        return new CommandProviderInfo(
            normalizedName,
            "builtin",
            CURRENT_API_VERSION,
            normalizeCapabilities(capabilities),
            true,
            true
        );
    }

    public static CommandProviderInfo plugin(
        String providerName,
        String namespace,
        String apiVersion,
        Collection<String> capabilities,
        boolean exposeUnqualifiedCommands
    ) {
        String normalizedName = sanitizeProviderName(providerName, "plugin");
        String normalizedNamespace = sanitizeNamespace(namespace, normalizedName);
        String normalizedVersion = sanitizeApiVersion(apiVersion);
        return new CommandProviderInfo(
            normalizedName,
            normalizedNamespace,
            normalizedVersion,
            normalizeCapabilities(capabilities),
            exposeUnqualifiedCommands,
            false
        );
    }

    public String getProviderName() {
        return providerName;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public Set<String> getCapabilities() {
        return capabilities;
    }

    public boolean isExposeUnqualifiedCommands() {
        return exposeUnqualifiedCommands;
    }

    public boolean isBuiltin() {
        return builtin;
    }

    public boolean isApiVersionSupported() {
        return majorVersion(apiVersion).equals(majorVersion(CURRENT_API_VERSION));
    }

    private static String sanitizeProviderName(String raw, String fallback) {
        String value = raw != null ? raw.trim() : "";
        return value.isEmpty() ? fallback : value;
    }

    private static String sanitizeNamespace(String raw, String fallback) {
        String value = raw != null ? raw.trim().toLowerCase(Locale.ROOT) : "";
        if (value.isEmpty()) {
            value = fallback != null ? fallback.trim().toLowerCase(Locale.ROOT) : "";
        }
        return value.isEmpty() ? "plugin" : value;
    }

    private static String sanitizeApiVersion(String raw) {
        String value = raw != null ? raw.trim() : "";
        return value.isEmpty() ? CURRENT_API_VERSION : value;
    }

    private static Set<String> normalizeCapabilities(Collection<String> rawCapabilities) {
        if (rawCapabilities == null || rawCapabilities.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String raw : rawCapabilities) {
            if (raw == null) {
                continue;
            }
            String cap = raw.trim().toLowerCase(Locale.ROOT);
            if (!cap.isEmpty()) {
                normalized.add(cap);
            }
        }
        return normalized.isEmpty() ? Collections.<String>emptySet() : Collections.unmodifiableSet(normalized);
    }

    private static String majorVersion(String value) {
        String v = value != null ? value.trim() : "";
        if (v.isEmpty()) {
            return CURRENT_API_VERSION;
        }
        int dot = v.indexOf('.');
        return dot >= 0 ? v.substring(0, dot) : v;
    }
}
