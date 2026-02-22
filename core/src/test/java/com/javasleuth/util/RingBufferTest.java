package com.javasleuth.bootstrap.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class RingBufferTest {
    @Test
    public void keepsMostRecentItems() {
        RingBuffer<Integer> rb = new RingBuffer<>(3);
        rb.add(1);
        rb.add(2);
        rb.add(3);
        assertEquals(3, rb.size());
        assertEquals(Arrays.asList(1, 2, 3), rb.snapshot());

        rb.add(4);
        assertEquals(3, rb.size());
        assertEquals(Arrays.asList(2, 3, 4), rb.snapshot());
        assertEquals(Arrays.asList(3, 4), rb.tail(2));
    }
}
