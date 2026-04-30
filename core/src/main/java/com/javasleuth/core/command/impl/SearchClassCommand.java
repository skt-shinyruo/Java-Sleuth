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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SearchClassCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("sc")
        .description("Search for loaded classes by pattern")
        .usage("sc <class-pattern> [options]")
        .meta(CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM))
        .argument(ArgumentSpec.required("class-pattern"))
        .option(OptionSpec.flag("details").alias("-d").build())
        .option(OptionSpec.flag("fields").alias("-f").build())
        .option(OptionSpec.flag("expand").alias("-x").build())
        .example("sc com.example.* -d")
        .build();

    private final Instrumentation instrumentation;

    public SearchClassCommand(Instrumentation instrumentation) {
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
        return searchClasses(
            parsed.argument("class-pattern"),
            Boolean.TRUE.equals(parsed.booleanOption("details")),
            Boolean.TRUE.equals(parsed.booleanOption("fields")),
            Boolean.TRUE.equals(parsed.booleanOption("expand"))
        );
    }

    private String searchClasses(String pattern, boolean showDetails, boolean showFields, boolean expand) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Search Classes ===\n");

        String normalized = normalizePattern(pattern);
        Pattern regex = WildcardMatcher.compile(normalized, Pattern.CASE_INSENSITIVE);
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        List<Class<?>> matchedClasses = new ArrayList<>();

        for (Class<?> clazz : loadedClasses) {
            if (regex.matcher(clazz.getName()).matches()) {
                matchedClasses.add(clazz);
            }
        }

        sb.append("Found ").append(matchedClasses.size()).append(" classes matching '").append(pattern).append("'\n\n");

        for (Class<?> clazz : matchedClasses) {
            sb.append("Class: ").append(clazz.getName()).append("\n");

            if (showDetails || expand) {
                sb.append("  ClassLoader: ").append(getClassLoaderName(clazz.getClassLoader())).append("\n");
                sb.append("  Package: ").append(clazz.getPackage() != null ? clazz.getPackage().getName() : "N/A").append("\n");
                sb.append("  Modifiers: ").append(java.lang.reflect.Modifier.toString(clazz.getModifiers())).append("\n");

                if (clazz.getSuperclass() != null) {
                    sb.append("  Super Class: ").append(clazz.getSuperclass().getName()).append("\n");
                }

                Class<?>[] interfaces = clazz.getInterfaces();
                if (interfaces.length > 0) {
                    sb.append("  Interfaces: ");
                    for (int i = 0; i < interfaces.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(interfaces[i].getName());
                    }
                    sb.append("\n");
                }
            }

            if (showFields || expand) {
                try {
                    java.lang.reflect.Field[] fields = clazz.getDeclaredFields();
                    if (fields.length > 0) {
                        sb.append("  Fields:\n");
                        for (java.lang.reflect.Field field : fields) {
                            sb.append("    ").append(java.lang.reflect.Modifier.toString(field.getModifiers()))
                              .append(" ").append(field.getType().getSimpleName())
                              .append(" ").append(field.getName()).append("\n");
                        }
                    }
                } catch (Exception e) {
                    sb.append("  Fields: Unable to access (").append(e.getMessage()).append(")\n");
                }
            }

            sb.append("\n");
        }

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

    private String getClassLoaderName(ClassLoader classLoader) {
        if (classLoader == null) {
            return "Bootstrap ClassLoader";
        }
        return classLoader.getClass().getName() + "@" + Integer.toHexString(classLoader.hashCode());
    }

    @Override
    public String getDescription() {
        return "Search for loaded classes by pattern";
    }
}
