package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps WebSocket chat runs alive independently of the socket that started them.
 *
 * <p>A long agentic request (many tool-loop turns, a multi-minute build, an MCP call) used to be
 * bound to the one WebSocket session that submitted it: if that socket dropped - Wi-Fi hiccup, a
 * proxy idle timeout, laptop sleep - the client declared the request failed while Core kept working
 * and then delivered the answer into a closed session. Every outgoing frame of a run now gets a
 * monotonically increasing {@code seq}, is buffered (bounded), and is fanned out to whichever
 * sessions are currently attached. A reconnecting client sends {@code CHAT_RESUME} with the last
 * {@code seq} it saw, gets the missed frames replayed, and keeps streaming. Running runs also emit a
 * periodic {@code HEARTBEAT} so the client (and any proxy in between) knows work is still going on.</p>
 */
public class ChatRunRegistry implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatRunRegistry.class);

    private final ObjectMapper objectMapper;
    private final int maxBufferedFrames;
    private final Duration finishedRetention;
    private final Map<String, ChatRun> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    /**
     * Creates the registry.
     *
     * @param objectMapper JSON mapper
     * @param heartbeatInterval interval between heartbeats for running runs; non-positive disables
     * @param maxBufferedFrames frames kept per run for replay
     * @param finishedRetention how long a finished run stays resumable
     */
    public ChatRunRegistry(ObjectMapper objectMapper, Duration heartbeatInterval, int maxBufferedFrames, Duration finishedRetention) {
        this.objectMapper = objectMapper;
        this.maxBufferedFrames = Math.max(100, maxBufferedFrames);
        this.finishedRetention = finishedRetention == null ? Duration.ofMinutes(15) : finishedRetention;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jarvis-chat-run-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        if (heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative()) {
            long millis = heartbeatInterval.toMillis();
            scheduler.scheduleWithFixedDelay(this::tick, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Starts a run and attaches the submitting session.
     *
     * @param userId owner user id
     * @param conversationId conversation id
     * @param session submitting session
     * @return new run
     */
    public ChatRun start(String userId, String conversationId, WebSocketSession session) {
        return register(userId, java.util.UUID.randomUUID().toString(), conversationId, "", session).run();
    }

    public record Registration(ChatRun run, boolean created) {}
    public synchronized Registration register(String owner, String requestId, String conversationId, String fingerprint, WebSocketSession session) {
        ChatRun existing = runs.get(key(owner, requestId));
        if (existing != null) {
            if (!existing.conversationId.equals(conversationId) || !existing.fingerprint.equals(fingerprint))
                throw new IllegalArgumentException("Request ID already belongs to a different payload");
            return new Registration(existing, false);
        }
        if (runs.size() >= 2048) throw new IllegalStateException("Retained request capacity reached");
        if (runs.values().stream().anyMatch(r -> r.requestId.equals(requestId) && !r.userId.equals(owner)))
            throw new IllegalArgumentException("Request ID unavailable; generate a fresh UUID");
        ChatRun run = new ChatRun(owner, requestId, conversationId, fingerprint);
        run.attach(session); runs.put(key(owner, requestId), run);
        return new Registration(run, true);
    }
    public Optional<ChatRun> findRequest(String owner, String requestId) {
        return Optional.ofNullable(runs.get(key(owner, requestId)));
    }

    /**
     * Finds a run for resume.
     *
     * @param userId owner user id
     * @param conversationId conversation id
     * @return run, when one is known
     */
    public Optional<ChatRun> find(String userId, String conversationId) {
        var candidates = runs.values().stream().filter(r -> r.userId.equals(userId) && r.conversationId.equals(conversationId)).toList();
        var active = candidates.stream().filter(ChatRun::isRunning).toList();
        return active.size() == 1 ? Optional.of(active.getFirst())
                : active.isEmpty() && candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    /**
     * Detaches a closed session from every run; the runs themselves keep going.
     *
     * @param session closed session
     */
    public void detach(WebSocketSession session) {
        runs.values().forEach(run -> run.detach(session));
    }

    /**
     * Returns the number of runs currently executing.
     *
     * @return running count
     */
    public int runningCount() {
        return (int) runs.values().stream().filter(ChatRun::isRunning).count();
    }

    void tick() {
        Instant now = Instant.now();
        for (Map.Entry<String, ChatRun> entry : runs.entrySet()) {
            ChatRun run = entry.getValue();
            if (run.isRunning()) {
                run.heartbeat();
            } else if (run.finishedAt != null && run.finishedAt.plus(finishedRetention).isBefore(now)) {
                runs.remove(entry.getKey(), run);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private static String key(String userId, String conversationId) {
        return (userId == null ? "" : userId) + "\u0000" + (conversationId == null ? "" : conversationId);
    }

    /**
     * One chat request whose frames outlive any single WebSocket session.
     */
    public final class ChatRun {

        private final String userId;
        private final String conversationId;
        private final String requestId;
        private final String fingerprint;
        private volatile boolean cancellationRequested;
        private volatile boolean executing;
        private final Instant startedAt = Instant.now();
        private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
        private final Deque<Frame> buffer = new ArrayDeque<>();
        private long seq;
        private volatile Instant finishedAt;
        private volatile String lastEvent = "";

        private ChatRun(String userId, String requestId, String conversationId, String fingerprint) {
            this.userId = userId;
            this.requestId = requestId;
            this.fingerprint = fingerprint;
            this.conversationId = conversationId;
        }

        public String requestId() { return requestId; }
        public boolean isExecuting() { return executing && isRunning(); }
        public void executing() { executing = true; }
        public boolean cancellationRequested() { return cancellationRequested; }
        public synchronized boolean cancel() {
            if (!isRunning()) return false;
            cancellationRequested = true; return true;
        }

        public String conversationId() {
            return conversationId;
        }

        public boolean isRunning() {
            return finishedAt == null;
        }

        void attach(WebSocketSession session) {
            if (session != null) {
                sessions.add(session);
            }
        }

        void detach(WebSocketSession session) {
            sessions.remove(session);
        }

        /**
         * Buffers and delivers one frame of this run.
         *
         * @param payload frame payload (serialized to a JSON object)
         */
        public synchronized boolean emitControl(Object payload) {
            if (!isRunning()) return false;
            emit(payload); return true;
        }

        public synchronized void emit(Object payload) {
            if (finishedAt != null) return;
            ObjectNode node = toObject(payload);
            if (node == null) {
                return;
            }
            String text;
            synchronized (this) {
                node.put("seq", ++seq);
                node.put("streamScope", "REQUEST");
                node.put("requestId", requestId);
                node.put("conversationId", conversationId);
                if (!node.has("conversationId") || node.path("conversationId").asText("").isBlank()) {
                    node.put("conversationId", conversationId);
                }
                String event = node.path("event").asText(node.path("type").asText(""));
                if (!event.isBlank() && !"EXECUTION_TRACE".equals(event) && !"JARVIS_LOG".equals(event)
                        && !event.endsWith("TOKEN")) {
                    // Human-meaningful progress for heartbeats, e.g. "TOOL_STARTED pc.SHELL".
                    String tool = node.path("metadata").path("tool").asText(node.path("metadata").path("toolName").asText(""));
                    String operation = node.path("metadata").path("operation").asText("");
                    lastEvent = tool.isBlank() ? event : event + " " + tool + (operation.isBlank() ? "" : "." + operation);
                } else if (event.endsWith("TOKEN")) {
                    lastEvent = "GENERATING";
                }
                text = node.toString();
                buffer.addLast(new Frame(seq, text));
                while (buffer.size() > maxBufferedFrames) {
                    buffer.removeFirst();
                }
            }
            for (WebSocketSession session : sessions) {
                send(session, text);
            }
        }

        /**
         * Emits a terminal frame and marks the run finished.
         *
         * @param payload terminal payload
         */
        public synchronized void finish(Object payload) {
            ObjectNode terminal = toObject(payload);
            if (cancellationRequested && terminal != null && "COMPLETED".equals(terminal.path("type").asText())) {
                terminal.put("type", "CANCELLED"); terminal.put("message", "Zatrzymano na Twoją prośbę");
            }
            emit(terminal == null ? payload : terminal);
            finishedAt = Instant.now();
        }

        /**
         * Attaches a reconnected session and replays frames newer than {@code afterSeq}.
         *
         * @param session reconnected session
         * @param afterSeq last sequence number the client has already seen
         * @return number of replayed frames
         */
        public int resume(WebSocketSession session, long afterSeq) {
            List<Frame> missed;
            synchronized (this) {
                if (afterSeq < 0 || afterSeq > seq) {
                    send(session, objectMapper.valueToTree(Map.of("type", "CHAT_RESUME_REJECTED", "requestId", requestId, "conversationId", conversationId,
                            "seq", 0, "reason", "CURSOR_OUT_OF_RANGE", "lastSeq", seq)).toString());
                    return 0;
                }
                if (!buffer.isEmpty() && afterSeq < buffer.getFirst().seq() - 1)
                    send(session, objectMapper.valueToTree(Map.of("type", "CHAT_REPLAY_GAP", "requestId", requestId, "conversationId", conversationId,
                            "seq", 0, "firstAvailableSeq", buffer.getFirst().seq(), "lastSeq", seq)).toString());
                missed = new ArrayList<>();
                for (Frame frame : buffer) {
                    if (frame.seq() > afterSeq) {
                        missed.add(frame);
                    }
                }
                // Attach inside the lock so no frame can slip between replay and live delivery.
                for (Frame frame : missed) {
                    send(session, frame.text());
                }
                attach(session);
            }
            LOGGER.info("[CHAT_RUN] resumed conversationId={} afterSeq={} replayed={} running={}",
                    conversationId, afterSeq, missed.size(), isRunning());
            return missed.size();
        }

        private void heartbeat() {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", "HEARTBEAT");
            node.put("conversationId", conversationId);
            node.put("elapsedMs", Duration.between(startedAt, Instant.now()).toMillis());
            node.put("lastEvent", lastEvent);
            synchronized (this) {
                node.put("lastSeq", seq);
            }
            emit(node);
        }

        private ObjectNode toObject(Object payload) {
            JsonNode node = payload instanceof JsonNode json ? json.deepCopy() : objectMapper.valueToTree(payload);
            return node instanceof ObjectNode object ? object : null;
        }
    }

    private void send(WebSocketSession session, String text) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            boolean interrupted = Thread.interrupted();
            try {
                session.sendMessage(new TextMessage(text));
            } catch (IllegalStateException | IOException exception) {
                LOGGER.debug("[CHAT_RUN] could not deliver frame: {}", exception.getMessage());
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private record Frame(long seq, String text) {
    }
}
