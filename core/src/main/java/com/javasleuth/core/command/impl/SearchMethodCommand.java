package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SearchMethodCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("sm")
        .description("Search for methods in loaded classes")
        .usage("sm <class-pattern> [method-pattern] [options]")
        .meta(CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM))
        .argument(ArgumentSpec.required("class-pattern"))
        .argument(ArgumentSpec.optional("method-pattern"))
        .option(OptionSpec.flag("details").alias("-d").build())
        .option(OptionSpec.flag("regex").alias("-E").build())
        .example("sm com.example.* do* -d")
        .build();

    private final Instrumentation instrumentation;

    public SearchMethodCommand(Instrumentation instrumentation) {
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

        String methodPattern = parsed.argument("method-pattern");
        return searchMethods(
            parsed.argument("class-pattern"),
            methodPattern != null ? methodPattern : "*",
            Boolean.TRUE.equals(parsed.booleanOption("details")),
            Boolean.TRUE.equals(parsed.booleanOption("regex"))
        );
    }

    private String searchMethods(String classPattern, String methodPattern, boolean showDetails, boolean useRegex) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Search Methods ===\n");

        String normalizedClassPattern = normalizePattern(classPattern);
        Pattern classRegex = WildcardMatcher.compile(normalizedClassPattern, Pattern.CASE_INSENSITIVE);
        Pattern methodWildcardRegex = null;
        com.google.re2j.Pattern methodRegex = null;
        if (useRegex) {
            try {
                methodRegex = com.google.re2j.Pattern.compile(methodPattern, com.google.re2j.Pattern.CASE_INSENSITIVE);
            } catch (com.google.re2j.PatternSyntaxException e) {
                return "Invalid regex pattern: " + e.getMessage();
            }
        } else {
            String normalizedMethodPattern = normalizePattern(methodPattern);
            methodWildcardRegex = WildcardMatcher.compile(normalizedMethodPattern, Pattern.CASE_INSENSITIVE);
        }

        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        List<Class<?>> matchedClasses = new ArrayList<>();

        for (Class<?> clazz : loadedClasses) {
            if (classRegex.matcher(clazz.getName()).matches()) {
                matchedClasses.add(clazz);
            }
        }

        int totalMethods = 0;

        for (Class<?> clazz : matchedClasses) {
            List<Method> matchedMethods = new ArrayList<>();

            try {
                Method[] methods = clazz.getDeclaredMethods();
                for (Method method : methods) {
                    boolean matched = useRegex
                        ? methodRegex.matcher(method.getName()).find()
                        : methodWildcardRegex.matcher(method.getName()).matches();
                    if (matched) {
                        matchedMethods.add(method);
                        totalMethods++;
                    }
                }

                if (!matchedMethods.isEmpty()) {
                    sb.append("Class: ").append(clazz.getName()).append("\n");

                    for (Method method : matchedMethods) {
                        sb.append("  ").append(formatMethod(method, showDetails)).append("\n");
                    }
                    sb.append("\n");
                }
            } catch (Exception e) {
                sb.append("Class: ").append(clazz.getName()).append(" - Unable to access methods (")
                  .append(e.getMessage()).append(")\n\n");
            }
        }

        sb.insert(sb.indexOf("===") + 20, "\nFound " + totalMethods + " methods in " + matchedClasses.size() + " classes\n");

        return sb.toString();
    }

    private String normalizePattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return "*";
        }
        String p = pattern.trim();
        if (!p.contains("*")) {
            return "*" + p + "*";
        }
        return p;
    }

    private String formatMethod(Method method, boolean showDetails) {
        StringBuilder sb = new StringBuilder();

        if (showDetails) {
            sb.append(Modifier.toString(method.getModifiers())).append(" ");
            sb.append(method.getReturnType().getSimpleName()).append(" ");
        }

        sb.append(method.getName()).append("(");

        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i].getSimpleName());
        }

        sb.append(")");

        if (showDetails) {
            Class<?>[] exceptionTypes = method.getExceptionTypes();
            if (exceptionTypes.length > 0) {
                sb.append(" throws ");
                for (int i = 0; i < exceptionTypes.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(exceptionTypes[i].getSimpleName());
                }
            }
        }

        return sb.toString();
    }

    @Override
    public String getDescription() {
        return "Search for methods in loaded classes";
    }
}
