package com.javasleuth.core.command.impl.tt;

import com.javasleuth.bootstrap.data.TtRecord;
import com.javasleuth.core.util.RingBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core-side TT record store (per-agent-runtime).
 *
 * <p>Records are stored in a bounded ring buffer to keep memory stable and to support
 * {@code tt list/detail/replay} without relying on bootstrap static registries.</p>
 */
public final class TtRecordStore {
    private final int capacity;
    private final AtomicLong recordSeq = new AtomicLong(1);
    private volatile RingBuffer<TtRecord> records;

    public TtRecordStore() {
        this(2000);
    }

    public TtRecordStore(int capacity) {
        int c = capacity <= 0 ? 2000 : capacity;
        this.capacity = c;
        this.records = new RingBuffer<>(c);
    }

    public long nextRecordId() {
        return recordSeq.getAndIncrement();
    }

    public List<TtRecord> list(int n) {
        return records.tail(n);
    }

    public TtRecord find(long recordId) {
        if (recordId <= 0) {
            return null;
        }
        for (TtRecord r : records.snapshot()) {
            if (r != null && r.getRecordId() == recordId) {
                return r;
            }
        }
        return null;
    }

    public void add(TtRecord record) {
        if (record == null) {
            return;
        }
        records.add(record);
    }

    public void clear() {
        records = new RingBuffer<>(capacity);
        recordSeq.set(1);
    }
}

