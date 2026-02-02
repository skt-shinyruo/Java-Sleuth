package com.javasleuth.command.impl;

import com.javasleuth.command.JobManager;
import com.javasleuth.command.StreamCommand;
import com.javasleuth.command.StreamSink;
import com.javasleuth.config.ProductionConfig;
import com.javasleuth.data.TtRecord;
import com.javasleuth.enhancement.ClassEnhancer;
import com.javasleuth.enhancement.SleuthClassFileTransformer;
import com.javasleuth.enhancement.TtEnhancer;
import com.javasleuth.monitor.TtInterceptor;
import com.javasleuth.util.SleuthValueFormatter;
import com.javasleuth.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.objectweb.asm.Type;

public class TtCommand implements StreamCommand {
    private final Instrumentation instrumentation;
    private final SleuthClassFileTransformer transformer;
    private final ProductionConfig config;
    private final ConcurrentHashMap<String, TtSession> activeSessions = new ConcurrentHashMap<>();

    public TtCommand(Instrumentation instrumentation, SleuthClassFileTransformer transformer) {
        this.instrumentation = instrumentation;
        this.transformer = transformer;
        this.config = ProductionConfig.getInstance();
    }

    @Override
    public String execute(String[] args) throws Exception {
        return runTt(args, null);
    }

    @Override
    public void executeStream(String[] args, StreamSink sink) throws Exception {
        runTt(args, sink);
    }

    private String runTt(String[] args, StreamSink sink) throws Exception {
        if (args == null || args.length == 1) {
            return getHelp();
        }

        // Subcommands: list/detail/stop
        String sub = args[1].toLowerCase();
        if ("list".equals(sub)) {
            return list(args);
        }
        if ("detail".equals(sub)) {
            return detail(args);
        }
        if ("replay".equals(sub)) {
            return replay(args);
        }
        if ("clear".equals(sub)) {
            TtInterceptor.clear();
            return "TT records cleared.";
        }
        if ("stop".equals(sub)) {
            return stop(args);
        }
        if ("record".equals(sub)) {
            // tt record <class-pattern> <method-pattern> [options]
            if (args.length < 4) {
                return "Usage: tt record <class-pattern> <method-pattern> [options]";
            }
            String[] shifted = new String[args.length - 1];
            shifted[0] = "tt";
            System.arraycopy(args, 2, shifted, 1, args.length - 2);
            return runRecord(shifted, sink);
        }

        // Default: tt <class-pattern> <method-pattern>
        return runRecord(args, sink);
    }

