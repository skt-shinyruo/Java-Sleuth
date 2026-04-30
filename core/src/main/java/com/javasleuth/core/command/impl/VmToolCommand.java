package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.CommandContext;
import com.javasleuth.core.command.CommandContextHolder;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.CommandSpecParser;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.core.agent.runtime.BootstrapBridge;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.core.command.session.ClientSession;
import com.javasleuth.foundation.config.ConfigView;
import com.javasleuth.foundation.config.ProductionConfig;
import com.javasleuth.foundation.config.model.SleuthConfig;
import com.javasleuth.foundation.config.model.SleuthConfigParser;
import com.javasleuth.foundation.config.model.VmToolConfig;
import com.javasleuth.core.enhancement.SleuthClassFileTransformer;
import com.javasleuth.core.enhancement.session.EnhancementSessionDescriptor;
import com.javasleuth.core.enhancement.session.EnhancementSessionKind;
import com.javasleuth.core.enhancement.session.EnhancementSessionRegistry;
import com.javasleuth.foundation.security.DangerousCommandConfirmationManager;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.AuthenticationManager.UserRole;
import com.javasleuth.foundation.security.SecurityValidator;
import com.javasleuth.foundation.util.LoadedClassResolver;
import com.javasleuth.core.util.SleuthObjectInspector;
import com.javasleuth.bootstrap.util.SleuthValueFormatter;
import com.javasleuth.foundation.util.WildcardMatcher;
import com.javasleuth.core.vmtool.VmToolMethodInvoker;
import com.javasleuth.core.vmtool.VmToolObjectConditionEvaluator;
import com.javasleuth.core.vmtool.VmToolSessionRegistry;
import com.javasleuth.core.vmtool.VmToolTracker;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * vmtool（简化版）：实例追踪 + 对象检视 + 受控方法调用。
 *
 * <p>重要限制：实例追踪基于构造器插桩，只能覆盖“启用 track 后新创建”的对象。</p>
 */
