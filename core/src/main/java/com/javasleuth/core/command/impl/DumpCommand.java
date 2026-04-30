package com.javasleuth.core.command.impl;

import com.javasleuth.core.command.Command;
import com.javasleuth.core.command.SpecBackedCommand;
import com.javasleuth.core.command.spec.ArgumentSpec;
import com.javasleuth.core.command.spec.CommandHelpRenderer;
import com.javasleuth.core.command.spec.CommandSpec;
import com.javasleuth.core.command.spec.OptionSpec;
import com.javasleuth.core.command.spec.ParsedCommand;
import com.javasleuth.foundation.security.CommandCapability;
import com.javasleuth.foundation.security.CommandMeta;
import com.javasleuth.foundation.security.SecurityValidator;
import com.javasleuth.foundation.util.WildcardMatcher;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

public class DumpCommand implements Command, SpecBackedCommand {
    private static final CommandSpec SPEC = CommandSpec.builder("dump")
        .description("Dump class bytecode from classpath resources to disk")
        .usage("dump <class-pattern> [--output <dir>] [--limit <n>]")
        .meta(CommandMeta.operator(false, false)
            .withImpact(CommandMeta.ImpactLevel.HIGH)
            .withRateLimit(5)
            .withCapability(CommandCapability.WRITES_DISK))
        .argument(ArgumentSpec.required("class-pattern"))
        .option(OptionSpec.string("output").defaultValue("./dump").build())
        .option(OptionSpec.integer("limit").defaultValue(50).range(1, 100000).build())
        .example("dump com.example.* --output ./dump --limit 50")
        .build();

    private final Instrumentation instrumentation;

    public DumpCommand(Instrumentation instrumentation) {
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

        String classPattern = parsed.argument("class-pattern");
        String outputDir = parsed.stringOption("output");
        int limit = parsed.intOption("limit");

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

    @Override
    public String getDescription() {
        return "Dump class bytecode from classpath resources to disk (simplified)";
    }
}
