package com.jarvis.memory.pipeline;

import java.util.Optional;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incrementally parses the main model structured response envelope while tokens arrive.
 */
public class StreamingStructuredResponseParser {

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "\\\"type\\\"\\s*:\\s*\\\"(FINAL_ANSWER|TOOL_REQUEST|CLARIFICATION)\\\""
    );

    private final StringBuilder raw;
    private final StringBuilder streamedValue;
    private MainModelActionType detectedType;
    private List<String> streamFields;
    private int streamValueStart;
    private int streamedUntil;
    private boolean streamClosed;

    /**
     * Creates the parser.
     */
    public StreamingStructuredResponseParser() {
        this.raw = new StringBuilder();
        this.streamedValue = new StringBuilder();
        this.streamValueStart = -1;
        this.streamedUntil = -1;
    }

    /**
     * Appends one model fragment and returns newly streamable answer text, if any.
     *
     * @param token model token
     * @return parser update
     */
    public ParserUpdate accept(String token) {
        if (token == null || token.isEmpty()) {
            return new ParserUpdate(Optional.empty(), "");
        }
        raw.append(token);
        Optional<MainModelActionType> newlyDetected = detectType();
        String streamed = streamAvailableValue();
        return new ParserUpdate(newlyDetected, streamed);
    }

    /**
     * Returns the detected type, when available.
     *
     * @return detected type
     */
    public Optional<MainModelActionType> detectedType() {
        return Optional.ofNullable(detectedType);
    }

    /**
     * Returns raw JSON seen so far.
     *
     * @return raw model response
     */
    public String raw() {
        return raw.toString();
    }

    /**
     * Returns text streamed from the answer/question field.
     *
     * @return streamed value
     */
    public String streamedValue() {
        return streamedValue.toString();
    }

    private Optional<MainModelActionType> detectType() {
        if (detectedType != null) {
            return Optional.empty();
        }
        Matcher matcher = TYPE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return Optional.empty();
        }
        detectedType = MainModelActionType.valueOf(matcher.group(1));
        if (detectedType == MainModelActionType.FINAL_ANSWER) {
            streamFields = List.of("answer", "response", "content", "message");
        } else if (detectedType == MainModelActionType.CLARIFICATION) {
            streamFields = List.of("question", "answer", "message", "content");
        }
        return Optional.of(detectedType);
    }

    private String streamAvailableValue() {
        if (streamFields == null || streamClosed) {
            return "";
        }
        if (streamValueStart < 0) {
            streamValueStart = findFirstStringValueStart(streamFields);
            if (streamValueStart < 0) {
                return "";
            }
            streamedUntil = streamValueStart;
        }
        int end = findUnescapedClosingQuote(streamedUntil);
        int availableEnd = end < 0 ? raw.length() : end;
        if (availableEnd <= streamedUntil) {
            if (end >= 0) {
                streamClosed = true;
            }
            return "";
        }
        String chunk = raw.substring(streamedUntil, availableEnd);
        streamedUntil = availableEnd;
        if (end >= 0) {
            streamClosed = true;
        }
        String decoded = decodeJsonStringFragment(chunk);
        streamedValue.append(decoded);
        return decoded;
    }

    private int findFirstStringValueStart(List<String> fields) {
        int firstStart = -1;
        for (String field : fields) {
            int start = findStringValueStart(field);
            if (start >= 0 && (firstStart < 0 || start < firstStart)) {
                firstStart = start;
            }
        }
        return firstStart;
    }

    private int findStringValueStart(String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"");
        Matcher matcher = pattern.matcher(raw);
        if (!matcher.find()) {
            return -1;
        }
        return matcher.end();
    }

    private int findUnescapedClosingQuote(int start) {
        boolean escaped = false;
        for (int index = start; index < raw.length(); index++) {
            char value = raw.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (value == '\\') {
                escaped = true;
                continue;
            }
            if (value == '"') {
                return index;
            }
        }
        return -1;
    }

    private String decodeJsonStringFragment(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * Incremental parser update.
     *
     * @param detectedType newly detected response type
     * @param streamedText newly available answer or question text
     */
    public record ParserUpdate(Optional<MainModelActionType> detectedType, String streamedText) {
    }
}
