package com.javasleuth.command.impl;

import com.javasleuth.command.Command;
import com.javasleuth.security.SecurityValidator;
import com.javasleuth.util.LoadedClassResolver;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

public class RedefineCommand implements Command {
    private final Instrumentation instrumentation;

    public RedefineCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 3) {
            return getHelp();
        }

        String className = args[1];
        String classFilePath = args[2];

        // Parse options
        boolean verbose = false;
        Integer loaderId = null;
        for (int i = 3; i < args.length; i++) {
            switch (args[i]) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "--loader":
                case "--loader-id":
                case "--loader-hash":
                    if (i + 1 < args.length) {
                        String raw = args[++i];
                        Integer parsed = LoadedClassResolver.parseLoaderId(raw);
                        if (parsed == null) {
                            return "Invalid --loader value: " + raw +
                                " (expected: bootstrap/null/0x1234/1234)";
                        }
                        loaderId = parsed;
                    }
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        return redefineClass(className, classFilePath, loaderId, verbose);
    }

    private String redefineClass(String className, String classFilePath, Integer loaderId, boolean verbose) {
        try {
            // Check if class file exists
            File classFile = new File(classFilePath);
            if (!classFile.exists()) {
                return "Class file not found: " + classFilePath;
            }
            if (!SecurityValidator.canReadFile(classFilePath)) {
                return "Class file path not allowed: " + classFilePath;
            }

            // Read class bytes
            byte[] classBytes = Files.readAllBytes(Paths.get(classFilePath));

            // Find the loaded class
            LoadedClassResolver.Candidate resolved;
            try {
                resolved = LoadedClassResolver.resolveSingle(instrumentation, className, loaderId, true, 200, false);
            } catch (LoadedClassResolver.ResolutionException e) {
                return e.getMessage() +
                    "\nCandidates:\n" + LoadedClassResolver.formatCandidates(e.getCandidates(), 10) +
                    "\nHint: use --loader <loaderId> (e.g. --loader 0x1234 or --loader bootstrap)";
            }
            Class<?> targetClass = resolved.getClazz();
            if (targetClass == null) {
                return "Class not found in loaded classes: " + className;
            }

            // Check if the class is modifiable
            if (!instrumentation.isModifiableClass(targetClass)) {
                return "Class is not modifiable: " + className;
            }

            // Check if redefinition is supported
            if (!instrumentation.isRedefineClassesSupported()) {
                return "Class redefinition is not supported by this JVM";
            }

            StringBuilder result = new StringBuilder();
            result.append("Redefining class: ").append(className).append("\n");
            result.append("Source file: ").append(classFilePath).append("\n");
            result.append("Class bytes size: ").append(classBytes.length).append(" bytes\n");

            if (verbose) {
                result.append("Class loader: ").append(getClassLoaderName(targetClass.getClassLoader())).append("\n");
                result.append("Loader ID: ").append(LoadedClassResolver.formatLoaderId(LoadedClassResolver.loaderId(targetClass.getClassLoader()))).append("\n");
                result.append("Is interface: ").append(targetClass.isInterface()).append("\n");
                result.append("Is array: ").append(targetClass.isArray()).append("\n");
            }

            // Perform the redefinition
            ClassDefinition classDefinition = new ClassDefinition(targetClass, classBytes);
            instrumentation.redefineClasses(classDefinition);

            result.append("\n✅ Class redefinition successful!");

            if (verbose) {
                result.append("\n\nRedefinition completed at: ").append(java.time.LocalDateTime.now());
                result.append("\nAffected class instances will use the new implementation immediately.");
            }

            return result.toString();

        } catch (UnsupportedOperationException e) {
            return "❌ Redefinition failed: " + e.getMessage() +
                   "\nNote: Redefinition has limitations - cannot change class structure (add/remove methods or fields)";
        } catch (ClassFormatError e) {
            return "❌ Invalid class file format: " + e.getMessage();
        } catch (NoClassDefFoundError e) {
            return "❌ Class definition error: " + e.getMessage();
        } catch (Exception e) {
            return "❌ Redefinition failed: " + e.getMessage() +
                   "\nCause: " + (e.getCause() != null ? e.getCause().getMessage() : "Unknown");
        }
    }

    private String getClassLoaderName(ClassLoader classLoader) {
        if (classLoader == null) {
            return "Bootstrap ClassLoader";
        }
        return classLoader.getClass().getName() + "@" + Integer.toHexString(classLoader.hashCode());
    }

    private String getHelp() {
        return "Redefine command usage:\n" +
               "  redefine <class-name> <class-file-path> [options]\n\n" +
               "Parameters:\n" +
               "  class-name        Fully qualified class name (e.g., com.example.MyClass)\n" +
               "  class-file-path   Path to the compiled .class file\n\n" +
               "Options:\n" +
               "  -v, --verbose     Show detailed information\n" +
               "  --loader <id>     Select target ClassLoader when multiple loaded classes match\n" +
               "  -h, --help        Show this help\n\n" +
               "Examples:\n" +
               "  redefine com.example.MyClass /path/to/MyClass.class\n" +
               "  redefine com.example.Service ./target/classes/com/example/Service.class -v\n\n" +
               "Limitations:\n" +
               "  - Cannot change class structure (add/remove methods or fields)\n" +
               "  - Cannot change method signatures\n" +
               "  - Can only modify method implementations\n" +
               "  - Target class must be modifiable and already loaded\n\n" +
               "Note: Use 'mc' command to compile Java source code to .class files in memory.";
    }

    @Override
    public String getDescription() {
        return "Redefine loaded classes with new implementations (hot code reload)";
    }
}
