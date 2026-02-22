package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.foundation.security.SecurityValidator;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

public class DumpCommand implements Command {
    private final Instrumentation instrumentation;

    public DumpCommand(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public String execute(String[] args) throws Exception {
        if (args.length < 2) {
            return getHelp();
        }

        String classPattern = args[1];
        String outputDir = "./dump";
        int limit = 50;

        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            if ("--output".equals(a) && i + 1 < args.length) {
                outputDir = args[++i];
            } else if ("--limit".equals(a) && i + 1 < args.length) {
                try {
                    limit = Integer.parseInt(args[++i]);
                } catch (NumberFormatException ignored) {
                    limit = 50;
                }
            } else if ("-h".equals(a) || "--help".equals(a)) {
                return getHelp();
            }
        }

        if (outputDir.contains("..")) {
            return "Invalid output path (.. not allowed): " + outputDir;
        }

        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c == null) {
                continue;
            }
            if (WildcardMatcher.matches(c.getName(), classPattern)) {
                matches.add(c);
                if (matches.size() >= limit) {
                    break;
                }
            }
        }

        if (matches.isEmpty()) {
            return "No classes found for pattern: " + classPattern;
        }

        int dumped = 0;
        int skipped = 0;
        for (Class<?> c : matches) {
            String className = c.getName();
            String resourceName = className.replace('.', '/') + ".class";
            ClassLoader loader = c.getClassLoader();
            InputStream in = loader != null ? loader.getResourceAsStream(resourceName) : ClassLoader.getSystemResourceAsStream(resourceName);
            if (in == null) {
                skipped++;
                continue;
            }

            String outPath = outputDir + "/" + resourceName;
            if (!SecurityValidator.canWriteFile(outPath)) {
                in.close();
                skipped++;
                continue;
            }

            File f = new File(outPath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (InputStream is = in; FileOutputStream fos = new FileOutputStream(f)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                dumped++;
            } catch (Exception e) {
                skipped++;
            }
        }

        return "Dump completed. matched=" + matches.size() + ", dumped=" + dumped + ", skipped=" + skipped +
            ", outputDir=" + outputDir;
    }

    private String getHelp() {
        return "Dump command usage:\n" +
            "  dump <class-pattern> [--output <dir>] [--limit <n>]\n" +
            "Examples:\n" +
            "  dump com.example.* --output ./dump --limit 50\n";
    }

    @Override
    public String getDescription() {
        return "Dump class bytecode from classpath resources to disk (simplified)";
    }
}
