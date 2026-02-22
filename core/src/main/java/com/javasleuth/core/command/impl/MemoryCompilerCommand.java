package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.compiler.MemoryJavaCompiler;
import com.javasleuth.core.compiler.MemoryJavaCompiler.CompilationResult;
import com.javasleuth.foundation.security.SecurityValidator;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class MemoryCompilerCommand implements Command {

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return getHelp();
        }

        String sourceFilePath = args[1];

        // Parse options
        boolean verbose = false;
        String outputDir = null;
        String className = null;
        Charset charset = StandardCharsets.UTF_8;

        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-e":
                case "--encoding":
                    if (i + 1 < args.length) {
                        String enc = args[++i];
                        try {
                            charset = Charset.forName(enc);
                        } catch (Exception e) {
                            return "Invalid encoding: " + enc;
                        }
                    }
                    break;
                case "-o":
                case "--output":
                    if (i + 1 < args.length) {
                        outputDir = args[++i];
                    }
                    break;
                case "-c":
                case "--class":
                    if (i + 1 < args.length) {
                        className = args[++i];
                    }
                    break;
                case "-h":
                case "--help":
                    return getHelp();
            }
        }

        return compileJavaSource(sourceFilePath, className, outputDir, charset, verbose);
    }

    private String compileJavaSource(String sourceFilePath, String className, String outputDir, Charset charset, boolean verbose) {
        try {
            // Check if source file exists
            File sourceFile = new File(sourceFilePath);
            if (!sourceFile.exists()) {
                return "Source file not found: " + sourceFilePath;
            }
            if (!SecurityValidator.canReadFile(sourceFilePath)) {
                return "Source file path not allowed: " + sourceFilePath;
            }

            // Read source code
            Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
            String sourceCode = new String(Files.readAllBytes(Paths.get(sourceFilePath)), cs);

            // Extract class name if not provided
            if (className == null) {
                className = extractClassName(sourceCode, sourceFile.getName());
                if (className == null) {
                    return "Could not determine class name. Please specify with -c option.";
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("Memory Compiler - Compiling Java Source\n");
            result.append("Source file: ").append(sourceFilePath).append("\n");
            result.append("Class name: ").append(className).append("\n");

            if (verbose) {
                result.append("Source code length: ").append(sourceCode.length()).append(" characters\n");
            }

            // Compile in memory
            MemoryJavaCompiler compiler = new MemoryJavaCompiler();
            try {
                CompilationResult compilationResult = compiler.compile(className, sourceCode);

                if (compilationResult.isSuccess()) {
                    result.append("\n✅ Compilation successful!\n");

                    Map<String, byte[]> compiledClasses = compilationResult.getCompiledClasses();
                    result.append("Compiled classes: ").append(compiledClasses.size()).append("\n");

                    for (Map.Entry<String, byte[]> entry : compiledClasses.entrySet()) {
                        String compiledClassName = entry.getKey();
                        byte[] classBytes = entry.getValue();

                        result.append("  - ").append(compiledClassName)
                              .append(" (").append(classBytes.length).append(" bytes)\n");

                        // Save to file if output directory is specified
                        if (outputDir != null) {
                            try {
                                saveClassFile(compiledClassName, classBytes, outputDir);
                                result.append("    Saved to: ").append(getClassFilePath(compiledClassName, outputDir)).append("\n");
                            } catch (IOException ioe) {
                                result.append("    Save failed: ").append(ioe.getMessage()).append("\n");
                            }
                        }
                    }

                    // Show warnings if any
                    if (compilationResult.hasWarnings()) {
                        result.append("\n⚠️  Compilation warnings:\n");
                        for (Diagnostic<? extends JavaFileObject> diagnostic : compilationResult.getDiagnostics()) {
                            if (diagnostic.getKind() == Diagnostic.Kind.WARNING) {
                                result.append("  Warning: ").append(diagnostic.getMessage(null)).append("\n");
                            }
                        }
                    }

                    if (outputDir == null) {
                        result.append("\nNote: Classes compiled in memory only. Use -o option to save to disk.\n");
                        result.append("Use 'redefine' command to hot-swap the compiled class into a running JVM.");
                    }

                } else {
                    result.append("\n❌ Compilation failed!\n");
                    result.append("Errors:\n");

                    for (Diagnostic<? extends JavaFileObject> diagnostic : compilationResult.getDiagnostics()) {
                        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                            result.append("  Error");
                            if (diagnostic.getLineNumber() != Diagnostic.NOPOS) {
                                result.append(" at line ").append(diagnostic.getLineNumber());
                            }
                            result.append(": ").append(diagnostic.getMessage(null)).append("\n");
                        }
                    }
                }

                if (verbose && !compilationResult.getDiagnostics().isEmpty()) {
                    result.append("\nAll diagnostics:\n");
                    for (Diagnostic<? extends JavaFileObject> diagnostic : compilationResult.getDiagnostics()) {
                        result.append("  ").append(diagnostic.getKind())
                              .append(": ").append(diagnostic.getMessage(null)).append("\n");
                    }
                }
            } finally {
                try {
                    compiler.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }

            return result.toString();

        } catch (Exception e) {
            return "❌ Compilation error: " + e.getMessage() +
                   (e.getCause() != null ? "\nCause: " + e.getCause().getMessage() : "");
        }
    }

    private String extractClassName(String sourceCode, String fileName) {
        String pkg = extractPackage(sourceCode);
        String simple = extractPrimaryTypeName(sourceCode, fileName);
        if (simple == null) {
            return null;
        }
        return pkg == null ? simple : (pkg + "." + simple);
    }

    private String extractPrimaryTypeName(String sourceCode, String fileName) {
        // Very small parser: find first class/interface/enum declaration.
        String[] lines = sourceCode.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("public class ") || t.startsWith("class ") ||
                t.startsWith("public interface ") || t.startsWith("interface ") ||
                t.startsWith("public enum ") || t.startsWith("enum ")) {
                String[] parts = t.split("\\s+");
                for (int i = 0; i < parts.length - 1; i++) {
                    if ("class".equals(parts[i]) || "interface".equals(parts[i]) || "enum".equals(parts[i])) {
                        String name = parts[i + 1];
                        int genericStart = name.indexOf('<');
                        if (genericStart > 0) {
                            name = name.substring(0, genericStart);
                        }
                        int braceStart = name.indexOf('{');
                        if (braceStart > 0) {
                            name = name.substring(0, braceStart);
                        }
                        int implementsStart = name.indexOf("implements");
                        if (implementsStart > 0) {
                            name = name.substring(0, implementsStart);
                        }
                        return name.trim();
                    }
                }
            }
        }

        // Fallback: use filename without extension
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - 5);
        }

        return null;
    }

    private String extractPackage(String sourceCode) {
        String[] lines = sourceCode.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("package ") && t.endsWith(";")) {
                String body = t.substring("package ".length(), t.length() - 1).trim();
                if (!body.isEmpty()) {
                    return body;
                }
            }
        }
        return null;
    }

    private void saveClassFile(String className, byte[] classBytes, String outputDir) throws IOException {
        String classFilePath = getClassFilePath(className, outputDir);
        if (!SecurityValidator.canWriteFile(classFilePath)) {
            throw new IOException("Output path not allowed: " + classFilePath);
        }
        File classFile = new File(classFilePath);

        // Create parent directories if they don't exist
        classFile.getParentFile().mkdirs();

        Files.write(Paths.get(classFilePath), classBytes);
    }

    private String getClassFilePath(String className, String outputDir) {
        return outputDir + File.separator + className.replace('.', File.separatorChar) + ".class";
    }

    private String getHelp() {
        return "Memory Compiler (mc) command usage:\n" +
               "  mc <source-file-path> [options]\n\n" +
               "Parameters:\n" +
               "  source-file-path  Path to the Java source file (.java)\n\n" +
               "Options:\n" +
               "  -c, --class <name>     Specify the fully qualified class name\n" +
               "  -o, --output <dir>     Output directory to save compiled .class files\n" +
               "  -e, --encoding <name>  Source file encoding (default: UTF-8)\n" +
               "  -v, --verbose          Show detailed compilation information\n" +
               "  -h, --help             Show this help\n\n" +
               "Examples:\n" +
               "  mc MyClass.java\n" +
               "  mc src/com/example/Service.java -c com.example.Service\n" +
               "  mc MyClass.java -o ./target/classes -v\n" +
               "  mc MyClass.java --encoding UTF-8 -v\n\n" +
               "Workflow:\n" +
               "  1. Use 'mc' to compile Java source to .class files\n" +
               "  2. Use 'redefine' to hot-swap the compiled class into running JVM\n\n" +
               "Note: Memory compilation requires JDK (not JRE) to be available.";
    }

    @Override
    public String getDescription() {
        return "Compile Java source code in memory and optionally save to disk";
    }
}
