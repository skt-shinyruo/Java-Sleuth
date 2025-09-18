package com.javasleuth.compiler;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryJavaCompiler implements AutoCloseable {
    private final JavaCompiler compiler;
    private final DiagnosticCollector<JavaFileObject> diagnostics;
    private final MemoryClassLoader classLoader;
    private final MemoryJavaFileManager fileManager;

    public MemoryJavaCompiler() {
        this.compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Java compiler not available. Make sure you're running on a JDK, not JRE.");
        }

        this.diagnostics = new DiagnosticCollector<>();
        this.classLoader = new MemoryClassLoader();
        StandardJavaFileManager standardManager = compiler.getStandardFileManager(diagnostics, null, null);
        this.fileManager = new MemoryJavaFileManager(standardManager, classLoader);
    }

    public CompilationResult compile(String className, String sourceCode) {
        return compile(Collections.singletonMap(className, sourceCode));
    }

    public CompilationResult compile(Map<String, String> sources) {
        try {
            List<JavaFileObject> sourceFiles = new ArrayList<>();
            for (Map.Entry<String, String> entry : sources.entrySet()) {
                sourceFiles.add(new MemoryJavaFileObject(entry.getKey(), entry.getValue()));
            }

            List<String> options = Arrays.asList(
                "-classpath", System.getProperty("java.class.path")
            );

            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, sourceFiles
            );

            boolean success = task.call();

            return new CompilationResult(success, diagnostics.getDiagnostics(), classLoader.getCompiledClasses());

        } catch (Exception e) {
            List<Diagnostic<? extends JavaFileObject>> errorDiagnostics = new ArrayList<>();
            errorDiagnostics.add(new SimpleDiagnostic("Compilation error: " + e.getMessage()));

            return new CompilationResult(false, errorDiagnostics, Collections.emptyMap());
        }
    }

    public void close() throws IOException {
        fileManager.close();
    }

    // Inner classes for in-memory compilation
    private static class MemoryJavaFileObject extends SimpleJavaFileObject {
        private final String sourceCode;

        public MemoryJavaFileObject(String className, String sourceCode) {
            super(createURI(className), Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }

        private static URI createURI(String className) {
            try {
                return new URI("string:///" + className.replace('.', '/') + Kind.SOURCE.extension);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> compiledClasses = new ConcurrentHashMap<>();

        public void addClass(String className, byte[] classBytes) {
            compiledClasses.put(className, classBytes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] classBytes = compiledClasses.get(name);
            if (classBytes != null) {
                return defineClass(name, classBytes, 0, classBytes.length);
            }
            return super.findClass(name);
        }

        public Map<String, byte[]> getCompiledClasses() {
            return new HashMap<>(compiledClasses);
        }
    }

    private static class MemoryJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final MemoryClassLoader classLoader;

        public MemoryJavaFileManager(StandardJavaFileManager fileManager, MemoryClassLoader classLoader) {
            super(fileManager);
            this.classLoader = classLoader;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                 JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            if (kind == JavaFileObject.Kind.CLASS) {
                return new MemoryClassFileObject(className, classLoader);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }
    }

    private static class MemoryClassFileObject extends SimpleJavaFileObject {
        private final String className;
        private final MemoryClassLoader classLoader;
        private ByteArrayOutputStream outputStream;

        public MemoryClassFileObject(String className, MemoryClassLoader classLoader) {
            super(createURI(className), Kind.CLASS);
            this.className = className;
            this.classLoader = classLoader;
        }

        @Override
        public OutputStream openOutputStream() {
            outputStream = new ByteArrayOutputStream();
            return outputStream;
        }

        public void closeStream() throws IOException {
            if (outputStream != null) {
                byte[] classBytes = outputStream.toByteArray();
                classLoader.addClass(className, classBytes);
                outputStream.close();
            }
        }

        private static URI createURI(String className) {
            try {
                return new URI("memory:///" + className.replace('.', '/') + Kind.CLASS.extension);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class SimpleDiagnostic implements Diagnostic<JavaFileObject> {
        private final String message;

        public SimpleDiagnostic(String message) {
            this.message = message;
        }

        @Override
        public Kind getKind() { return Kind.ERROR; }

        @Override
        public JavaFileObject getSource() { return null; }

        @Override
        public long getPosition() { return NOPOS; }

        @Override
        public long getStartPosition() { return NOPOS; }

        @Override
        public long getEndPosition() { return NOPOS; }

        @Override
        public long getLineNumber() { return NOPOS; }

        @Override
        public long getColumnNumber() { return NOPOS; }

        @Override
        public String getCode() { return null; }

        @Override
        public String getMessage(Locale locale) { return message; }
    }

    public static class CompilationResult {
        private final boolean success;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;
        private final Map<String, byte[]> compiledClasses;

        public CompilationResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics,
                               Map<String, byte[]> compiledClasses) {
            this.success = success;
            this.diagnostics = diagnostics;
            this.compiledClasses = compiledClasses;
        }

        public boolean isSuccess() { return success; }
        public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() { return diagnostics; }
        public Map<String, byte[]> getCompiledClasses() { return compiledClasses; }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
        }

        public boolean hasWarnings() {
            return diagnostics.stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.WARNING);
        }
    }
}