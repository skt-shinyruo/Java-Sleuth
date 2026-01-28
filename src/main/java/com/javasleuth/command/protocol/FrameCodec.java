package com.javasleuth.command.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;

public class FrameCodec {
    private FrameCodec() {}

    public static void writeFrame(PrintWriter writer, Frame frame, int maxPayloadSize) {
        if (frame == null) {
            return;
        }

        switch (frame.getType()) {
            case END:
                writer.println("END");
                return;
            case ERR:
            case DATA:
                writePayload(writer, frame.getType(), frame.getPayload(), maxPayloadSize);
                return;
            default:
                break;
        }
    }

    public static void writePayload(PrintWriter writer, Frame.Type type, String payload, int maxPayloadSize) {
        if (payload == null) {
            payload = "";
        }
        if (maxPayloadSize <= 0) {
            maxPayloadSize = payload.length();
        }

        String normalized = payload.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0) {
            writer.println(type.name() + " 0");
            writer.println("");
            return;
        }

        for (String line : lines) {
            if (line.isEmpty()) {
                writer.println(type.name() + " 0");
                writer.println("");
                continue;
            }
            int offset = 0;
            while (offset < line.length()) {
                int end = Math.min(line.length(), offset + maxPayloadSize);
                String chunk = line.substring(offset, end);
                writer.println(type.name() + " " + chunk.length());
                writer.println(chunk);
                offset = end;
            }
        }
    }

    public static Frame readFrame(BufferedReader reader) throws IOException {
        String header = reader.readLine();
        if (header == null) {
            return null;
        }

        if ("END".equals(header)) {
            return Frame.end();
        }

        if (header.startsWith("DATA ") || header.startsWith("ERR ")) {
            Frame.Type type = header.startsWith("DATA ") ? Frame.Type.DATA : Frame.Type.ERR;
            String payload = reader.readLine();
            return type == Frame.Type.DATA ? Frame.data(payload) : Frame.err(payload);
        }

        return Frame.err(header);
    }
}
