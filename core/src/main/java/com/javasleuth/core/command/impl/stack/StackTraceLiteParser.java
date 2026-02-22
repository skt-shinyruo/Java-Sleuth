package com.javasleuth.core.command.impl.stack;

import java.util.ArrayList;
import java.util.List;

public final class StackTraceLiteParser {

    public ParseResult parse(String[] args) {
        if (args == null || args.length < 3) {
            return ParseResult.help(args);
        }

        String classPattern = args[1];
        String methodPattern = args[2];

        boolean background = false;
        boolean showHelp = false;
        int maxCount = 10;
        long timeoutSeconds = 30;
        int depth = 20;

        List<String> sanitized = new ArrayList<>(args.length);
        sanitized.add(args[0]);
        sanitized.add(classPattern);
        sanitized.add(methodPattern);

        for (int i = 3; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }

            if ("--bg".equals(a)) {
                background = true;
                continue;
            }

            if ("-h".equals(a) || "--help".equals(a)) {
                showHelp = true;
                sanitized.add(a);
                continue;
            }

            if ("-n".equals(a) || "--count".equals(a)) {
                sanitized.add(a);
                if (i + 1 < args.length) {
                    String raw = args[++i];
                    sanitized.add(raw);
                    maxCount = parseInt(raw, 10);
                }
                continue;
            }

            if ("-t".equals(a) || "--timeout".equals(a)) {
                sanitized.add(a);
                if (i + 1 < args.length) {
                    String raw = args[++i];
                    sanitized.add(raw);
                    timeoutSeconds = parseLong(raw, 30);
                }
                continue;
            }

            if ("--depth".equals(a)) {
                sanitized.add(a);
                if (i + 1 < args.length) {
                    String raw = args[++i];
                    sanitized.add(raw);
                    depth = parseInt(raw, 20);
                }
                continue;
            }

            sanitized.add(a);
        }

        if (timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
        if (maxCount <= 0) {
            maxCount = 1;
        }
        depth = Math.max(1, Math.min(depth, 200));

        return new ParseResult(
            false,
            showHelp,
            background,
            classPattern,
            methodPattern,
            maxCount,
            timeoutSeconds,
            depth,
            sanitized.toArray(new String[0])
        );
    }

    private int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static final class ParseResult {
        private final boolean invalid;
        private final boolean showHelp;
        private final boolean background;
        private final String classPattern;
        private final String methodPattern;
        private final int maxCount;
        private final long timeoutSeconds;
        private final int depth;
        private final String[] sanitizedArgs;

        private ParseResult(
            boolean invalid,
            boolean showHelp,
            boolean background,
            String classPattern,
            String methodPattern,
            int maxCount,
            long timeoutSeconds,
            int depth,
            String[] sanitizedArgs
        ) {
            this.invalid = invalid;
            this.showHelp = showHelp;
            this.background = background;
            this.classPattern = classPattern;
            this.methodPattern = methodPattern;
            this.maxCount = maxCount;
            this.timeoutSeconds = timeoutSeconds;
            this.depth = depth;
            this.sanitizedArgs = sanitizedArgs;
        }

        static ParseResult help(String[] args) {
            return new ParseResult(true, true, false, null, null, 0, 0, 0, args);
        }

        public boolean isInvalid() {
            return invalid;
        }

        public boolean isShowHelp() {
            return showHelp;
        }

        public boolean isBackground() {
            return background;
        }

        public String getClassPattern() {
            return classPattern;
        }

        public String getMethodPattern() {
            return methodPattern;
        }

        public int getMaxCount() {
            return maxCount;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public int getDepth() {
            return depth;
        }

        public String[] getSanitizedArgs() {
            return sanitizedArgs;
        }
    }
}
