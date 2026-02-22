package com.javasleuth.bootstrap.monitor;

import com.javasleuth.bootstrap.data.TraceResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates TraceResult events into per-invocation trees.
 *
 * <p>Simplified design:
 * - METHOD_ENTRY/EXIT/EXCEPTION form the traced-method call stack
 * - SUB_METHOD_CALL is attached to the current top traced node (no duration by default)
 */
public final class TraceAggregator {
    public static final class Options {
        private int maxDepth = 10;
        private int maxNodes = 2000;

        public int getMaxDepth() { return maxDepth; }
        public int getMaxNodes() { return maxNodes; }

        public Options withMaxDepth(int maxDepth) { this.maxDepth = Math.max(1, maxDepth); return this; }
        public Options withMaxNodes(int maxNodes) { this.maxNodes = Math.max(100, maxNodes); return this; }
    }

    public static abstract class Item {
        private final String className;
        private final String methodName;
        private final String methodDescriptor;

        protected Item(String className, String methodName, String methodDescriptor) {
            this.className = className;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
        }

        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public String getMethodDescriptor() { return methodDescriptor; }
    }

    public static final class Node extends Item {
        private final long startTime;
        private long duration;
        private boolean exception;
        private final List<Item> children = new ArrayList<>();

        public Node(String className, String methodName, String methodDescriptor, long startTime) {
            super(className, methodName, methodDescriptor);
            this.startTime = startTime;
        }

        public long getStartTime() { return startTime; }
        public long getDuration() { return duration; }
        public boolean isException() { return exception; }
        public List<Item> getChildren() { return children; }

        private void finish(long duration, boolean exception) {
            this.duration = duration;
            this.exception = exception;
        }
    }

    public static final class SubCall extends Item {
        private final long callTime;
        private final long duration;

        public SubCall(String className, String methodName, String methodDescriptor, long callTime, long duration) {
            super(className, methodName, methodDescriptor);
            this.callTime = callTime;
            this.duration = duration;
        }

        public long getCallTime() { return callTime; }
        public long getDuration() { return duration; }
    }

    public static final class Invocation {
        private final String traceId;
        private final long threadId;
        private final String threadName;
        private final Node root;

        public Invocation(String traceId, long threadId, String threadName, Node root) {
            this.traceId = traceId;
            this.threadId = threadId;
            this.threadName = threadName;
            this.root = root;
        }

        public String getTraceId() { return traceId; }
        public long getThreadId() { return threadId; }
        public String getThreadName() { return threadName; }
        public Node getRoot() { return root; }
    }

    private final Options options;
    private final Map<Long, Deque<Node>> stacksByThread = new HashMap<>();
    private final Map<Long, Integer> nodeCountByThread = new HashMap<>();
    // When we hit maxNodes we stop allocating new nodes, but we still need to keep entry/exit balanced
    // so that subsequent exits don't pop real nodes from the stack.
    private final Map<Long, Integer> droppedDepthByThread = new HashMap<>();
    private final List<Invocation> completed = new ArrayList<>();

    public TraceAggregator() {
        this(new Options());
    }

    public TraceAggregator(Options options) {
        this.options = options == null ? new Options() : options;
    }

    public void accept(TraceResult e) {
        if (e == null || e.getEventType() == null) {
            return;
        }
        long tid = e.getThreadId();
        switch (e.getEventType()) {
            case METHOD_ENTRY:
                onEntry(tid, e);
                break;
            case METHOD_EXIT:
                onExit(tid, e, false);
                break;
            case METHOD_EXCEPTION:
                onExit(tid, e, true);
                break;
            case SUB_METHOD_CALL:
                onSubCall(tid, e);
                break;
            default:
                break;
        }
    }

    public List<Invocation> drainCompleted() {
        if (completed.isEmpty()) {
            return new ArrayList<>();
        }
        List<Invocation> out = new ArrayList<>(completed);
        completed.clear();
        return out;
    }

    private void onEntry(long tid, TraceResult e) {
        Deque<Node> stack = stacksByThread.computeIfAbsent(tid, k -> new ArrayDeque<>());
        int currentNodes = nodeCountByThread.getOrDefault(tid, 0);
        if (currentNodes >= options.getMaxNodes()) {
            // Drop further nodes for this thread/invocation to avoid unbounded memory.
            droppedDepthByThread.put(tid, droppedDepthByThread.getOrDefault(tid, 0) + 1);
            return;
        }
        Node node = new Node(e.getClassName(), e.getMethodName(), e.getMethodDescriptor(), e.getStartTime());
        stack.push(node);
        nodeCountByThread.put(tid, currentNodes + 1);
    }

    private void onExit(long tid, TraceResult e, boolean exception) {
        Deque<Node> stack = stacksByThread.get(tid);
        int droppedDepth = droppedDepthByThread.getOrDefault(tid, 0);
        if (droppedDepth > 0) {
            if (droppedDepth == 1) {
                droppedDepthByThread.remove(tid);
                // If we only have dropped frames and no tracked stack, clear state to avoid leaks.
                if (stack == null || stack.isEmpty()) {
                    stacksByThread.remove(tid);
                    nodeCountByThread.remove(tid);
                }
            } else {
                droppedDepthByThread.put(tid, droppedDepth - 1);
            }
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        Node node = stack.pop();
        node.finish(e.getDuration(), exception);

        Node parent = stack.peek();
        if (parent != null && stack.size() < options.getMaxDepth()) {
            parent.getChildren().add(node);
        }

        if (stack.isEmpty()) {
            completed.add(new Invocation(e.getTraceId(), tid, e.getThreadName(), node));
            stacksByThread.remove(tid);
            nodeCountByThread.remove(tid);
            droppedDepthByThread.remove(tid);
        }
    }

    private void onSubCall(long tid, TraceResult e) {
        Deque<Node> stack = stacksByThread.get(tid);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (stack.size() > options.getMaxDepth()) {
            return;
        }
        int currentNodes = nodeCountByThread.getOrDefault(tid, 0);
        if (currentNodes >= options.getMaxNodes()) {
            return;
        }
        Node parent = stack.peek();
        if (parent == null) {
            return;
        }
        // Duration for sub-call may be 0 in current instrumentation; keep field for future extension.
        parent.getChildren().add(new SubCall(e.getClassName(), e.getMethodName(), e.getMethodDescriptor(), e.getStartTime(), e.getDuration()));
        nodeCountByThread.put(tid, currentNodes + 1);
    }
}
