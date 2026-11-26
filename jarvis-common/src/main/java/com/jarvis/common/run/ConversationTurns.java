package com.jarvis.common.run;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** FIFO reservations occur at ingress, before workers can race or access conversation history. */
public final class ConversationTurns {
    private static final Map<String, ArrayDeque<Turn>> QUEUES = new HashMap<>();
    private static int outstanding;
    private ConversationTurns() {}
    /** Autonomous writes yield to all already admitted interactive work. */
    public static synchronized Turn reserveIfIdle(String owner,String conversation) {
        if(QUEUES.containsKey(owner+"\u0000"+conversation))return null;
        return reserve(owner,conversation);
    }
    public static synchronized Turn reserve(String owner, String conversation) {
        if (outstanding >= 1024) throw new IllegalStateException("Interactive request queue is full");
        String key = owner + "\u0000" + conversation;
        var queue = QUEUES.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        if (queue.size() >= 64) throw new IllegalStateException("Conversation queue is full");
        Turn turn = new Turn(key); queue.addLast(turn); outstanding++;
        return turn;
    }
    public static final class Turn implements AutoCloseable {
        private final String key;
        private boolean closed;
        private Turn(String key) { this.key = key; }
        public int position() {
            synchronized (ConversationTurns.class) {
                int position = 0;
                for (Turn turn : QUEUES.getOrDefault(key, new ArrayDeque<>())) { if (turn == this) return position; position++; }
                return -1;
            }
        }
        public void await() {
            synchronized (ConversationTurns.class) {
                try {
                    while (!closed && QUEUES.get(key).peekFirst() != this) ConversationTurns.class.wait();
                    if (closed || Thread.currentThread().isInterrupted()) throw new ChatRunCancelledException();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new ChatRunCancelledException();
                }
            }
        }
        @Override public void close() {
            synchronized (ConversationTurns.class) {
                if (closed) return;
                closed = true;
                var queue = QUEUES.get(key); queue.remove(this); outstanding--;
                if (queue.isEmpty()) QUEUES.remove(key);
                ConversationTurns.class.notifyAll();
            }
        }
    }
}
