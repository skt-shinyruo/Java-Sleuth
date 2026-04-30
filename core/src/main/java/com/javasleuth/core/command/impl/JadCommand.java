package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.util.CfrDecompiler;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.StringUtils;
import com.javasleuth.foundation.util.WildcardMatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.*;

public class JadCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("jad")
        .description("Decompile Java classes to readable source code")
        .usage("jad <classname> [options]")
        .meta(CommandMeta.operator(false, false).withImpact(CommandMeta.ImpactLevel.HIGH).withRateLimit(5))
        .argument(ArgumentSpec.required("classname"))
        .option(OptionSpec.flag("lines").alias("-l").build())
        .option(OptionSpec.flag("verbose").alias("-v").build())
        .option(OptionSpec.string("method").alias("-m").build())
        .option(OptionSpec.string("contains").build())
        .option(OptionSpec.integer("max-lines").defaultValue(0).range(0, 1000000).build())
        .example("jad java.lang.String --lines")
        .example("jad MyClass --method=toString")
        .build();

    private final Instrumentation instrumentation;

    public JadCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public static CommandSpec spec() {
        return SPEC;
    }

    @Override
    public CommandSpec getSpec() {
        return SPEC;
    }

    @Override
    public String execute(String[] args) throws Exception {
        ParsedCommand parsed = CommandSpecSupport.parsed(SPEC, args);
        if (parsed.isHelpRequested()) {
            return CommandHelpRenderer.render(SPEC);
        }

        return decompileClass(
            parsed.argument("classname"),
            Boolean.TRUE.equals(parsed.booleanOption("lines")),
            Boolean.TRUE.equals(parsed.booleanOption("verbose")),
            parsed.stringOption("method"),
            parsed.stringOption("contains"),
            parsed.intOption("max-lines")
        );
    }

    private String decompileClass(String className, boolean showLineNumbers, boolean verbose, String methodFilter,
                                  String containsFilter, int maxLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Java Decompilation ===\n");
        sb.append("Class: ").append(className).append("\n");

        try {
            // Find the class
            Class<?> targetClass = findClass(className);
            if (targetClass == null) {
                return "Class not found: " + className + "\n" +
                       "Use 'sc " + className + "' to search for available classes";
            }

            sb.append("Found: ").append(targetClass.getName()).append("\n");
            sb.append("ClassLoader: ").append(targetClass.getClassLoader()).append("\n\n");

            // Get bytecode
            byte[] bytecode = getClassBytecode(targetClass);
            if (bytecode == null) {
                return "Unable to retrieve bytecode for class: " + className;
            }

            // Decompile using CFR
            String decompiled = decompileWithCfr(targetClass.getName(), bytecode, showLineNumbers, verbose);

            // Apply method filter if specified
            if (methodFilter != null) {
                decompiled = filterMethods(decompiled, methodFilter);
                sb.append("Method filter: ").append(methodFilter).append("\n");
            }

            if (containsFilter != null && !containsFilter.trim().isEmpty()) {
                decompiled = filterContains(decompiled, containsFilter);
                sb.append("Contains filter: ").append(containsFilter).append("\n");
            }

            if (maxLines > 0) {
                decompiled = truncateLines(decompiled, maxLines);
                sb.append("Max lines: ").append(maxLines).append("\n");
            }

            sb.append("Decompiled source:\n");
            sb.append(StringUtils.repeat('=', 50)).append("\n");
            sb.append(decompiled);
            sb.append("\n").append(StringUtils.repeat('=', 50)).append("\n");

        } catch (Exception e) {
            sb.append("Error decompiling class: ").append(e.getMessage()).append("\n");
            sb.append("Possible causes:\n");
            sb.append("- Class not found in current classloader\n");
            sb.append("- Bytecode not accessible\n");
            sb.append("- CFR decompiler error\n");
            if (verbose) {
                sb.append("Stack trace:\n");
                for (StackTraceElement element : e.getStackTrace()) {
                    sb.append("  ").append(element.toString()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String filterContains(String decompiled, String containsFilter) {
        String[] lines = decompiled.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.contains(containsFilter)) {
                sb.append(line).append("\n");
            }
        }
        if (sb.length() == 0) {
            return "// No lines matched --contains=" + containsFilter;
        }
        return sb.toString();
    }

    private String truncateLines(String decompiled, int maxLines) {
        if (maxLines <= 0) {
            return decompiled;
        }
        String[] lines = decompiled.split("\n");
        if (lines.length <= maxLines) {
            return decompiled;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("// ... [truncated, total lines=").append(lines.length).append("]\n");
        return sb.toString();
    }

    private Class<?> findClass(String className) {
        try {
            // Try direct class loading first
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            // Search through loaded classes
            Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();

            for (Class<?> clazz : loadedClasses) {
                String clazzName = clazz.getName();

                // Exact match
                if (clazzName.equals(className)) {
                    return clazz;
                }

                // Simple name match (for inner classes)
                if (clazzName.endsWith("." + className) || clazzName.endsWith("$" + className)) {
                    return clazz;
                }

                // Partial match if the input contains wildcards
                if (className.contains("*")) {
                    if (WildcardMatcher.matches(clazzName, className)) {
                        return clazz;
                    }
                }
            }

            return null;
        }
    }

    private byte[] getClassBytecode(Class<?> clazz) {
        try {
            String className = clazz.getName();
            String classFile = className.replace('.', '/') + ".class";

            ClassLoader classLoader = clazz.getClassLoader();
            if (classLoader == null) {
                classLoader = ClassLoader.getSystemClassLoader();
            }

            InputStream is = classLoader.getResourceAsStream(classFile);
            if (is == null) {
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }

            is.close();
            return baos.toByteArray();

        } catch (IOException e) {
            return null;
        }
    }

    private String decompileWithCfr(String className, byte[] bytecode, boolean showLineNumbers, boolean verbose) {
        return CfrDecompiler.decompileClassBytes(className, bytecode, showLineNumbers, verbose);
    }

    private String filterMethods(String decompiled, String methodFilter) {
        if (methodFilter == null || methodFilter.trim().isEmpty()) {
            return decompiled;
        }

        String normalizedMethodFilter = methodFilter.trim();
        if (!normalizedMethodFilter.contains("*")) {
            normalizedMethodFilter = "*" + normalizedMethodFilter + "*";
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = decompiled.split("\n");
        boolean inTargetMethod = false;
        int braceCount = 0;

        // Add class declaration and imports
        for (String line : lines) {
            if (line.trim().startsWith("package ") ||
                line.trim().startsWith("import ") ||
                line.trim().startsWith("//") ||
                line.contains("class ") && line.contains("{")) {
                sb.append(line).append("\n");
                if (line.contains("class ") && line.contains("{")) {
                    sb.append("\n");
                }
            }
        }

        // Find and include matching methods
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (!inTargetMethod) {
                // Check if this line starts a method that matches our filter
                if (isMethodDeclaration(line) && matchesMethodFilter(line, normalizedMethodFilter)) {
                    inTargetMethod = true;
                    braceCount = 0;
                    sb.append("    ").append(lines[i]).append("\n");

                    // Count braces in the method declaration line
                    for (char c : line.toCharArray()) {
                        if (c == '{') braceCount++;
                        if (c == '}') braceCount--;
                    }
                }
            } else {
                // We're inside a matching method
                sb.append("    ").append(lines[i]).append("\n");

                // Count braces to determine when method ends
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }

                if (braceCount <= 0) {
                    inTargetMethod = false;
                    sb.append("\n");
                }
            }
        }

        // Close class
        sb.append("}\n");

        return sb.toString();
    }

    private boolean matchesMethodFilter(String methodDeclarationLine, String methodFilter) {
        if (methodDeclarationLine == null || methodFilter == null || methodFilter.trim().isEmpty()) {
            return false;
        }
        String line = methodDeclarationLine.trim();
        int idx = line.indexOf('(');
        if (idx <= 0) {
            return false;
        }
        String beforeParen = line.substring(0, idx).trim();
        if (beforeParen.isEmpty()) {
            return false;
        }
        // method name is usually the last token before '('
        String[] parts = beforeParen.split("\\s+");
        String name = parts.length == 0 ? null : parts[parts.length - 1];
        if (name == null || name.isEmpty()) {
            return false;
        }
        return WildcardMatcher.matches(name, methodFilter);
    }

    private boolean isMethodDeclaration(String line) {
        line = line.trim();

        // Skip non-method lines
        if (line.startsWith("//") || line.startsWith("/*") ||
            line.startsWith("@") || line.isEmpty() ||
            line.startsWith("class ") || line.startsWith("interface ") ||
            line.startsWith("enum ") || line.equals("{") || line.equals("}")) {
            return false;
        }

        // Look for method patterns: modifiers + returnType + methodName + (parameters)
        return line.contains("(") && line.contains(")") &&
               (line.contains("public ") || line.contains("private ") ||
                line.contains("protected ") || line.contains("static ") ||
                line.matches(".*\\w+\\s*\\(.*\\).*"));
    }

    @Override
    public String getDescription() {
        return "Decompile Java classes to readable source code";
    }
}
