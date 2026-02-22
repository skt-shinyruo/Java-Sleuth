package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SearchMethodCommand implements Command {
    private final Instrumentation instrumentation;

    public SearchMethodCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return "Usage: sm <class-pattern> [method-pattern] [options]\n" +
                   "Options:\n" +
                   "  -d    Show method details\n" +
                   "  -E    Enable regular expression\n";
        }

        String classPattern = args[1];
        String methodPattern = args.length > 2 && !args[2].startsWith("-") ? args[2] : "*";
        boolean showDetails = false;
        boolean useRegex = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-d":
                    showDetails = true;
                    break;
                case "-E":
                    useRegex = true;
                    break;
            }
        }

        return searchMethods(classPattern, methodPattern, showDetails, useRegex);
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
