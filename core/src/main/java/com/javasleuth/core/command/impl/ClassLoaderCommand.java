package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.core.command.spec.SubcommandSpec;
import com.javasleuth.foundation.security.CommandMeta;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.regex.Pattern;

public class ClassLoaderCommand implements Command, SpecBackedCommand {
    private static final CommandSpec LIST_SPEC = CommandSpec.builder("list")
        .description("List all ClassLoaders")
        .usage("classloader list")
        .build();

    private static final CommandSpec SPEC = CommandSpec.builder("classloader")
        .description("Analyze ClassLoader hierarchy and loaded classes")
        .usage("classloader [list|tree|stats|classes|urls|find] [options]")
        .meta(CommandMeta.viewer(true, false).withImpact(CommandMeta.ImpactLevel.MEDIUM))
        .argument(ArgumentSpec.optional("class-pattern"))
        .unknownSubcommandAsArgument(true)
        .subcommand(SubcommandSpec.of("list", "List all ClassLoaders", LIST_SPEC))
        .subcommand(SubcommandSpec.of("ls", "List all ClassLoaders", LIST_SPEC))
        .subcommand(SubcommandSpec.of(
            "tree",
            "Show ClassLoader hierarchy tree",
            CommandSpec.builder("tree")
                .description("Show ClassLoader hierarchy tree")
                .usage("classloader tree")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "stats",
            "Show ClassLoader statistics",
            CommandSpec.builder("stats")
                .description("Show ClassLoader statistics")
                .usage("classloader stats")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "classes",
            "Show classes loaded by matching ClassLoaders",
            CommandSpec.builder("classes")
                .description("Show classes loaded by matching ClassLoaders")
                .usage("classloader classes <loader-pattern>")
                .argument(ArgumentSpec.required("loader-pattern"))
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "urls",
            "Show URLs for URLClassLoaders",
            CommandSpec.builder("urls")
                .description("Show URLs for URLClassLoaders")
                .usage("classloader urls")
                .build()
        ))
        .subcommand(SubcommandSpec.of(
            "find",
            "Find classes across ClassLoaders",
            CommandSpec.builder("find")
                .description("Find classes across ClassLoaders")
                .usage("classloader find <class-pattern>")
                .argument(ArgumentSpec.required("class-pattern"))
                .build()
        ))
        .example("classloader list")
        .example("classloader find java.lang.*")
        .build();

    private final Instrumentation instrumentation;

    public ClassLoaderCommand(Instrumentation instrumentation) {
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

        String subCommand = parsed.subcommandName();
        if (subCommand == null) {
            String shortcutPattern = parsed.argument("class-pattern");
            return shortcutPattern == null ? listClassLoaders() : findClassInLoaders(shortcutPattern);
        }

        switch (subCommand) {
            case "list":
            case "ls":
                return listClassLoaders();
            case "tree":
                return showClassLoaderTree();
            case "stats":
                return showClassLoaderStats();
            case "classes":
                return showClassesByLoader(parsed.argument("loader-pattern"));
            case "urls":
                return showClassLoaderUrls();
            case "find":
                return findClassInLoaders(parsed.argument("class-pattern"));
            default:
                return CommandHelpRenderer.render(SPEC);
        }
    }

    private String listClassLoaders() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ClassLoader List ===\n");

        Set<ClassLoader> uniqueLoaders = new LinkedHashSet<>();
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();

        // Collect all unique classloaders
        for (Class<?> clazz : loadedClasses) {
            ClassLoader loader = clazz.getClassLoader();
            uniqueLoaders.add(loader);
        }

        // Count classes per classloader
        Map<ClassLoader, Integer> loaderCounts = new HashMap<>();
        for (Class<?> clazz : loadedClasses) {
            ClassLoader loader = clazz.getClassLoader();
            loaderCounts.put(loader, loaderCounts.getOrDefault(loader, 0) + 1);
        }

        sb.append(String.format("Found %d unique ClassLoaders:\n\n", uniqueLoaders.size()));

