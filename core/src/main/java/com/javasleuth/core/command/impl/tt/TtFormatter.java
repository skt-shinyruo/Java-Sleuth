package com.javasleuth.core.command.impl.tt;

import com.javasleuth.bootstrap.data.TtRecord;
import java.time.LocalTime;
import java.util.Locale;

public final class TtFormatter {
    private TtFormatter() {}

    public static String formatRecordLine(TtRecord r, int idx) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%3d] ", idx));
        sb.append(String.format("%s ", LocalTime.now().toString()));
        sb.append("id=").append(r.getRecordId()).append(" ");
        sb.append(r.getClassName()).append(".").append(r.getMethodName()).append("()");
        sb.append(" cost=").append(formatNanos(r.getDuration()));
        if (r.getEventType() == TtRecord.EventType.METHOD_EXCEPTION) {
            sb.append(" [EXCEPTION]");
        }
        return sb.toString();
    }

    public static String formatNanos(long nanos) {
        long d = Math.max(0, nanos);
        if (d < 1_000) {
            return d + "ns";
        } else if (d < 1_000_000) {
            return String.format(Locale.ROOT, "%.2fμs", d / 1_000.0);
        } else if (d < 1_000_000_000) {
            return String.format(Locale.ROOT, "%.2fms", d / 1_000_000.0);
        } else {
            return String.format(Locale.ROOT, "%.2fs", d / 1_000_000_000.0);
        }
    }
}
