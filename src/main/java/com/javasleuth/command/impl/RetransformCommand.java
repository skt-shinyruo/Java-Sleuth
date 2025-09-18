package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class RetransformCommand implements Command {
    private final Instrumentation instrumentation;

    public RetransformCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return getHelp();
        }

        String classPattern = args[1];

        // Parse options
        boolean verbose = false;
        boolean listOnly = false;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-l":
                case "--list":
                    listOnly = true;
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        return retransformClasses(classPattern, verbose, listOnly);
    }

    private String retransformClasses(String classPattern, boolean verbose, boolean listOnly) {
        try {
            if (!instrumentation.isRetransformClassesSupported()) {
                return "❌ Class retransformation is not supported by this JVM";
            }

            // Find matching classes
            List<Class<?>> matchingClasses = new ArrayList<>();
            Pattern pattern = Pattern.compile(classPattern.replace("*", ".*"), Pattern.CASE_INSENSITIVE);

            for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
                if (pattern.matcher(clazz.getName()).find()) {
                    if (instrumentation.isModifiableClass(clazz)) {
                        matchingClasses.add(clazz);
                    }
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("Retransform Classes\n");
            result.append("Pattern: ").append(classPattern).append("\n");
            result.append("Found ").append(matchingClasses.size()).append(" modifiable classes\n\n");

            if (matchingClasses.isEmpty()) {
                result.append("No modifiable classes found matching pattern: ").append(classPattern);
                return result.toString();
            }

            // List matching classes
            result.append("Matching classes:\n");
            for (int i = 0; i < matchingClasses.size(); i++) {
                Class<?> clazz = matchingClasses.get(i);
                result.append(String.format("[%3d] %s", i + 1, clazz.getName()));

                if (verbose) {
                    result.append("\n      ClassLoader: ").append(getClassLoaderName(clazz.getClassLoader()));
                    result.append("\n      Modifiable: ").append(instrumentation.isModifiableClass(clazz));
                    result.append("\n      Array: ").append(clazz.isArray());
                    result.append("\n      Interface: ").append(clazz.isInterface());
                    result.append("\n      Primitive: ").append(clazz.isPrimitive());
                }
                result.append("\n");
            }

            if (listOnly) {
                result.append("\nUse without -l/--list option to perform actual retransformation.");
                return result.toString();
            }

            // Perform retransformation
            result.append("\nPerforming retransformation...\n");

            long startTime = System.currentTimeMillis();
            Class<?>[] classArray = matchingClasses.toArray(new Class<?>[0]);

            try {
                instrumentation.retransformClasses(classArray);
                long duration = System.currentTimeMillis() - startTime;

                result.append("\n✅ Retransformation successful!\n");
                result.append("Processed ").append(classArray.length).append(" classes in ").append(duration).append("ms\n");

                if (verbose) {
                    result.append("\nRetransformed classes:\n");
                    for (Class<?> clazz : classArray) {
                        result.append("  ✓ ").append(clazz.getName()).append("\n");
                    }
                }

                result.append("\nNote: Any active class transformers will be applied to these classes.");

            } catch (Exception e) {
                result.append("\n❌ Retransformation failed: ").append(e.getMessage());

                if (verbose && e.getCause() != null) {
                    result.append("\nCause: ").append(e.getCause().getMessage());
                }

                result.append("\n\nPossible reasons:");
                result.append("\n  - Class structure changes not allowed in retransformation");
                result.append("\n  - Class is not retransformable");
                result.append("\n  - Active transformer encountered an error");
            }

            return result.toString();

        } catch (Exception e) {
            return "❌ Error during retransformation: " + e.getMessage();
        }
    }

    private String getClassLoaderName(ClassLoader classLoader) {
        if (classLoader == null) {
            return "Bootstrap ClassLoader";
        }
        return classLoader.getClass().getSimpleName() + "@" + Integer.toHexString(classLoader.hashCode());
    }

    private String getHelp() {
        return "Retransform command usage:\n" +
               "  retransform <class-pattern> [options]\n\n" +
               "Parameters:\n" +
               "  class-pattern     Pattern to match class names (supports wildcards *)\n\n" +
               "Options:\n" +
               "  -l, --list        List matching classes without retransforming\n" +
               "  -v, --verbose     Show detailed information\n" +
               "  -h, --help        Show this help\n\n" +
               "Examples:\n" +
               "  retransform com.example.*\n" +
               "  retransform *Service* --list\n" +
               "  retransform MyClass -v\n\n" +
               "Purpose:\n" +
               "  Retransform applies any registered class transformers to the specified\n" +
               "  classes. This is useful when you want to apply bytecode modifications\n" +
               "  to classes that were loaded before transformers were registered.\n\n" +
               "Use cases:\n" +
               "  - Apply monitoring/instrumentation to existing classes\n" +
               "  - Reset classes to their original state (remove transformations)\n" +
               "  - Apply new transformations after modifying transformer logic\n\n" +
               "Note: Only modifiable classes can be retransformed.";
    }

    @Override
    public String getDescription() {
        return "Retransform loaded classes to apply active transformers";
    }
}