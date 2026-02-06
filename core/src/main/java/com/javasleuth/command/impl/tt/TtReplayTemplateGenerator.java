package com.javasleuth.command.impl.tt;

import com.javasleuth.data.TtRecord;
import com.javasleuth.util.SleuthValueFormatter;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import org.objectweb.asm.Type;

public final class TtReplayTemplateGenerator {

    public String generate(TtRecord r) {
        if (r == null) {
            return "Record not found.";
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
        sb.append("Cost: ").append(TtFormatter.formatNanos(r.getDuration())).append("\n");
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
                case '"':
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
}