        int index = 1;
        for (ClassLoader loader : uniqueLoaders) {
            String loaderName = loader == null ? "Bootstrap ClassLoader" : loader.getClass().getName();
            int classCount = loaderCounts.getOrDefault(loader, 0);

            sb.append(String.format("[%d] %s\n", index++, loaderName));
            sb.append(String.format("    Hash: %s\n", loader == null ? "null" : Integer.toHexString(loader.hashCode())));
            sb.append(String.format("    Classes: %d\n", classCount));
            sb.append(String.format("    Parent: %s\n", getParentLoaderName(loader)));

            if (loader instanceof URLClassLoader) {
                URL[] urls = ((URLClassLoader) loader).getURLs();
                sb.append(String.format("    URLs: %d entries\n", urls.length));
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    private String showClassLoaderTree() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ClassLoader Hierarchy Tree ===\n\n");

        Set<ClassLoader> uniqueLoaders = new LinkedHashSet<>();
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();

        for (Class<?> clazz : loadedClasses) {
            uniqueLoaders.add(clazz.getClassLoader());
        }

        // Build parent-child relationships
        Map<ClassLoader, Set<ClassLoader>> children = new HashMap<>();
        Set<ClassLoader> roots = new HashSet<>();

        for (ClassLoader loader : uniqueLoaders) {
            ClassLoader parent = getParentLoader(loader);
            if (parent == null || !uniqueLoaders.contains(parent)) {
                roots.add(loader);
            } else {
                children.computeIfAbsent(parent, k -> new HashSet<>()).add(loader);
            }
        }

        // Print tree starting from roots
        for (ClassLoader root : roots) {
            printLoaderTree(sb, root, "", children, loadedClasses);
        }

        return sb.toString();
    }

    private void printLoaderTree(StringBuilder sb, ClassLoader loader, String prefix,
                                 Map<ClassLoader, Set<ClassLoader>> children, Class<?>[] loadedClasses) {
        String loaderName = loader == null ? "Bootstrap ClassLoader" : loader.getClass().getSimpleName();
        int classCount = 0;

        for (Class<?> clazz : loadedClasses) {
            if (clazz.getClassLoader() == loader) {
                classCount++;
            }
        }

        sb.append(prefix).append(loaderName);
        sb.append(" (").append(classCount).append(" classes)");
        if (loader != null) {
            sb.append(" [").append(Integer.toHexString(loader.hashCode())).append("]");
        }
        sb.append("\n");

        Set<ClassLoader> childLoaders = children.get(loader);
        if (childLoaders != null) {
            for (ClassLoader child : childLoaders) {
                printLoaderTree(sb, child, prefix + "  ├─ ", children, loadedClasses);
            }
        }
    }

    private String showClassLoaderStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ClassLoader Statistics ===\n");

        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        Map<String, Integer> loaderTypeCount = new HashMap<>();
        Map<ClassLoader, Integer> loaderClassCount = new HashMap<>();
        Map<ClassLoader, Set<String>> loaderPackages = new HashMap<>();

        for (Class<?> clazz : loadedClasses) {
            ClassLoader loader = clazz.getClassLoader();
            String loaderType = loader == null ? "Bootstrap" : loader.getClass().getSimpleName();

            loaderTypeCount.put(loaderType, loaderTypeCount.getOrDefault(loaderType, 0) + 1);
            loaderClassCount.put(loader, loaderClassCount.getOrDefault(loader, 0) + 1);

            String packageName = getPackageName(clazz.getName());
            loaderPackages.computeIfAbsent(loader, k -> new HashSet<>()).add(packageName);
        }

        sb.append("ClassLoader Types:\n");
        for (Map.Entry<String, Integer> entry : loaderTypeCount.entrySet()) {
            sb.append(String.format("  %-30s: %d classes\n", entry.getKey(), entry.getValue()));
        }

        sb.append("\nTop ClassLoaders by Class Count:\n");
        loaderClassCount.entrySet().stream()
            .sorted(Map.Entry.<ClassLoader, Integer>comparingByValue().reversed())
            .limit(10)
            .forEach(entry -> {
                ClassLoader loader = entry.getKey();
                String name = loader == null ? "Bootstrap ClassLoader" : loader.getClass().getName();
                sb.append(String.format("  %-50s: %d classes, %d packages\n",
                    name, entry.getValue(), loaderPackages.get(loader).size()));
            });

        sb.append(String.format("\nTotal: %d classes across %d ClassLoaders\n",
            loadedClasses.length, loaderClassCount.size()));

        return sb.toString();
    }

