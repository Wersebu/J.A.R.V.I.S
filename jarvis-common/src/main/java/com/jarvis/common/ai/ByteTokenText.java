package com.jarvis.common.ai;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs text where a model emitted raw byte-fallback tokens instead of characters - e.g. an emoji
 * written into a file as {@code <0xF0><0x9F><0x9B><0x9E>} (seen with gemma via Ollama). Runs of such
 * tokens that form valid UTF-8 are turned back into the real characters; anything else is left as is.
 */
public final class ByteTokenText {

    private static final Pattern RUN = Pattern.compile("(?:<0x[0-9A-Fa-f]{2}>)+");

    private ByteTokenText() {
    }

    /**
     * Decodes byte-token runs.
     *
     * @param text text, may be null
     * @return repaired text (same instance when nothing to do)
     */
    public static String repair(String text) {
        if (text == null || text.indexOf("<0x") < 0) {
            return text;
        }
        Matcher matcher = RUN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        while (matcher.find()) {
            String run = matcher.group();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < run.length(); i += 6) {
                bytes.write(Integer.parseInt(run.substring(i + 3, i + 5), 16));
            }
            String decoded;
            try {
                decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                changed = true;
            } catch (CharacterCodingException exception) {
                decoded = run; // not valid UTF-8 - leave the original text untouched
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        matcher.appendTail(out);
        return changed ? out.toString() : text;
    }

    /**
     * Repairs every string inside tool-call arguments (nested maps and lists included).
     *
     * @param arguments arguments
     * @return repaired copy (same instance when nothing changed)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> repairArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return arguments;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object repaired = repairValue(entry.getValue());
            changed |= repaired != entry.getValue();
            copy.put(entry.getKey(), repaired);
        }
        return changed ? copy : arguments;
    }

    @SuppressWarnings("unchecked")
    private static Object repairValue(Object value) {
        if (value instanceof String text) {
            String repaired = repair(text);
            return repaired.equals(text) ? text : repaired;
        }
        if (value instanceof Map<?, ?> map) {
            return repairArguments((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object item : list) {
                Object repaired = repairValue(item);
                changed |= repaired != item;
                copy.add(repaired);
            }
            return changed ? copy : list;
        }
        return value;
    }
}
