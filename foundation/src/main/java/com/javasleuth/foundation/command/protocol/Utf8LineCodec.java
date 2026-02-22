package com.javasleuth.foundation.command.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Simple UTF-8 line codec that operates directly on InputStream/OutputStream.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Do not mix multiple buffered wrappers on the same socket stream.</li>
 *   <li>Do not read past the newline boundary (no read-ahead beyond what the underlying stream buffers).</li>
 *   <li>Enforce a maximum line size to reduce DoS risk.</li>
 * </ul>
 */
public final class Utf8LineCodec {
    private Utf8LineCodec() {}

    /**
     * Read a single line terminated by '\n'. The returned bytes exclude the trailing '\n' and optional '\r'.
     */
    public static byte[] readLineBytes(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return null;
        }

        int limit = maxBytes <= 0 ? Integer.MAX_VALUE : maxBytes;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(256, limit));

        boolean sawAny = false;
        while (true) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            sawAny = true;
            if (b == '\n') {
                break;
            }
            if (buffer.size() >= limit) {
                throw new IOException("Line too long (maxBytes=" + maxBytes + ")");
            }
            buffer.write(b);
        }

        if (!sawAny) {
            return null;
        }

        byte[] bytes = buffer.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') {
            len -= 1;
        }
        if (len == bytes.length) {
            return bytes;
        }
        byte[] out = new byte[len];
        System.arraycopy(bytes, 0, out, 0, len);
        return out;
    }

    /**
     * Read a single line terminated by '\n'. The returned string excludes the trailing '\n' and optional '\r'.
     *
     * @param in stream to read
     * @param maxBytes maximum bytes per line (<=0 means no limit)
     * @return line string, or null on EOF with no data
     */
    public static String readLine(InputStream in, int maxBytes) throws IOException {
        byte[] bytes = readLineBytes(in, maxBytes);
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeLine(OutputStream out, String line) throws IOException {
        writeLine(out, line, true);
    }

    public static void writeLine(OutputStream out, String line, boolean flush) throws IOException {
        if (out == null) {
            return;
        }
        String v = line != null ? line : "";
        out.write(v.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        if (flush) {
            out.flush();
        }
    }

    public static void writeBytesLine(OutputStream out, byte[] bytes, int offset, int len, boolean flush) throws IOException {
        if (out == null) {
            return;
        }
        if (bytes != null && len > 0) {
            out.write(bytes, offset, len);
        }
        out.write('\n');
        if (flush) {
            out.flush();
        }
    }
}