    private String showClassesByLoader(String pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Classes by ClassLoader ===\n");
        sb.append("Pattern: ").append(pattern).append("\n\n");

        Pattern compiledPattern = Pattern.compile(pattern.replace("*", ".*"), Pattern.CASE_INSENSITIVE);
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        Map<ClassLoader, List<String>> matchingLoaders = new HashMap<>();

        for (Class<?> clazz : loadedClasses) {
            ClassLoader loader = clazz.getClassLoader();
            String loaderName = loader == null ? "Bootstrap ClassLoader" : loader.getClass().getName();

            if (compiledPattern.matcher(loaderName).find()) {
                matchingLoaders.computeIfAbsent(loader, k -> new ArrayList<>()).add(clazz.getName());
            }
        }

        if (matchingLoaders.isEmpty()) {
            sb.append("No ClassLoaders found matching pattern: ").append(pattern);
        } else {
            for (Map.Entry<ClassLoader, List<String>> entry : matchingLoaders.entrySet()) {
                ClassLoader loader = entry.getKey();
                List<String> classes = entry.getValue();
                Collections.sort(classes);

                sb.append("ClassLoader: ").append(getLoaderDisplayName(loader)).append("\n");
                sb.append("Classes (").append(classes.size()).append("):\n");

                for (String className : classes) {
                    sb.append("  ").append(className).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String showClassLoaderUrls() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ClassLoader URLs ===\n");

        Set<ClassLoader> uniqueLoaders = new LinkedHashSet<>();
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();

        for (Class<?> clazz : loadedClasses) {
            uniqueLoaders.add(clazz.getClassLoader());
        }

        for (ClassLoader loader : uniqueLoaders) {
            sb.append("ClassLoader: ").append(getLoaderDisplayName(loader)).append("\n");

            if (loader instanceof URLClassLoader) {
                URL[] urls = ((URLClassLoader) loader).getURLs();
                sb.append("URLs (").append(urls.length).append("):\n");
                for (URL url : urls) {
                    sb.append("  ").append(url.toString()).append("\n");

                    // Additional info for file URLs
                    if ("file".equals(url.getProtocol())) {
                        File file = new File(url.getPath());
                        if (file.exists()) {
                            sb.append("    [").append(file.isDirectory() ? "directory" : "file");
                            if (file.isFile()) {
                                sb.append(", ").append(formatSize(file.length()));
                            }
                            sb.append("]\n");
                        } else {
                            sb.append("    [not found]\n");
                        }
                    }
                }
            } else {
                sb.append("  URLs not available (not a URLClassLoader)\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private String findClassInLoaders(String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Find Class in ClassLoaders ===\n");
        sb.append("Searching for: ").append(className).append("\n\n");

        Pattern pattern = Pattern.compile(className.replace("*", ".*"), Pattern.CASE_INSENSITIVE);
        Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        Map<ClassLoader, List<Class<?>>> matches = new HashMap<>();

        for (Class<?> clazz : loadedClasses) {
            if (pattern.matcher(clazz.getName()).find()) {
                matches.computeIfAbsent(clazz.getClassLoader(), k -> new ArrayList<>()).add(clazz);
            }
        }

        if (matches.isEmpty()) {
            sb.append("No classes found matching: ").append(className);
        } else {
            int totalMatches = 0;
            for (Map.Entry<ClassLoader, List<Class<?>>> entry : matches.entrySet()) {
                ClassLoader loader = entry.getKey();
                List<Class<?>> classes = entry.getValue();
                totalMatches += classes.size();

                sb.append("ClassLoader: ").append(getLoaderDisplayName(loader)).append("\n");
                sb.append("Matches (").append(classes.size()).append("):\n");

                for (Class<?> clazz : classes) {
                    sb.append("  ").append(clazz.getName());

                    // Show code source if available
                    ProtectionDomain pd = clazz.getProtectionDomain();
                    if (pd != null) {
                        CodeSource cs = pd.getCodeSource();
                        if (cs != null && cs.getLocation() != null) {
                            sb.append(" [").append(cs.getLocation()).append("]");
                        }
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("Total matches: ").append(totalMatches).append(" classes in ")
              .append(matches.size()).append(" ClassLoaders\n");
        }

        return sb.toString();
    }

    private String getLoaderDisplayName(ClassLoader loader) {
        if (loader == null) {
            return "Bootstrap ClassLoader";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(loader.hashCode());
    }

    private String getParentLoaderName(ClassLoader loader) {
        if (loader == null) {
            return "null";
        }
        ClassLoader parent = loader.getParent();
        if (parent == null) {
            return "Bootstrap ClassLoader";
        }
        return parent.getClass().getSimpleName() + "@" + Integer.toHexString(parent.hashCode());
    }

    private ClassLoader getParentLoader(ClassLoader loader) {
        return loader == null ? null : loader.getParent();
    }

    private String getPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot == -1 ? "<default>" : className.substring(0, lastDot);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    @Override
    public String getDescription() {
        return "Analyze ClassLoader hierarchy and loaded classes";
    }
}
