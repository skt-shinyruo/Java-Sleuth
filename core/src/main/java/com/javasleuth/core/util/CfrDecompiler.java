package com.javasleuth.core.util;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class CfrDecompiler {
    private CfrDecompiler() {}

    public static String decompileClassBytes(String className, byte[] bytecode, boolean showLineNumbers, boolean verbose) {
        if (className == null || className.trim().isEmpty()) {
            return "// CFR decompiler error: className is empty";
        }
        if (bytecode == null || bytecode.length == 0) {
            return "// CFR decompiler error: bytecode is empty\n// Class: " + className;
        }

        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");
        options.put("hideutf", "false");
        options.put("trackbytecodeloc", showLineNumbers ? "true" : "false");
        options.put("comments", verbose ? "true" : "false");
        options.put("forcetopsort", "true");
        options.put("forloopaggcapture", "true");

        StringBuilder output = new StringBuilder();
        OutputSinkFactory mySink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                return Arrays.asList(SinkClass.STRING, SinkClass.DECOMPILED, SinkClass.DECOMPILED_MULTIVER);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                return new Sink<T>() {
                    @Override
                    public void write(T sinkable) {
                        if (sinkType != SinkType.JAVA || sinkable == null) {
                            return;
                        }
                        if (sinkable instanceof SinkReturns.Decompiled) {
                            output.append(((SinkReturns.Decompiled) sinkable).getJava());
                            return;
                        }
                        if (sinkable instanceof String) {
                            output.append((String) sinkable);
                        }
                    }
                };
            }
        };

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("sleuth-jad-");
            Path classFilePath = tempDir.resolve(className.replace('.', '/') + ".class");
            Path parent = classFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(classFilePath, bytecode);

            CfrDriver driver = new CfrDriver.Builder()
                .withOptions(options)
                .withOutputSink(mySink)
                .build();

            driver.analyse(Collections.singletonList(classFilePath.toString()));

            String result = output.toString();
            if (result.isEmpty()) {
                return "// CFR decompiler failed to produce output\n// Class: " + className;
            }
            return result;
        } catch (Exception e) {
            return "// CFR decompiler error: " + e.getMessage() + "\n" +
                   "// Class: " + className + "\n" +
                   "// Try using a different decompiler or check class accessibility";
        } finally {
            if (tempDir != null) {
                deleteRecursivelyBestEffort(tempDir);
            }
        }
    }

    private static void deleteRecursivelyBestEffort(Path root) {
        if (root == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
