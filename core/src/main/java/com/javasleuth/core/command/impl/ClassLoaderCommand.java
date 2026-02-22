package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.regex.Pattern;

public class ClassLoaderCommand implements Command {
    private final Instrumentation instrumentation;

    public ClassLoaderCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length > 1 && "--help".equals(args[1])) {
            return getHelpText();
        }

        String subCommand = args.length > 1 ? args[1].toLowerCase() : "list";

        switch (subCommand) {
            case "list":
            case "ls":
                return listClassLoaders();
            case "tree":
                return showClassLoaderTree();
            case "stats":
                return showClassLoaderStats();
            case "classes":
                return showClassesByLoader(args);
            case "urls":
                return showClassLoaderUrls(args);
            case "find":
                return findClassInLoaders(args);
            default:
                // If not a known subcommand, treat as class name for finding
                return findClassInLoaders(new String[]{"find", subCommand});
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

    private String showClassesByLoader(String[] args) {
        if (args.length < 3) {
            return "Usage: classloader classes <loader-pattern>\n" +
                   "Example: classloader classes URLClassLoader";
        }

        String pattern = args[2];
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

    private String showClassLoaderUrls(String[] args) {
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

    private String findClassInLoaders(String[] args) {
        if (args.length < 2) {
            return "Usage: classloader find <class-pattern>\n" +
                   "Example: classloader find String";
        }

        String className = args[1];
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

    private String getHelpText() {
        return "=== ClassLoader Command Help ===\n" +
               "Analyze and inspect ClassLoader hierarchy and loaded classes\n\n" +
               "Usage:\n" +
               "  classloader [subcommand] [options]\n" +
               "  classloader --help                Show this help message\n\n" +
               "Subcommands:\n" +
               "  list, ls                          List all ClassLoaders with basic info\n" +
               "  tree                              Show ClassLoader hierarchy tree\n" +
               "  stats                             Show ClassLoader statistics\n" +
               "  classes <loader-pattern>          Show classes loaded by matching ClassLoaders\n" +
               "  urls                              Show URLs for all URLClassLoaders\n" +
               "  find <class-pattern>              Find classes across all ClassLoaders\n" +
               "  <class-pattern>                   Shortcut for 'find <class-pattern>'\n\n" +
               "Examples:\n" +
               "  classloader list                  List all ClassLoaders\n" +
               "  classloader tree                  Show loader hierarchy\n" +
               "  classloader stats                 Show loader statistics\n" +
               "  classloader classes *URL*         Show classes from URLClassLoaders\n" +
               "  classloader urls                  Show all ClassLoader URLs\n" +
               "  classloader find String           Find String class\n" +
               "  classloader java.lang.*           Find all java.lang classes\n\n" +
               "Information Displayed:\n" +
               "- ClassLoader types and instances\n" +
               "- Parent-child relationships\n" +
               "- Number of classes per loader\n" +
               "- Package distribution\n" +
               "- URLs and classpaths (for URLClassLoaders)\n" +
               "- Code sources and locations\n\n" +
               "Pattern Matching:\n" +
               "- Use * for wildcard matching\n" +
               "- Case-insensitive search\n" +
               "- Supports regex patterns\n\n" +
               "Use Cases:\n" +
               "- Debug classloading issues\n" +
               "- Understand application structure\n" +
               "- Find duplicate classes\n" +
               "- Analyze memory usage by ClassLoader\n" +
               "- Inspect classpath and dependencies\n";
    }

    @Override
    public String getDescription() {
        return "Analyze ClassLoader hierarchy and loaded classes";
    }
}