public class VmToolCommand implements Command, SpecBackedCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ConfigView config;
    private final DangerousCommandConfirmationManager dangerousConfirm;
    private final VmToolSessionRegistry registry;
    private final EnhancementSessionRegistry enhancementSessionRegistry;
    private static final Integer INVALID_LOADER = Integer.valueOf(Integer.MIN_VALUE);

    public VmToolCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        DangerousCommandConfirmationManager dangerousConfirm,
        VmToolSessionRegistry registry
    ) {
        this(instrumentation, transformer, config, dangerousConfirm, registry, null);
    }

    public VmToolCommand(
        Instrumentation instrumentation,
        SleuthClassFileTransformer transformer,
        ConfigView config,
        DangerousCommandConfirmationManager dangerousConfirm,
        VmToolSessionRegistry registry,
        EnhancementSessionRegistry enhancementSessionRegistry
    ) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = config;
        if (dangerousConfirm == null) {
            throw new IllegalArgumentException("dangerousConfirm");
        }
        if (registry == null) {
            throw new IllegalArgumentException("registry");
        }
        this.dangerousConfirm = dangerousConfirm;
        this.registry = registry;
        this.enhancementSessionRegistry = enhancementSessionRegistry;
    }

    @Override
    public String execute(String[] args) throws Exception {
        ParsedCommand parsed = parsedOrFallback(args);
        String sub = parsed.subcommandName();
        if (parsed.isHelpRequested()) {
            return getHelp(sub);
        }
        if (sub == null) {
            return getHelp();
        }
        switch (sub) {
            case "track":
                return handleTrack(parsed);
            case "stop":
                return handleStop(parsed);
            case "tracks":
                return handleTracks();
            case "instances":
                return handleInstances(parsed);
            case "inspect":
                return handleInspect(parsed);
            case "invoke":
                return handleInvoke(args);
            case "invoke-static":
            case "invokestatic":
                return handleInvokeStatic(args);
            case "histogram":
                return handleHistogram(parsed);
            default:
                return "Unknown vmtool subcommand: " + sub + "\n" + getHelp();
        }
    }

    public static CommandSpec spec() {
        return CommandSpec.builder("vmtool")
            .description("VmTool (lite) - track instances, inspect fields, and invoke methods")
            .usage("vmtool <subcommand> [arguments] [options]")
            .meta(CommandMeta.operator(false, false)
                .requiresBootstrap(BootstrapBridge.SPY_API)
                .withCapability(CommandCapability.LONG_RUNNING)
                .withImpact(CommandMeta.ImpactLevel.MEDIUM)
                .withRateLimit(10)
                .withSubcommandRole("invoke", UserRole.ADMIN)
                .withSubcommandRole("invoke-static", UserRole.ADMIN)
                .withSubcommandRole("invokestatic", UserRole.ADMIN))
            .subcommand(SubcommandSpec.builder("track")
                .description("Track newly created instances of matching classes")
                .usage("vmtool track <class-pattern> [options]")
                .argument(ArgumentSpec.required("class-pattern"))
                .option(loaderOption())
                .option(OptionSpec.flag("first").alias("--first").alias("--unsafe-first").build())
                .option(OptionSpec.flag("subclasses").alias("--subclasses").alias("--include-subclasses").build())
                .option(OptionSpec.integer("max").alias("--max").defaultValue(500).range(1, 100000).build())
                .option(OptionSpec.integer("class-limit").alias("--class-limit").defaultValue(50).range(1, 10000).build())
                .build())
            .subcommand(SubcommandSpec.builder("stop")
                .description("Stop an instance tracking session")
                .usage("vmtool stop <track-id>")
                .argument(ArgumentSpec.required("track-id"))
                .build())
            .subcommand(SubcommandSpec.builder("tracks")
                .description("List active instance tracking sessions")
                .usage("vmtool tracks")
                .build())
            .subcommand(SubcommandSpec.builder("instances")
                .description("List tracked instances")
                .usage("vmtool instances <track-id> [options]")
                .argument(ArgumentSpec.required("track-id"))
                .option(OptionSpec.integer("limit").alias("--limit").defaultValue(50).range(1, 10000).build())
                .option(OptionSpec.flag("alive").alias("--alive").alias("--alive-only").build())
                .option(OptionSpec.string("where").alias("--where").repeatable(true).build())
                .build())
            .subcommand(SubcommandSpec.builder("inspect")
                .description("Inspect fields on a tracked instance")
                .usage("vmtool inspect <track-id> <ref-id> [options]")
                .argument(ArgumentSpec.required("track-id"))
                .argument(ArgumentSpec.required("ref-id"))
                .option(OptionSpec.integer("deep").alias("--deep").defaultValue(1).range(0, 100).build())
                .option(OptionSpec.integer("fields").alias("--fields").defaultValue(80).range(1, 10000).build())
                .option(OptionSpec.flag("static").alias("--static").alias("--include-static").build())
                .build())
            .subcommand(invokeSpec("invoke", "Invoke a method on a tracked instance"))
            .subcommand(invokeStaticSpec("invoke-static", "Invoke a static method on a matching class"))
            .subcommand(invokeStaticSpec("invokestatic", "Alias for invoke-static"))
            .subcommand(SubcommandSpec.builder("histogram")
                .description("Show class histogram rows matching a pattern")
                .argument(ArgumentSpec.required("class-pattern"))
                .option(OptionSpec.integer("top").alias("--top").defaultValue(20).range(1, 200).build())
                .option(OptionSpec.flag("all").alias("--all").build())
                .build())
            .example("vmtool track com.example.Service --subclasses")
            .example("vmtool instances track-1 --limit 20 --alive")
            .example("vmtool invoke track-1 1 toString --confirm <token>")
            .build();
    }

    @Override
    public CommandSpec getSpec() {
        return spec();
    }

    private static SubcommandSpec invokeSpec(String name, String description) {
        return SubcommandSpec.builder(name)
            .description(description)
            .usage("vmtool " + name + " <track-id> <ref-id> <method> [args...] [options]")
            .argument(ArgumentSpec.required("track-id"))
            .argument(ArgumentSpec.required("ref-id"))
            .argument(ArgumentSpec.required("method"))
            .argument(ArgumentSpec.trailing("args"))
            .option(OptionSpec.flag("declared").alias("--declared").build())
            .option(OptionSpec.flag("unsafe").alias("--unsafe").build())
            .option(OptionSpec.integer("deep").alias("--deep").defaultValue(1).range(0, 100).build())
            .option(OptionSpec.string("confirm").alias("--confirm").build())
            .build();
    }

    private static SubcommandSpec invokeStaticSpec(String name, String description) {
        return SubcommandSpec.builder(name)
            .description(description)
            .usage("vmtool " + name + " <class-pattern> <method> [args...] [options]")
            .argument(ArgumentSpec.required("class-pattern"))
            .argument(ArgumentSpec.required("method"))
            .argument(ArgumentSpec.trailing("args"))
            .option(loaderOption())
            .option(OptionSpec.flag("first").alias("--first").alias("--unsafe-first").build())
            .option(OptionSpec.flag("declared").alias("--declared").build())
            .option(OptionSpec.flag("unsafe").alias("--unsafe").build())
            .option(OptionSpec.integer("deep").alias("--deep").defaultValue(1).range(0, 100).build())
            .option(OptionSpec.string("confirm").alias("--confirm").build())
            .build();
    }

    private static OptionSpec loaderOption() {
        return OptionSpec.string("loader").alias("--loader").alias("--loader-id").alias("--loader-hash").build();
    }

    private String handleTrack(ParsedCommand parsed) {
        String classPattern = parsed.argument("class-pattern");

        VmToolConfig vmTool = typedConfig().vmTool();
        Integer loaderId = parseLoader(parsed.stringOption("loader"));
        if (loaderId == INVALID_LOADER) {
            return invalidLoaderMessage(parsed.stringOption("loader"));
        }
        boolean allowFirst = Boolean.TRUE.equals(parsed.booleanOption("first"));
        boolean includeSubclasses = Boolean.TRUE.equals(parsed.booleanOption("subclasses"));
        int maxEntries = vmTool.getTrackMaxEntries();
        int classLimit = vmTool.getTrackClassLimit();
        if (parsed.isOptionExplicit("max")) {
            maxEntries = parsed.intOption("max");
        }
        if (parsed.isOptionExplicit("class-limit")) {
            classLimit = parsed.intOption("class-limit");
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
            registerEnhancementSession(classPattern, r.getSession());
            CommandContext ctx = CommandContextHolder.get();
            ClientSession clientSession = ctx != null ? ctx.getClientSession() : null;
            if (clientSession != null) {
                String trackId = r.getSession().getId();
                String cleanupKey = "vmtool:" + trackId;
                clientSession.registerCleanup(cleanupKey, () -> stopTrackAndRegistrySession(trackId, "client_cleanup"));
            }
        }
        return r != null ? r.getMessage() : "vmtool track failed.";
    }

    private SleuthConfig typedConfig() {
        return config instanceof ProductionConfig
            ? ((ProductionConfig) config).typedSnapshot()
            : SleuthConfigParser.parse(config);
    }

    private String handleStop(ParsedCommand parsed) {
        String trackId = parsed.argument("track-id");
        // Best-effort remove cleanup in current session (if any).
        CommandContext ctx = CommandContextHolder.get();
        ClientSession clientSession = ctx != null ? ctx.getClientSession() : null;
        if (clientSession != null && trackId != null && !trackId.trim().isEmpty()) {
            clientSession.removeCleanup("vmtool:" + trackId.trim());
        }
        return stopTrackAndRegistrySession(trackId, "stop").getMessage();
    }

    private VmToolSessionRegistry.StopResult stopTrackAndRegistrySession(String trackId, String reason) {
        VmToolSessionRegistry.StopResult result = registry.stopTrack(instrumentation, transformer, trackId);
        if (enhancementSessionRegistry != null && trackId != null && !trackId.trim().isEmpty()) {
            enhancementSessionRegistry.close(trackId.trim(), reason);
        }
        return result;
    }

    private void registerEnhancementSession(String classPattern, VmToolSessionRegistry.TrackSession session) {
        if (enhancementSessionRegistry == null || session == null) {
            return;
        }

        List<String> targetClassNames = new ArrayList<>();
        List<Integer> loaderIds = new ArrayList<>();
        for (VmToolSessionRegistry.EnhancedClass enhancedClass : session.getEnhancedClasses()) {
            if (enhancedClass == null) {
                continue;
            }
            targetClassNames.add(enhancedClass.getClassName());
            loaderIds.add(Integer.valueOf(enhancedClass.getLoaderId()));
        }

        CommandContext ctx = CommandContextHolder.get();
        EnhancementSessionDescriptor.Builder builder = EnhancementSessionDescriptor
            .builder(session.getId(), EnhancementSessionKind.VMTOOL)
            .withCommandName("vmtool")
            .withClassPattern(classPattern)
            .withTargetClassNames(targetClassNames)
            .withLoaderIds(loaderIds)
            .withCreatedAtMs(session.getCreatedAtMs())
            .withDetails("base=" + session.getBaseClassName()
                + ", includeSubclasses=" + session.isIncludeSubclasses()
                + ", maxEntries=" + session.getMaxEntries());
        if (ctx != null) {
            builder.withClientId(ctx.getClientId())
                .withClientSessionId(ctx.getSessionId());
        }

        String trackId = session.getId();
        enhancementSessionRegistry.register(
            builder.build(),
            reason -> registry.stopTrack(instrumentation, transformer, trackId)
        );
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
            VmToolTracker.TrackStats stats = registry.getTrackStats(s.getId());
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

    private String handleInstances(ParsedCommand parsed) {
        String trackId = parsed.argument("track-id");
        int limit = parsed.intOption("limit");
        boolean aliveOnly = Boolean.TRUE.equals(parsed.booleanOption("alive"));
        List<String> rawConditions = parsed.stringOptionValues("where");

        List<VmToolObjectConditionEvaluator.Condition> conditions = VmToolObjectConditionEvaluator.parse(rawConditions);
        boolean needInstance = conditions.stream().anyMatch(c -> c != null && c.getLhs() != null && c.getLhs().startsWith("field."));

        List<VmToolTracker.TrackedInstanceInfo> scanned = registry.listInstances(trackId, 500, false);
        if (scanned.isEmpty()) {
            return "No instances for track: " + trackId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("REF_ID\tALIVE\tTYPE\tID\tTHREAD\tAGE_MS\n");
        int out = 0;
        for (VmToolTracker.TrackedInstanceInfo info : scanned) {
            if (info == null) {
                continue;
            }
            if (aliveOnly && !info.isAlive()) {
                continue;
            }
            Object instance = null;
            if (needInstance) {
                instance = registry.getInstance(trackId, info.getRefId());
            }
            if (!VmToolObjectConditionEvaluator.matches(info, instance, conditions)) {
                continue;
            }
            long ageMs = Math.max(0, System.currentTimeMillis() - info.getCapturedAtMs());
            sb.append(info.getRefId()).append("\t")
                .append(info.isAlive()).append("\t")
                .append(info.getClassName()).append("\t")
                .append(VmToolTracker.formatIdentity(info.getIdentityHash())).append("\t")
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

    private String handleInspect(ParsedCommand parsed) {
        String trackId = parsed.argument("track-id");
        String refIdRaw = parsed.argument("ref-id");
        long refId = parseLong(refIdRaw, -1);
        if (refId <= 0) {
            return "Invalid ref-id: " + refIdRaw;
        }

        int deep = parsed.intOption("deep");
        int maxFields = parsed.intOption("fields");
        boolean includeStatic = Boolean.TRUE.equals(parsed.booleanOption("static"));

        Object instance = registry.getInstance(trackId, refId);
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
        // Dangerous confirm (per-subcommand)
        String[] effective = confirmDangerousIfRequired(args, "invoke");
        if (effective == null) {
            // confirm flow already returned message as exception? keep consistent:
            return "vmtool invoke blocked by confirmation manager.";
        }

        ParsedCommand parsed = CommandSpecParser.parse(spec(), effective);
        String trackId = parsed.argument("track-id");
        String refIdRaw = parsed.argument("ref-id");
        long refId = parseLong(refIdRaw, -1);
        if (refId <= 0) {
            return "Invalid ref-id: " + refIdRaw;
        }
        String methodName = parsed.argument("method");
        boolean declared = Boolean.TRUE.equals(parsed.booleanOption("declared"));
        boolean unsafe = Boolean.TRUE.equals(parsed.booleanOption("unsafe"));
        int deep = parsed.intOption("deep");
        List<String> methodArgs = parsed.argumentValues("args");

        Object instance = registry.getInstance(trackId, refId);
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
        String[] effective = confirmDangerousIfRequired(args, "invoke-static");
        if (effective == null) {
            return "vmtool invoke-static blocked by confirmation manager.";
        }

        ParsedCommand parsed = CommandSpecParser.parse(spec(), effective);
        String classPattern = parsed.argument("class-pattern");
        String methodName = parsed.argument("method");
        Integer loaderId = parseLoader(parsed.stringOption("loader"));
        if (loaderId == INVALID_LOADER) {
            return invalidLoaderMessage(parsed.stringOption("loader"));
        }
        boolean allowFirst = Boolean.TRUE.equals(parsed.booleanOption("first"));
        boolean declared = Boolean.TRUE.equals(parsed.booleanOption("declared"));
        boolean unsafe = Boolean.TRUE.equals(parsed.booleanOption("unsafe"));
        int deep = parsed.intOption("deep");
        List<String> methodArgs = parsed.argumentValues("args");

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

    private String handleHistogram(ParsedCommand parsed) {
        String pattern = normalizeClassPattern(parsed.argument("class-pattern"));
        int top = parsed.intOption("top");
        boolean all = Boolean.TRUE.equals(parsed.booleanOption("all"));
        String raw = getHotspotClassHistogram(all);
        if (raw == null || raw.trim().isEmpty()) {
            return "HotSpot DiagnosticCommandMBean not available (or access denied).";
        }
        List<HistogramRow> rows = parseHistogram(raw, pattern);
        if (rows.isEmpty()) {
            return "No histogram rows matched: " + parsed.argument("class-pattern");
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

    private ParsedCommand parsedOrFallback(String[] args) {
        CommandContext ctx = CommandContextHolder.get();
        ParsedCommand parsed = ctx != null ? ctx.getParsedCommand() : null;
        return parsed != null ? parsed : CommandSpecParser.parse(spec(), args);
    }

    private static Integer parseLoader(String raw) {
        if (raw == null) {
            return null;
        }
        Integer parsed = LoadedClassResolver.parseLoaderId(raw);
        return parsed != null ? parsed : INVALID_LOADER;
    }

    private static String invalidLoaderMessage(String raw) {
        return "Invalid --loader value: " + raw + " (expected: bootstrap/null/0x1234/1234)";
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
        return getHelp(null);
    }

    private String getHelp(String subcommandName) {
        CommandSpec helpSpec = spec();
        if (subcommandName != null) {
            SubcommandSpec subcommand = helpSpec.subcommand(subcommandName);
            if (subcommand != null) {
                helpSpec = subcommand.getSpec();
            }
        }
        return CommandHelpRenderer.render(helpSpec) + whereHelp(subcommandName);
    }

    private static String whereHelp(String subcommandName) {
        if (subcommandName != null && !"instances".equals(subcommandName)) {
            return "";
        }
        return "\nWhere condition examples:\n" +
            "  --where class:contains:Service\n" +
            "  --where field.userId:eq:123\n" +
            "  --where ageMs:gt:1000\n";
    }

    @Override
    public String getDescription() {
        return "VmTool (lite) - track instances, inspect fields, and invoke methods (with confirmation)";
    }
}
