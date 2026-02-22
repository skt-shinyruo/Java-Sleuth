package com.javasleuth.foundation.config;

import java.io.File;

public final class LogPathResolver {
    public String defaultLogPath(String baseName) {
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null || tmp.trim().isEmpty()) {
            tmp = ".";
        }
        String name = (baseName == null || baseName.trim().isEmpty()) ? "sleuth.log" : baseName.trim();
        String pid = PidUtil.currentPid();
        String fileName = appendPidSuffix(name, pid);
        return new File(tmp, fileName).getAbsolutePath();
    }

    static String appendPidSuffix(String fileName, String pid) {
        String p = (pid == null || pid.trim().isEmpty()) ? "unknown" : pid.trim();
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            return fileName.substring(0, dot) + "-" + p + fileName.substring(dot);
        }
        return fileName + "-" + p;
    }
}
