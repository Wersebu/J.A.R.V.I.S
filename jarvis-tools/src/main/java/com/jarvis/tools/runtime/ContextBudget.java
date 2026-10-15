package com.jarvis.tools.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.ModelMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a long agent loop inside the model's context window without silently losing information:
 * oversized tool results are shortened <em>visibly</em> (head + tail + an {@code outputId} to read the
 * rest with the {@code output} tool), and when the whole conversation grows past its budget the
 * oldest tool results are replaced by short stubs that also point to the stored full text.
 */
public final class ContextBudget {

    private static final int MIN_STRING_CHARS = 200;

    private ContextBudget() {
    }

    /**
     * Serializes {@code value}, shortening it only when it exceeds {@code maxChars}.
     *
     * @param value tool result view
     * @param maxChars budget for this one result
     * @param store where the full text is kept (may be null)
     * @param objectMapper JSON mapper
     * @return JSON text for the model
     */
    public static String fit(Map<String, Object> value, int maxChars, ToolOutputStore store, ObjectMapper objectMapper) {
        String json = write(objectMapper, value);
        if (json.length() <= maxChars) {
            return json;
        }
        String outputId = store == null ? "" : store.put(render(value));
        String pointer = outputId.isBlank() ? "" : "; read the rest with output__read outputId=" + outputId;
        int stringLimit = Math.max(MIN_STRING_CHARS, maxChars / 2);
        int listLimit = 100;
        String shortened = json;
        for (int attempt = 0; attempt < 12; attempt++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>) shrink(value, stringLimit, listLimit, pointer);
            Map<String, Object> withNote = new LinkedHashMap<>(copy);
            withNote.put("_shortened", outputId.isBlank()
                    ? Map.of("originalChars", json.length())
                    : Map.of("originalChars", json.length(), "outputId", outputId,
                    "note", "This result was too large and was shortened. Use output__read / output__grep with this outputId for the full text."));
            shortened = write(objectMapper, withNote);
            if (shortened.length() <= maxChars) {
                return shortened;
            }
            stringLimit = Math.max(MIN_STRING_CHARS, stringLimit / 2);
            listLimit = Math.max(5, listLimit / 2);
        }
        return shortened.substring(0, Math.min(shortened.length(), maxChars)) + "...[cut" + pointer + "]";
    }

    /**
     * Replaces the oldest tool-result messages with stubs until the conversation fits the budget.
     * The most recent {@code keepRecent} tool results are never touched.
     *
     * @param messages loop messages (modified in place)
     * @param budgetChars total character budget for message contents
     * @param keepRecent number of newest tool results to keep intact
     * @param store where elided full results are kept (may be null)
     * @return number of elided messages
     */
    public static int compactHistory(List<ModelMessage> messages, int budgetChars, int keepRecent, ToolOutputStore store) {
        long total = messages.stream().mapToLong(message -> message.content() == null ? 0 : message.content().length()).sum();
        if (total <= budgetChars) {
            return 0;
        }
        List<Integer> toolIndexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equalsIgnoreCase(messages.get(i).role())) {
                toolIndexes.add(i);
            }
        }
        int elided = 0;
        for (int k = 0; k < toolIndexes.size() - keepRecent && total > budgetChars; k++) {
            int index = toolIndexes.get(k);
            ModelMessage message = messages.get(index);
            String content = message.content() == null ? "" : message.content();
            if (content.length() < 600 || content.startsWith("{\"elided\":true")) {
                continue;
            }
            String outputId = store == null ? "" : store.put(content);
            String stub = "{\"elided\":true,\"tool\":\"" + escape(message.toolName()) + "\",\"originalChars\":" + content.length()
                    + ",\"preview\":\"" + escape(content.substring(0, Math.min(300, content.length()))) + "\""
                    + (outputId.isBlank() ? "" : ",\"outputId\":\"" + outputId + "\"")
                    + ",\"note\":\"Older tool result removed to save context" + (outputId.isBlank() ? "" : "; output__read can show it again")
                    + "\"}";
            messages.set(index, ModelMessage.tool(message.toolCallId(), message.toolName(), stub));
            total -= content.length() - stub.length();
            elided++;
        }
        return elided;
    }

    /** Includes serialized tool definitions and call arguments, unlike a content-only budget.
     * This is a conservative character estimate, not a tokenizer measurement.
     */
    public static int estimatedInputTokens(List<ModelMessage> messages, Object tools, ObjectMapper mapper) {
        try {
            return (int) Math.ceil((mapper.writeValueAsString(messages.stream().map(m -> Map.of(
                    "role", m.role(), "content", m.content(), "toolCalls", m.toolCalls(),
                    "toolCallId", m.toolCallId(), "toolName", m.toolName())).toList()).length()
                    + messages.stream().mapToInt(m -> m.images().size() * 6144).sum()
                    + mapper.writeValueAsString(tools).length()) / 3.0);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot measure model context", e);
        }
    }

    /** Replace old assistant/tool exchanges only after a checkpoint has been obtained.
     * Keep instructions and user steering verbatim and keep complete recent call/result groups.
     */
    public static void checkpointHistory(List<ModelMessage> messages, String checkpoint, int keepRecent) {
        int boundary = messages.size();
        int tools = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("tool".equals(messages.get(i).role())) tools++;
            if (tools >= keepRecent && "assistant".equals(messages.get(i).role())) {
                boundary = i;
                break;
            }
        }
        List<ModelMessage> retained = new ArrayList<>();
        for (int i = 0; i < boundary; i++) {
            var message = messages.get(i);
            if ("system".equals(message.role()) || "user".equals(message.role())) retained.add(message);
        }
        // A model-authored checkpoint is conversation data, never a new privileged instruction.
        retained.add(ModelMessage.assistant("[WORK CHECKPOINT — verify claims against sources]\n" + checkpoint, List.of()));
        retained.addAll(messages.subList(boundary, messages.size()));
        messages.clear();
        messages.addAll(retained);
    }

    private static Object shrink(Object value, int stringLimit, int listLimit, String pointer) {
        if (value instanceof String text) {
            if (text.length() <= stringLimit) {
                return text;
            }
            int head = (int) (stringLimit * 0.45);
            int tail = (int) (stringLimit * 0.45);
            return text.substring(0, head) + "\n...[" + (text.length() - head - tail) + " characters omitted" + pointer + "]...\n"
                    + text.substring(text.length() - tail);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), shrink(item, stringLimit, listLimit, pointer)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (int i = 0; i < Math.min(list.size(), listLimit); i++) {
                copy.add(shrink(list.get(i), stringLimit, listLimit, pointer));
            }
            if (list.size() > listLimit) {
                copy.add("...[" + (list.size() - listLimit) + " more items omitted" + pointer + "]");
            }
            return copy;
        }
        return value;
    }

    /**
     * Human-readable rendering for paging: long strings keep their real line breaks.
     */
    static String render(Object value) {
        StringBuilder out = new StringBuilder();
        render("", value, out);
        return out.toString();
    }

    private static void render(String path, Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> render(path.isEmpty() ? String.valueOf(key) : path + "." + key, item, out));
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                render(path + "[" + i + "]", list.get(i), out);
            }
        } else if (value instanceof String text && (text.length() > 120 || text.contains("\n"))) {
            out.append("### ").append(path).append('\n').append(text);
            if (!text.endsWith("\n")) {
                out.append('\n');
            }
        } else {
            out.append(path).append(": ").append(value).append('\n');
        }
    }

    private static String write(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }
}
