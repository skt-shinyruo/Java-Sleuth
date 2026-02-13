package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.command.CommandContext;
import com.javasleuth.command.CommandContextHolder;
import com.javasleuth.security.CommandMeta;
import com.javasleuth.command.session.ClientSession;
import com.javasleuth.config.ConfigView;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.monitor.VmToolInterceptor;
import com.javasleuth.security.DangerousCommandConfirmationManager;
import com.javasleuth.security.SecurityValidator;
import com.javasleuth.util.LoadedClassResolver;
import com.javasleuth.util.SleuthObjectInspector;
import com.javasleuth.util.SleuthValueFormatter;
import com.javasleuth.util.WildcardMatcher;
import com.javasleuth.vmtool.VmToolMethodInvoker;
import com.javasleuth.vmtool.VmToolObjectConditionEvaluator;
import com.javasleuth.vmtool.VmToolSessionRegistry;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * vmtool（简化版）：实例追踪 + 对象检视 + 受控方法调用。
 *
 * <p>重要限制：实例追踪基于构造器插桩，只能覆盖“启用 track 后新创建”的对象。</p>
 */
public class VmToolCommand implements Command {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final VmToolSessionRegistry registry = VmToolSessionRegistry.getInstance();

    public VmToolCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer, ConfigView config) {
        this(instrumentation, transformer, config, DangerousCommandConfirmationManager.getInstance());
    }

    public VmToolCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        DangerousCommandConfirmationManager dangerousConfirm
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        this.dangerousConfirm = dangerousConfirm != null ? dangerousConfirm : DangerousCommandConfirmationManager.getInstance();
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args == null || args.length < 2) {
            return getHelp();
        }
        String sub = args[1] != null ? args[1].trim().toLowerCase(Locale.ROOT) : "";
        if (sub.isEmpty() || "help".equals(sub) || "-h".equals(sub) || "--help".equals(sub)) {
            return getHelp();
        }
        switch (sub) {
            case "track":
                return handleTrack(args);
            case "stop":
                return handleStop(args);
            case "tracks":
                return handleTracks();
            case "instances":
                return handleInstances(args);
            case "inspect":
                return handleInspect(args);
            case "invoke":
                return handleInvoke(args);
            case "invoke-static":
            case "invokestatic":
                return handleInvokeStatic(args);
            case "histogram":
                return handleHistogram(args);
            default:
                return "Unknown vmtool subcommand: " + sub + "\n" + getHelp();
        }
    }

    private String handleTrack(String[] args) {
        if (args.length < 3) {
            return "Usage: vmtool track <class-pattern> [options]\n" + getHelp();
        }
        String classPattern = args[2];

        Integer loaderId = null;
        boolean allowFirst = false;
        boolean includeSubclasses = false;
        int maxEntries = config.getInt("vmtool.track.max.entries", 500);
        int classLimit = config.getInt("vmtool.track.class.limit", 50);

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--loader".equals(a) || "--loader-id".equals(a) || "--loader-hash".equals(a)) {
                if (i + 1 < args.length) {
                    Integer parsed = LoadedClassResolver.parseLoaderId(args[++i]);
                    if (parsed == null) {
                        return "Invalid --loader value: " + args[i] + " (expected: bootstrap/null/0x1234/1234)";
                    }
                    loaderId = parsed;
                }
            } else if ("--first".equals(a) || "--unsafe-first".equals(a)) {
                allowFirst = true;
            } else if ("--subclasses".equals(a) || "--include-subclasses".equals(a)) {
                includeSubclasses = true;
            } else if ("--max".equals(a) && i + 1 < args.length) {
                maxEntries = parseInt(args[++i], maxEntries);
            } else if ("--class-limit".equals(a) && i + 1 < args.length) {
                classLimit = parseInt(args[++i], classLimit);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        VmToolSessionRegistry.StartResult r = registry.startTrack(
            instrumentation,
            transformer,
            classPattern,
            loaderId,
            allowFirst,
            includeSubclasses,
            maxEntries,
            classLimit
        );
        if (r != null && r.isOk() && r.getSession() != null) {
            CommandContext ctx = CommandContextHolder.get();
            ClientSession clientSession = ctx != null ? ctx.getClientSession() : null;
            if (clientSession != null) {
                String trackId = r.getSession().getId();
                String cleanupKey = "vmtool:" + trackId;
                clientSession.registerCleanup(cleanupKey, () -> registry.stopTrack(instrumentation, transformer, trackId));
            }
        }
        return r != null ? r.getMessage() : "vmtool track failed.";
    }

    private String handleStop(String[] args) {
        if (args.length < 3) {
            return "Usage: vmtool stop <track-id>";
        }
        String trackId = args[2];
        // Best-effort remove cleanup in current session (if any).
        CommandContext ctx = CommandContextHolder.get();
        ClientSession clientSession = ctx != null ? ctx.getClientSession() : null;
        if (clientSession != null && trackId != null && !trackId.trim().isEmpty()) {
            clientSession.removeCleanup("vmtool:" + trackId.trim());
        }
        return registry.stopTrack(instrumentation, transformer, trackId).getMessage();
    }

    private String handleTracks() {
        Map<String, VmToolSessionRegistry.TrackSession> sessions = registry.listSessions();
        if (sessions.isEmpty()) {
            return "No vmtool tracks.";
        }
        List<VmToolSessionRegistry.TrackSession> list = new ArrayList<>(sessions.values());
        list.sort(Comparator.comparing(VmToolSessionRegistry.TrackSession::getId));

        StringBuilder sb = new StringBuilder();
        sb.append("ID\tBASE\tLOADER\tSUBCLASSES\tMAX\tCACHED\tALIVE\tCAPTURED_TOTAL\n");
        for (VmToolSessionRegistry.TrackSession s : list) {
            if (s == null) {
                continue;
            }
            VmToolInterceptor.TrackStats stats = VmToolInterceptor.getTrackStats(s.getId());
            int cached = stats != null ? stats.getCached() : 0;
            int alive = stats != null ? stats.getAlive() : 0;
            long total = stats != null ? stats.getCapturedTotal() : 0;
            sb.append(s.getId()).append("\t")
                .append(s.getBaseClassName()).append("\t")
                .append(LoadedClassResolver.formatLoaderId(s.getBaseLoaderId())).append("\t")
                .append(s.isIncludeSubclasses()).append("\t")
                .append(s.getMaxEntries()).append("\t")
                .append(cached).append("\t")
                .append(alive).append("\t")
                .append(total).append("\n");
        }
        return sb.toString().trim();
    }

    private String handleInstances(String[] args) {
        if (args.length < 3) {
            return "Usage: vmtool instances <track-id> [options]";
        }
        String trackId = args[2];
        int limit = 50;
        boolean aliveOnly = false;
        List<String> rawConditions = new ArrayList<>();

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--limit".equals(a) && i + 1 < args.length) {
                limit = parseInt(args[++i], 50);
            } else if ("--alive".equals(a) || "--alive-only".equals(a)) {
                aliveOnly = true;
            } else if ("--where".equals(a) && i + 1 < args.length) {
                rawConditions.add(args[++i]);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        List<VmToolObjectConditionEvaluator.Condition> conditions = VmToolObjectConditionEvaluator.parse(rawConditions);
        boolean needInstance = conditions.stream().anyMatch(c -> c != null && c.getLhs() != null && c.getLhs().startsWith("field."));

        List<VmToolInterceptor.TrackedInstanceInfo> scanned = VmToolInterceptor.listInstances(trackId, 500, false);
        if (scanned.isEmpty()) {
            return "No instances for track: " + trackId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("REF_ID\tALIVE\tTYPE\tID\tTHREAD\tAGE_MS\n");
        int out = 0;
        for (VmToolInterceptor.TrackedInstanceInfo info : scanned) {
            if (info == null) {
                continue;
            }
            if (aliveOnly && !info.isAlive()) {
                continue;
            }
            Object instance = null;
            if (needInstance) {
                instance = VmToolInterceptor.getInstance(trackId, info.getRefId());
            }
            if (!VmToolObjectConditionEvaluator.matches(info, instance, conditions)) {
                continue;
            }
            long ageMs = Math.max(0, System.currentTimeMillis() - info.getCapturedAtMs());
            sb.append(info.getRefId()).append("\t")
                .append(info.isAlive()).append("\t")
                .append(info.getClassName()).append("\t")
                .append(VmToolInterceptor.formatIdentity(info.getIdentityHash())).append("\t")
                .append(info.getCapturedThread() != null ? info.getCapturedThread() : "-").append("\t")
                .append(ageMs)
                .append("\n");
            out++;
            if (out >= Math.min(limit, 200)) {
                break;
            }
        }

        if (out == 0) {
            return "No instances matched conditions for track: " + trackId;
        }
        return sb.toString().trim();
    }

    private String handleInspect(String[] args) {
        if (args.length < 4) {
            return "Usage: vmtool inspect <track-id> <ref-id> [options]";
        }
        String trackId = args[2];
        long refId = parseLong(args[3], -1);
        if (refId <= 0) {
            return "Invalid ref-id: " + args[3];
        }

        int deep = 1;
        int maxFields = 80;
        boolean includeStatic = false;

        for (int i = 4; i < args.length; i++) {
            String a = args[i];
            if ("--deep".equals(a) && i + 1 < args.length) {
                deep = parseInt(args[++i], deep);
            } else if ("--fields".equals(a) && i + 1 < args.length) {
                maxFields = parseInt(args[++i], maxFields);
            } else if ("--static".equals(a) || "--include-static".equals(a)) {
                includeStatic = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        Object instance = VmToolInterceptor.getInstance(trackId, refId);
        if (instance == null) {
            return "Instance not found or already GC'ed. track=" + trackId + ", refId=" + refId;
        }

        SleuthObjectInspector.Options opt = new SleuthObjectInspector.Options()
            .withMaxDepth(Math.max(0, deep))
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20)
            .withMaxFields(maxFields)
            .withIncludeStatic(includeStatic);
        return SleuthObjectInspector.inspect(instance, opt);
    }

    private String handleInvoke(String[] args) throws Exception {
        if (args.length < 5) {
            return "Usage: vmtool invoke <track-id> <ref-id> <method> [args...] [options]";
        }

        // Dangerous confirm (per-subcommand)
        String[] effective = confirmDangerousIfRequired(args, "invoke");
        if (effective == null) {
            // confirm flow already returned message as exception? keep consistent:
            return "vmtool invoke blocked by confirmation manager.";
        }
        if (effective.length < 5) {
            return "Usage: vmtool invoke <track-id> <ref-id> <method> ...";
        }

        String trackId = effective[2];
        long refId = parseLong(effective[3], -1);
        if (refId <= 0) {
            return "Invalid ref-id: " + effective[3];
        }
        String methodName = effective[4];

        boolean declared = false;
        boolean unsafe = false;
        int deep = 1;
        List<String> methodArgs = new ArrayList<>();

        for (int i = 5; i < effective.length; i++) {
            String a = effective[i];
            if ("--declared".equals(a)) {
                declared = true;
            } else if ("--unsafe".equals(a)) {
                unsafe = true;
            } else if ("--deep".equals(a) && i + 1 < effective.length) {
                deep = parseInt(effective[++i], deep);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            } else {
                methodArgs.add(a);
            }
        }

        Object instance = VmToolInterceptor.getInstance(trackId, refId);
        if (instance == null) {
            return "Instance not found or already GC'ed. track=" + trackId + ", refId=" + refId;
        }
        if (!SecurityValidator.isClassAccessible(instance.getClass().getName())) {
            return "Access denied for class: " + instance.getClass().getName();
        }

        try {
            Object ret = VmToolMethodInvoker.invokeInstance(instance, methodName, methodArgs, declared, unsafe);
            SleuthValueFormatter.Options fmt = new SleuthValueFormatter.Options()
                .withMaxDepth(Math.max(0, deep))
                .withMaxStringLength(200)
                .withMaxCollectionItems(20)
                .withMaxMapEntries(20);
            return "Invoke OK: " + instance.getClass().getName() + "#" + methodName +
                "\nReturn: " + SleuthValueFormatter.format(ret, fmt);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            return "Invoke threw: " + SleuthValueFormatter.formatThrowable(asThrowable(cause), new SleuthValueFormatter.Options());
        } catch (SecurityException se) {
            return "Invoke blocked: " + se.getMessage();
        } catch (Exception e) {
            return "Invoke failed: " + e.getMessage();
        }
    }

    private String handleInvokeStatic(String[] args) throws Exception {
        if (args.length < 4) {
            return "Usage: vmtool invoke-static <class-pattern> <method> [args...] [options]";
        }

        String[] effective = confirmDangerousIfRequired(args, "invoke-static");
        if (effective == null) {
            return "vmtool invoke-static blocked by confirmation manager.";
        }
        if (effective.length < 4) {
            return "Usage: vmtool invoke-static <class-pattern> <method> ...";
        }

        String classPattern = effective[2];
        String methodName = effective[3];

        Integer loaderId = null;
        boolean allowFirst = false;
        boolean declared = false;
        boolean unsafe = false;
        int deep = 1;
        List<String> methodArgs = new ArrayList<>();

        for (int i = 4; i < effective.length; i++) {
            String a = effective[i];
            if ("--loader".equals(a) || "--loader-id".equals(a) || "--loader-hash".equals(a)) {
                if (i + 1 < effective.length) {
                    Integer parsed = LoadedClassResolver.parseLoaderId(effective[++i]);
                    if (parsed == null) {
                        return "Invalid --loader value: " + effective[i] + " (expected: bootstrap/null/0x1234/1234)";
                    }
                    loaderId = parsed;
                }
            } else if ("--first".equals(a) || "--unsafe-first".equals(a)) {
                allowFirst = true;
            } else if ("--declared".equals(a)) {
                declared = true;
            } else if ("--unsafe".equals(a)) {
                unsafe = true;
            } else if ("--deep".equals(a) && i + 1 < effective.length) {
                deep = parseInt(effective[++i], deep);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            } else {
                methodArgs.add(a);
            }
        }

        LoadedClassResolver.Candidate resolved;
        try {
            resolved = LoadedClassResolver.resolveSingle(instrumentation, normalizeClassPattern(classPattern), loaderId, false, 200, allowFirst);
        } catch (LoadedClassResolver.ResolutionException e) {
            return e.getMessage() +
                "\nCandidates:\n" + LoadedClassResolver.formatCandidates(e.getCandidates(), 10) +
                "\nHint: use --loader <loaderId> (e.g. --loader 0x1234 or --loader bootstrap)";
        }

        Class<?> type = resolved.getClazz();
        if (type == null) {
            return "Class not found in loaded classes: " + classPattern;
        }
        if (!SecurityValidator.isClassAccessible(type.getName())) {
            return "Access denied for class: " + type.getName();
        }

        try {
            Object ret = VmToolMethodInvoker.invokeStatic(type, methodName, methodArgs, declared, unsafe);
            SleuthValueFormatter.Options fmt = new SleuthValueFormatter.Options()
                .withMaxDepth(Math.max(0, deep))
                .withMaxStringLength(200)
                .withMaxCollectionItems(20)
                .withMaxMapEntries(20);
            return "InvokeStatic OK: " + type.getName() + "#" + methodName +
                "\nReturn: " + SleuthValueFormatter.format(ret, fmt);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            return "InvokeStatic threw: " + SleuthValueFormatter.formatThrowable(asThrowable(cause), new SleuthValueFormatter.Options());
        } catch (SecurityException se) {
            return "InvokeStatic blocked: " + se.getMessage();
        } catch (Exception e) {
            return "InvokeStatic failed: " + e.getMessage();
        }
    }

    private String handleHistogram(String[] args) {
        if (args.length < 3) {
            return "Usage: vmtool histogram <class-pattern> [--top <n>]";
        }
        String pattern = normalizeClassPattern(args[2]);
        int top = 20;
        boolean all = false;
        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("--top".equals(a) && i + 1 < args.length) {
                top = parseInt(args[++i], top);
            } else if ("--all".equals(a)) {
                all = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }
        String raw = getHotspotClassHistogram(all);
        if (raw == null || raw.trim().isEmpty()) {
            return "HotSpot DiagnosticCommandMBean not available (or access denied).";
        }
        List<HistogramRow> rows = parseHistogram(raw, pattern);
        if (rows.isEmpty()) {
            return "No histogram rows matched: " + args[2];
        }
        rows.sort((a, b) -> Long.compare(b.bytes, a.bytes));
        int n = Math.min(Math.max(1, top), 200);
        StringBuilder sb = new StringBuilder();
        sb.append("INSTANCES\tBYTES\tCLASS\n");
        for (int i = 0; i < rows.size() && i < n; i++) {
            HistogramRow r = rows.get(i);
            sb.append(r.instances).append("\t").append(r.bytes).append("\t").append(r.className).append("\n");
        }
        return sb.toString().trim();
    }

    private static final class HistogramRow {
        final long instances;
        final long bytes;
        final String className;

        private HistogramRow(long instances, long bytes, String className) {
            this.instances = instances;
            this.bytes = bytes;
            this.className = className;
        }
    }

    private static List<HistogramRow> parseHistogram(String raw, String classPattern) {
        List<HistogramRow> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String[] lines = raw.split("\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            // Common format: "  1:  123  456  com.example.Foo"
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String after = t.substring(colon + 1).trim();
            String[] parts = after.split("\\s+");
            if (parts.length < 3) {
                continue;
            }
            Long inst = tryParseLong(parts[0]);
            Long bytes = tryParseLong(parts[1]);
            if (inst == null || bytes == null) {
                continue;
            }
            String className = parts[2];
            if (className == null) {
                continue;
            }
            if (!WildcardMatcher.matches(className, classPattern)) {
                continue;
            }
            out.add(new HistogramRow(inst, bytes, className));
        }
        return out;
    }

    private String getHotspotClassHistogram(boolean all) {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
            String[] signature = new String[]{String[].class.getName()};
            String[] dcArgs = all ? new String[]{"-all"} : new String[0];
            Object res = server.invoke(name, "gcClassHistogram", new Object[]{dcArgs}, signature);
            return res != null ? String.valueOf(res) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String[] confirmDangerousIfRequired(String[] args, String subcommand) {
        // Only enforce confirmation for invoke / invoke-static (and other future mutating actions).
        CommandContext ctx = CommandContextHolder.get();
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        String clientInfo = ctx != null ? ctx.getClientInfo() : null;

        CommandMeta meta = CommandMeta.admin(false, false).withDangerous(true).withImpact(CommandMeta.ImpactLevel.HIGH);

        DangerousCommandConfirmationManager.ConfirmationResult r =
            dangerousConfirm.confirmIfRequired(sessionId, clientInfo, "vmtool:" + subcommand, args, meta);
        if (r == null) {
            return args;
        }
        if (!r.isAllowed()) {
            // Return challenge message directly to user.
            throw new RuntimeException(r.getError());
        }
        return r.getNormalizedArgs() != null ? r.getNormalizedArgs() : args;
    }

    private static String normalizeClassPattern(String p) {
        if (p == null) {
            return "*";
        }
        String t = p.trim();
        if (t.isEmpty()) {
            return "*";
        }
        if (!t.contains("*")) {
            return "*" + t + "*";
        }
        return t;
    }

    private static Throwable asThrowable(Throwable t) {
        return t != null ? t : new RuntimeException("Unknown error");
    }

    private static int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Long tryParseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception ignore) {
            return null;
        }
    }

    private String getHelp() {
        return "VmTool command usage (simplified):\n" +
            "  vmtool tracks\n" +
            "  vmtool track <class-pattern> [--loader <id>] [--first] [--subclasses] [--max <n>] [--class-limit <n>]\n" +
            "  vmtool instances <track-id> [--limit <n>] [--alive] [--where lhs:op:rhs]\n" +
            "  vmtool inspect <track-id> <ref-id> [--deep <n>] [--fields <n>] [--static]\n" +
            "  vmtool invoke <track-id> <ref-id> <method> [args...] [--declared] [--unsafe] [--deep <n>] [--confirm <token>]\n" +
            "  vmtool invoke-static <class-pattern> <method> [args...] [--loader <id>] [--first] [--declared] [--unsafe] [--deep <n>] [--confirm <token>]\n" +
            "  vmtool stop <track-id>\n" +
            "  vmtool histogram <class-pattern> [--top <n>] [--all]\n" +
            "\n" +
            "Where condition examples:\n" +
            "  --where class:contains:Service\n" +
            "  --where field.userId:eq:123\n" +
            "  --where ageMs:gt:1000\n";
    }

    @Override
    public String getDescription() {
        return "VmTool (lite) - track instances, inspect fields, and invoke methods (with confirmation)";
    }
}
