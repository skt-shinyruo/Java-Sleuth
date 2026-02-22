package com.javasleuth.core.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个简单的线程安全环形缓冲，用于保留最近 N 条数据。
 *
 * <p>说明：</p>
 * <ul>
 *   <li>core 侧自带该实现，避免依赖 bootstrap 可见域的实现细节。</li>
 *   <li>实现优先保证行为可预期（诊断输出场景），而非极致吞吐。</li>
 * </ul>
 */
public final class RingBuffer<T> {
    private final Object[] buffer;
    private int writeIndex = 0;
    private int size = 0;

    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.buffer = new Object[capacity];
    }

    public int capacity() {
        return buffer.length;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void add(T item) {
        buffer[writeIndex] = item;
        writeIndex = (writeIndex + 1) % buffer.length;
        if (size < buffer.length) {
            size++;
        }
    }

    public synchronized List<T> snapshot() {
        if (size == 0) {
            return Collections.emptyList();
        }
        List<T> out = new ArrayList<>(size);
        int start = (writeIndex - size + buffer.length) % buffer.length;
        for (int i = 0; i < size; i++) {
            int idx = (start + i) % buffer.length;
            @SuppressWarnings("unchecked")
            T v = (T) buffer[idx];
            out.add(v);
        }
        return out;
    }

    public synchronized List<T> tail(int maxItems) {
        if (maxItems <= 0 || size == 0) {
            return Collections.emptyList();
        }
        int n = Math.min(maxItems, size);
        List<T> out = new ArrayList<>(n);
        int start = (writeIndex - n + buffer.length) % buffer.length;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % buffer.length;
            @SuppressWarnings("unchecked")
            T v = (T) buffer[idx];
            out.add(v);
        }
        return out;
    }
}

