package com.javasleuth.util;

import java.lang.instrument.Instrumentation;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 已加载类解析/选择器。
 *
 * <p>用于在多 ClassLoader 场景下稳定选择目标 {@link Class}，并在多候选时给出可操作的提示信息。</p>
 */
public final class LoadedClassResolver {
    private LoadedClassResolver() {}

    public static final class Candidate {
        private final Class<?> clazz;
        private final String className;
        private final ClassLoader loader;
        private final int loaderId;
        private final String loaderName;
        private final String codeSource;
        private final boolean modifiable;

        private Candidate(Class<?> clazz, Instrumentation instrumentation) {
            this.clazz = clazz;
            this.className = clazz != null ? clazz.getName() : "<null>";
            this.loader = clazz != null ? clazz.getClassLoader() : null;
            this.loaderId = loaderId(this.loader);
            this.loaderName = describeLoader(this.loader);
            this.codeSource = safeCodeSource(clazz);
            this.modifiable = clazz != null && instrumentation != null && instrumentation.isModifiableClass(clazz);
        }

        public Class<?> getClazz() {
            return clazz;
        }

        public String getClassName() {
            return className;
        }

        public ClassLoader getLoader() {
            return loader;
        }

        public int getLoaderId() {
            return loaderId;
        }

        public String getLoaderName() {
            return loaderName;
        }

        public String getCodeSource() {
            return codeSource;
        }

        public boolean isModifiable() {
            return modifiable;
        }
    }

    /**
     * 解析 loader id 输入：
     * - "bootstrap"/"null" → 0
     * - "0x12ab" → hex
     * - "1234" → decimal
     */
    public static Integer parseLoaderId(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return null;
        }
        if ("bootstrap".equals(v) || "null".equals(v)) {
            return 0;
        }
        try {
            if (v.startsWith("0x")) {
                return (int) Long.parseLong(v.substring(2), 16);
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String formatLoaderId(int loaderId) {
        if (loaderId == 0) {
            return "bootstrap(0)";
        }
        return "0x" + Integer.toHexString(loaderId);
    }

    public static int loaderId(ClassLoader loader) {
        if (loader == null) {
            return 0;
        }
        return System.identityHashCode(loader);
    }

    public static String describeLoader(ClassLoader loader) {
        if (loader == null) {
            return "BootstrapClassLoader";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    public static List<Candidate> findCandidates(Instrumentation instrumentation,
                                                 String classPattern,
                                                 Integer filterLoaderId,
                                                 boolean requireModifiable,
                                                 int limit) {
        if (instrumentation == null) {
            return Collections.emptyList();
        }
        int cap = limit <= 0 ? 200 : Math.min(limit, 2000);
        List<Candidate> out = new ArrayList<>();

        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        for (Class<?> c : loaded) {
            if (c == null) {
                continue;
            }
            String name = c.getName();
            if (!WildcardMatcher.matches(name, classPattern)) {
                continue;
            }
            Candidate cand = new Candidate(c, instrumentation);
            if (filterLoaderId != null && cand.getLoaderId() != filterLoaderId.intValue()) {
                continue;
            }
            if (requireModifiable && !cand.isModifiable()) {
                continue;
            }
            out.add(cand);
            if (out.size() >= cap) {
                break;
            }
        }

        out.sort(Comparator
            .comparing(Candidate::getClassName, Comparator.nullsLast(String::compareTo))
            .thenComparingInt(Candidate::getLoaderId)
            .thenComparing(Candidate::getCodeSource, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    public static Candidate resolveSingle(Instrumentation instrumentation,
                                         String classPattern,
                                         Integer filterLoaderId,
                                         boolean requireModifiable,
                                         int limit,
                                         boolean allowFirstWhenAmbiguous) throws ResolutionException {
        List<Candidate> candidates = findCandidates(instrumentation, classPattern, filterLoaderId, requireModifiable, limit);
        if (candidates.isEmpty()) {
            throw new ResolutionException("未找到匹配的已加载类: " + classPattern, candidates);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (allowFirstWhenAmbiguous) {
            return candidates.get(0);
        }
        throw new ResolutionException("匹配到多个已加载类，请显式指定 ClassLoader: " + classPattern, candidates);
    }

    public static String formatCandidates(List<Candidate> candidates, int maxLines) {
        if (candidates == null || candidates.isEmpty()) {
            return "(no candidates)";
        }
        int cap = maxLines <= 0 ? 10 : Math.min(maxLines, 100);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Candidate c : candidates) {
            if (c == null) {
                continue;
            }
            if (shown >= cap) {
                break;
            }
            sb.append("- ")
                .append(c.getClassName())
                .append(" | loaderId=").append(formatLoaderId(c.getLoaderId()))
                .append(" | loader=").append(c.getLoaderName());
            if (c.getCodeSource() != null && !c.getCodeSource().isEmpty()) {
                sb.append(" | codeSource=").append(c.getCodeSource());
            }
            if (!c.isModifiable()) {
                sb.append(" | modifiable=false");
            }
            sb.append("\n");
            shown++;
        }
        if (candidates.size() > shown) {
            sb.append("... (").append(candidates.size()).append(" total)\n");
        }
        return sb.toString().trim();
    }

    private static String safeCodeSource(Class<?> clazz) {
        if (clazz == null) {
            return "";
        }
        try {
            CodeSource cs = clazz.getProtectionDomain() != null ? clazz.getProtectionDomain().getCodeSource() : null;
            if (cs == null || cs.getLocation() == null) {
                return "";
            }
            return String.valueOf(cs.getLocation());
        } catch (Exception ignore) {
            return "";
        }
    }

    public static final class ResolutionException extends Exception {
        private final List<Candidate> candidates;

        public ResolutionException(String message, List<Candidate> candidates) {
            super(message);
            this.candidates = candidates != null ? candidates : Collections.<Candidate>emptyList();
        }

        public List<Candidate> getCandidates() {
            return candidates;
        }
    }
}

