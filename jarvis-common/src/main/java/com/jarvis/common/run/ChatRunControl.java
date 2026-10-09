package com.jarvis.common.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Lets the user steer a chat run that is still working, like in Claude Code or Codex: messages sent
 * mid-run are queued here and the agent loop picks them up at its next step ({@link #drain}), and
 * "Stop" marks the run cancelled ({@link #cancel}) so the loop ends at the next checkpoint and the
 * running thread is interrupted. Keyed by conversation id - one active run per conversation.
 */
public final class ChatRunControl {

    private static final Map<String, Run> RUNS = new ConcurrentHashMap<>();

    private ChatRunControl() {
    }

    /**
     * Registers a starting run.
     *
     * @param conversationId conversation id
     * @param worker thread executing the run (interrupted on cancel), may be null
     * @return handle for {@link #close(String, Object)}
     */
    public static Object open(String conversationId, Thread worker) {
        Run run = new Run(worker);
        if (conversationId != null && !conversationId.isBlank()) {
            RUNS.put(conversationId, run);
        }
        return run;
    }

    /**
     * Queues a message for the running agent.
     *
     * @param conversationId conversation id
     * @param message user text
     * @return false when no run is active (the caller should send it as a normal message instead)
     */
    public static boolean post(String conversationId, String message) {
        Run run = conversationId == null ? null : RUNS.get(conversationId);
        if (run == null || run.cancelled || message == null || message.isBlank()) {
            return false;
        }
        run.inbox.add(message.strip());
        return true;
    }

    /**
     * Takes all queued messages.
     *
     * @param conversationId conversation id
     * @return queued messages, oldest first
     */
    public static List<String> drain(String conversationId) {
        Run run = conversationId == null ? null : RUNS.get(conversationId);
        List<String> messages = new ArrayList<>();
        if (run != null) {
            String message;
            while ((message = run.inbox.poll()) != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * Returns whether messages wait to be picked up.
     *
     * @param conversationId conversation id
     * @return true when the inbox is not empty
     */
    public static boolean hasPending(String conversationId) {
        Run run = conversationId == null ? null : RUNS.get(conversationId);
        return run != null && !run.inbox.isEmpty();
    }

    /**
     * Stops a run: marks it cancelled and interrupts its worker thread.
     *
     * @param conversationId conversation id
     * @return false when no run is active
     */
    public static boolean cancel(String conversationId) {
        Run run = conversationId == null ? null : RUNS.get(conversationId);
        if (run == null) {
            return false;
        }
        run.cancelled = true;
        Thread worker = run.worker;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
        return true;
    }

    /**
     * Returns whether the run was stopped by the user.
     *
     * @param conversationId conversation id
     * @return true when cancelled
     */
    public static boolean isCancelled(String conversationId) {
        Run run = conversationId == null ? null : RUNS.get(conversationId);
        return run != null && run.cancelled;
    }

    /**
     * Throws {@link ChatRunCancelledException} when the run was stopped.
     *
     * @param conversationId conversation id
     */
    public static void checkNotCancelled(String conversationId) {
        if (isCancelled(conversationId)) {
            throw new ChatRunCancelledException();
        }
    }

    /**
     * Ends a run (only the run the handle belongs to - a newer run of the same conversation stays).
     *
     * @param conversationId conversation id
     * @param handle handle returned by {@link #open}
     * @return messages that arrived too late to be used
     */
    public static List<String> close(String conversationId, Object handle) {
        if (conversationId == null || RUNS.get(conversationId) != handle) {
            return List.of();
        }
        List<String> leftovers = drain(conversationId);
        RUNS.remove(conversationId, handle);
        leftovers.addAll(((Run) handle).drainAll()); // anything posted in between
        return leftovers;
    }

    private static final class Run {
        private final Thread worker;
        private final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
        private volatile boolean cancelled;

        private Run(Thread worker) {
            this.worker = worker;
        }

        private List<String> drainAll() {
            List<String> messages = new ArrayList<>();
            String message;
            while ((message = inbox.poll()) != null) {
                messages.add(message);
            }
            return messages;
        }
    }
}
