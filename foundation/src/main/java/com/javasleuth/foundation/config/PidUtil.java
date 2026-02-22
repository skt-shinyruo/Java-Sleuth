package com.javasleuth.foundation.config;

import java.lang.management.ManagementFactory;

public final class PidUtil {
    private PidUtil() {}

    public static String currentPid() {
        try {
            String name = ManagementFactory.getRuntimeMXBean().getName();
            if (name == null) {
                return "unknown";
            }
            int idx = name.indexOf('@');
            if (idx > 0) {
                return name.substring(0, idx);
            }
            return name;
        } catch (Exception ignore) {
            return "unknown";
        }
    }
}