    private String runRecord(String[] args, StreamSink sink) throws Exception {
        if (args.length < 3) {
            return getHelp();
        }

        boolean background = false;
        String classPattern = args[1];
        String methodPattern = args[2];

        int maxCount = 100;
        long timeoutSeconds = 30;

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if ("-n".equals(a) || "--count".equals(a)) {
                if (i + 1 < args.length) {
                    maxCount = parseInt(args[++i], 100);
                }
            } else if ("-t".equals(a) || "--timeout".equals(a)) {
                if (i + 1 < args.length) {
                    timeoutSeconds = parseLong(args[++i], 30);
                }
            } else if ("--bg".equals(a)) {
                background = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        if (background) {
            String[] jobArgs = removeFlag(args, "--bg");
            String commandLine = String.join(" ", jobArgs);
            String jobId = JobManager.getInstance().submitStreamJob(
                "tt",
                commandLine,
                jobSink -> runRecord(jobArgs, jobSink)
            );
            String msg = "Started tt in background. Job ID: " + jobId + " (use: jobs tail " + jobId + ")";
            if (sink != null) {
                sink.send(msg);
                sink.close("job started");
                return "";
            }
            return msg;
        }

        return startRecording(classPattern, methodPattern, maxCount, timeoutSeconds, sink);
    }

    private String startRecording(String classPattern, String methodPattern,
                                  int maxCount, long timeoutSeconds, StreamSink sink) throws Exception {
        // Find one matching class (simplified).
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        Class<?> target = null;
        for (Class<?> c : loaded) {
            if (c != null && WildcardMatcher.matches(c.getName(), classPattern)) {
                target = c;
                break;
            }
        }
        if (target == null) {
            return "No loaded class matches pattern: " + classPattern;
        }

        String ttId = UUID.randomUUID().toString();
        BlockingQueue<TtRecord> q = new LinkedBlockingQueue<>(config.getWatchQueueCapacity());

        ClassEnhancer enhancer = new TtEnhancer(target.getName(), methodPattern, null, ttId);
        boolean interceptorRegistered = false;
        boolean enhancerAdded = false;
        try {
            TtInterceptor.register(ttId, q);
            interceptorRegistered = true;

            transformer.addEnhancer(target.getName(), enhancer);
            enhancerAdded = true;

            instrumentation.retransformClasses(target);

            TtSession session = new TtSession(ttId, target, methodPattern, q, enhancer);
            activeSessions.put(ttId, session);
        } catch (Exception e) {
            // Rollback partial state best-effort.
            if (enhancerAdded) {
                try {
                    transformer.removeEnhancer(target.getName(), enhancer);
                } catch (Exception ignore) {
                    // ignore
                }
                try {
                    instrumentation.retransformClasses(target);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            if (interceptorRegistered) {
                try {
                    TtInterceptor.unregister(ttId);
                } catch (Exception ignore) {
                    // ignore
                }
            }
            throw e;
        }

        StringBuilder banner = new StringBuilder();
        banner.append("Started tt record ").append(target.getName()).append(".").append(methodPattern).append("\n");
        banner.append("TT ID: ").append(ttId).append("\n");
        banner.append("Max records: ").append(maxCount).append(", Timeout: ").append(timeoutSeconds).append("s\n");

        if (sink != null) {
            sink.send(banner.toString().trim());
        }

        StringBuilder out = new StringBuilder();
        int recorded = 0;
        long startMs = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000;

        try {
            while (recorded < maxCount) {
                long remaining = timeoutMs - (System.currentTimeMillis() - startMs);
                if (remaining <= 0) {
                    appendOrSend(out, sink, "\nTT timeout reached");
                    break;
                }
                TtRecord r = q.poll(Math.min(remaining, 1000), TimeUnit.MILLISECONDS);
                if (r != null) {
                    recorded++;
                    appendOrSend(out, sink, formatRecordLine(r, recorded));
                }
            }
        } finally {
            stopSession(ttId);
        }

        String summary = "TT completed. totalRecords=" + recorded;
        if (sink != null) {
            sink.close(summary);
            return "";
        }
        out.append(summary);
        return out.toString();
    }

    private String list(String[] args) {
        int n = 50;
        if (args.length >= 3) {
            n = parseInt(args[2], 50);
        }
        List<TtRecord> records = TtInterceptor.list(n);
        if (records.isEmpty()) {
            return "No TT records.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ID\tTIME\tCOST\tTYPE\tMETHOD\tTHREAD\n");
        for (TtRecord r : records) {
            sb.append(r.getRecordId()).append("\t")
                .append(Instant.ofEpochMilli(r.getTimestampMs())).append("\t")
                .append(formatNanos(r.getDuration())).append("\t")
                .append(r.getEventType()).append("\t")
                .append(r.getClassName()).append(".").append(r.getMethodName()).append("()\t")
                .append(r.getThreadName()).append("#").append(r.getThreadId())
                .append("\n");
        }
        return sb.toString().trim();
    }

    private String detail(String[] args) {
        if (args.length < 3) {
            return "Usage: tt detail <recordId>";
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "Invalid recordId: " + args[2];
        }
        TtRecord r = TtInterceptor.find(id);
        if (r == null) {
            return "Record not found: " + id;
        }

        SleuthValueFormatter.Options opt = new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

        StringBuilder sb = new StringBuilder();
        sb.append("=== TT Record Detail ===\n");
        sb.append("RecordId: ").append(r.getRecordId()).append("\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(r.getTimestampMs())).append("\n");
        sb.append("Thread: ").append(r.getThreadName()).append("#").append(r.getThreadId()).append("\n");
        sb.append("Method: ").append(r.getClassName()).append(".").append(r.getMethodName()).append(r.getMethodDescriptor()).append("\n");
        sb.append("Cost: ").append(formatNanos(r.getDuration())).append("\n");
        sb.append("Type: ").append(r.getEventType()).append("\n\n");

        sb.append("Params: ").append(SleuthValueFormatter.format(r.getParameters(), opt)).append("\n");
        if (r.getEventType() == TtRecord.EventType.METHOD_EXCEPTION) {
            sb.append("Throw: ").append(SleuthValueFormatter.format(r.getException(), opt)).append("\n");
        } else {
            sb.append("Return: ").append(SleuthValueFormatter.format(r.getReturnValue(), opt)).append("\n");
        }
        return sb.toString().trim();
    }

    private String stop(String[] args) {
        if (args.length < 3) {
            return "Usage: tt stop <ttId>";
        }
        String ttId = args[2];
        boolean ok = stopSession(ttId);
        if (!ok) {
            return "TT session not found: " + ttId;
        }
        return "TT stopped: " + ttId;
    }

    private boolean stopSession(String ttId) {
        TtSession session = activeSessions.remove(ttId);
        if (session == null) {
            TtInterceptor.unregister(ttId);
            return false;
        }
        try {
            transformer.removeEnhancer(session.target.getName(), session.enhancer);
            instrumentation.retransformClasses(session.target);
        } catch (Exception ignored) {
            // best-effort
        } finally {
            TtInterceptor.unregister(ttId);
        }
        return true;
    }

    private String formatRecordLine(TtRecord r, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", idx));
        sb.append(String.format("%s ", LocalTime.now().toString()));
        sb.append("id=").append(r.getRecordId()).append(" ");
        sb.append(r.getClassName()).append(".").append(r.getMethodName()).append("()");
        sb.append(" cost=").append(formatNanos(r.getDuration()));
        if (r.getEventType() == TtRecord.EventType.METHOD_EXCEPTION) {
            sb.append(" [EXCEPTION]");
        }
        return sb.toString();
    }

    private void appendOrSend(StringBuilder buf, StreamSink sink, String text) {
        if (sink != null) {
            sink.send(text);
        } else {
            buf.append(text).append("\n");
        }
    }

    private int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String formatNanos(long nanos) {
        long d = Math.max(0, nanos);
        if (d < 1_000) {
            return d + "ns";
        } else if (d < 1_000_000) {
            return String.format("%.2fμs", d / 1_000.0);
        } else if (d < 1_000_000_000) {
            return String.format("%.2fms", d / 1_000_000.0);
        } else {
            return String.format("%.2fs", d / 1_000_000_000.0);
        }
    }

    private String getHelp() {
        return "TT (lite) command usage:\n" +
            "  tt <class-pattern> <method-pattern> [options]\n" +
            "  tt record <class-pattern> <method-pattern> [options]\n" +
            "  tt list [n]\n" +
            "  tt detail <recordId>\n" +
            "  tt replay <recordId>            - Generate replay template (lite, no execution)\n" +
            "  tt clear\n" +
            "  tt stop <ttId>\n\n" +
            "Options:\n" +
            "  -n, --count <num>     Max records to capture (default: 100)\n" +
            "  -t, --timeout <sec>   Timeout in seconds (default: 30)\n" +
            "  --bg                 Run in background (use jobs tail/stop)\n";
    }

    @Override
    public String getDescription() {
        return "TT-lite: record/list/detail/replay-template (no execution)";
    }

    private String replay(String[] args) {
        if (args.length < 3) {
            return "Usage: tt replay <recordId>";
        }
        long id;
        try {
            id = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "Invalid recordId: " + args[2];
        }

        TtRecord r = TtInterceptor.find(id);
        if (r == null) {
            return "Record not found: " + id;
        }

        SleuthValueFormatter.Options opt = new SleuthValueFormatter.Options()
            .withMaxDepth(2)
            .withMaxStringLength(200)
            .withMaxCollectionItems(20)
            .withMaxMapEntries(20);

        String className = r.getClassName();
        String methodName = r.getMethodName();
        String methodDesc = r.getMethodDescriptor();

        Type[] argTypes;
        Type retType;
        try {
            argTypes = Type.getArgumentTypes(methodDesc);
            retType = Type.getReturnType(methodDesc);
        } catch (Exception ex) {
            // 防御：descriptor 不可解析时退化输出
            argTypes = new Type[0];
            retType = Type.VOID_TYPE;
        }

        Object[] params = r.getParameters();
        if (params == null) {
            params = new Object[0];
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== TT Replay (lite) ===\n");
        sb.append("RecordId: ").append(r.getRecordId()).append("\n");
        sb.append("Time: ").append(Instant.ofEpochMilli(r.getTimestampMs())).append("\n");
        sb.append("Thread: ").append(r.getThreadName()).append("#").append(r.getThreadId()).append("\n");
        sb.append("Method: ").append(className).append(".").append(methodName).append(methodDesc).append("\n");
        sb.append("Cost: ").append(formatNanos(r.getDuration())).append("\n");
        sb.append("Type: ").append(r.getEventType()).append("\n\n");

        sb.append("Params(summary): ").append(SleuthValueFormatter.format(params, opt)).append("\n");
        if (r.getEventType() == TtRecord.EventType.METHOD_EXCEPTION) {
            sb.append("Throw(summary): ").append(SleuthValueFormatter.format(r.getException(), opt)).append("\n");
        } else {
            sb.append("Return(summary): ").append(SleuthValueFormatter.format(r.getReturnValue(), opt)).append("\n");
        }
        sb.append("\n");

        sb.append("---- Java Template (no execution) ----\n");
        sb.append("// 说明：该模板用于快速写复现用例/最小复现；默认不在目标 JVM 内执行。\n");
        sb.append("// 目标: ").append(className).append(".").append(methodName).append("\n\n");

        sb.append("public class TtReplay_").append(r.getRecordId()).append(" {\n");
        sb.append("    public static void main(String[] args) throws Exception {\n");
        sb.append("        // 1) 准备参数（基础类型尽量还原字面量；复杂对象使用 null + 注释摘要）\n");

        for (int i = 0; i < argTypes.length; i++) {
            String javaType = argTypes[i].getClassName();
            Object v = i < params.length ? params[i] : null;
            String literal = toJavaLiteral(javaType, v, opt);
            sb.append("        ").append(javaType).append(" arg").append(i).append(" = ").append(literal).append(";\n");
        }

        String joined = joinArgs(argTypes.length);
        sb.append("\n");
        sb.append("        // 2) 调用方式（根据方法是否为 static/是否在 DI 容器中获取对象自行调整）\n");

        if ("<init>".equals(methodName)) {
            sb.append("        ").append(className).append(" obj = new ").append(className).append("(").append(joined).append(");\n");
        } else {
            String retJava = retType == null ? "void" : retType.getClassName();
            sb.append("        // Option A: 静态方法\n");
            if (!"void".equals(retJava)) {
                sb.append("        ").append(retJava).append(" resultA = ").append(className).append(".").append(methodName).append("(").append(joined).append(");\n");
            } else {
                sb.append("        ").append(className).append(".").append(methodName).append("(").append(joined).append(");\n");
            }

            sb.append("\n");
            sb.append("        // Option B: 实例方法（通常需要从业务上下文/容器获取实例）\n");
            sb.append("        // 注意：Java-Sleuth 无法自动定位实例，请自行从业务上下文/容器/单例等方式获取对象后赋值。\n");
            sb.append("        ").append(className).append(" target = null; // 例如：从 Spring 容器获取或使用单例持有者\n");
            if (!"void".equals(retJava)) {
                sb.append("        ").append(retJava).append(" resultB = target.").append(methodName).append("(").append(joined).append(");\n");
            } else {
                sb.append("        target.").append(methodName).append("(").append(joined).append(");\n");
            }
        }

        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString().trim();
    }

    private String joinArgs(int n) {
        if (n <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("arg").append(i);
        }
        return sb.toString();
    }

    private String toJavaLiteral(String declaredType, Object v, SleuthValueFormatter.Options opt) {
        if (v == null) {
            return "null";
        }

        // 优先处理常见基础类型，尽量输出可复现字面量
        if (v instanceof String) {
            return "\"" + escapeJavaString((String) v) + "\"";
        }
        if (v instanceof Character) {
            return "'" + escapeJavaChar((Character) v) + "'";
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        if (v instanceof Byte) {
            return "(byte) " + v.toString();
        }
        if (v instanceof Short) {
            return "(short) " + v.toString();
        }
        if (v instanceof Integer) {
            return v.toString();
        }
        if (v instanceof Long) {
            return v.toString() + "L";
        }
        if (v instanceof Float) {
            return String.format(Locale.ROOT, "%sf", v.toString());
        }
        if (v instanceof Double) {
            return v.toString();
        }
        if (v.getClass().isEnum()) {
            Enum<?> e = (Enum<?>) v;
            return e.getDeclaringClass().getName() + "." + e.name();
        }

        // 数组：只做摘要，不直接还原（避免过大输出）
        if (v.getClass().isArray()) {
            String summary;
            try {
                summary = Arrays.deepToString(new Object[]{v});
            } catch (Exception ex) {
                summary = SleuthValueFormatter.format(v, opt);
            }
            return "null /* array summary: " + summary + " */";
        }

        // 复杂对象：返回 null，并在注释里输出安全摘要
        String summary = SleuthValueFormatter.format(v, opt);
        if (declaredType == null || declaredType.trim().isEmpty()) {
            return "null /* summary: " + summary + " */";
        }
        return "null /* " + declaredType + " summary: " + summary + " */";
    }

    private String escapeJavaString(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '\"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private String escapeJavaChar(char c) {
        switch (c) {
            case '\\':
                return "\\\\";
            case '\'':
                return "\\'";
            case '\n':
                return "\\n";
            case '\r':
                return "\\r";
            case '\t':
                return "\\t";
            default:
                return String.valueOf(c);
        }
    }

    private static String[] removeFlag(String[] args, String flag) {
        if (args == null || args.length == 0 || flag == null || flag.isEmpty()) {
            return args;
        }
        List<String> out = new ArrayList<>();
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (flag.equals(a)) {
                continue;
            }
            out.add(a);
        }
        return out.toArray(new String[0]);
    }

    private static final class TtSession {
        private final String ttId;
        private final Class<?> target;
        private final String methodPattern;
        private final BlockingQueue<TtRecord> queue;
        private final ClassEnhancer enhancer;

        private TtSession(String ttId, Class<?> target, String methodPattern, BlockingQueue<TtRecord> queue, ClassEnhancer enhancer) {
            this.ttId = ttId;
            this.target = target;
            this.methodPattern = methodPattern;
            this.queue = queue;
            this.enhancer = enhancer;
        }
    }
}
