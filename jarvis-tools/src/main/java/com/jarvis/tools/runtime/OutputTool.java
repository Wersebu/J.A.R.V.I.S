package com.jarvis.tools.runtime;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads full tool outputs that were shortened for the context window (see {@link ToolOutputStore}).
 */
@Service
public class OutputTool implements JarvisTool, ToolSchemaProvider {

    private static final int DEFAULT_LINES = 300;

    private final ToolOutputStore store;

    /**
     * Creates the tool.
     *
     * @param store output store
     */
    public OutputTool(ToolOutputStore store) {
        this.store = store;
    }

    @Override
    public String getName() {
        return "output";
    }

    @Override
    public String getDescription() {
        return "Read the full text of an earlier tool result that was shortened to save context (it carries an outputId).";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(getName(), getDescription(), List.of(
                new ToolOperationDefinition("READ", "Read lines of a stored output (numbered), e.g. the middle of a long build log.",
                        List.of(new ToolArgumentDefinition("outputId", "string", true, "outputId from a shortened result"),
                                new ToolArgumentDefinition("startLine", "integer", false, "First line (1-based)"),
                                new ToolArgumentDefinition("limit", "integer", false, "Max lines (default 300)")),
                        false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("GREP", "Find lines matching a regex in a stored output.",
                        List.of(new ToolArgumentDefinition("outputId", "string", true, "outputId from a shortened result"),
                                new ToolArgumentDefinition("pattern", "string", true, "Regex or text, case-insensitive")),
                        false, ToolSafetyLevel.READ)
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = request.operation() == null ? "" : request.operation().toUpperCase(Locale.ROOT);
        String id = String.valueOf(request.arguments().getOrDefault("outputId", ""));
        String text = store.get(id).orElse(null);
        if (text == null) {
            return failure(request, operation, "Unknown or expired outputId '" + id + "'. Re-run the original tool call.");
        }
        String[] lines = text.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outputId", id);
        data.put("totalLines", lines.length);
        if ("GREP".equals(operation)) {
            String raw = String.valueOf(request.arguments().getOrDefault("pattern", ""));
            Pattern pattern;
            try {
                pattern = Pattern.compile(raw, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException exception) {
                pattern = Pattern.compile(Pattern.quote(raw), Pattern.CASE_INSENSITIVE);
            }
            int hits = 0;
            for (int i = 0; i < lines.length && hits < 200; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    out.append(String.format(Locale.ROOT, "%6d\t%s%n", i + 1, abbreviate(lines[i])));
                    hits++;
                }
            }
            data.put("matches", hits);
        } else {
            int start = Math.max(1, integer(request.arguments().get("startLine"), 1));
            int limit = Math.max(1, Math.min(integer(request.arguments().get("limit"), DEFAULT_LINES), 2_000));
            int end = Math.min(lines.length, start + limit - 1);
            for (int i = start; i <= end; i++) {
                out.append(String.format(Locale.ROOT, "%6d\t%s%n", i, abbreviate(lines[i - 1])));
            }
            data.put("startLine", start);
            data.put("endLine", end);
        }
        data.put("content", out.toString());
        return new ToolResult(true, getName(), operation, request.requestId(), request.conversationId(), false, List.of(),
                "Stored output " + id, data, "", "", false, "");
    }

    private static String abbreviate(String line) {
        return line.length() > 1_000 ? line.substring(0, 1_000) + "..." : line;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private ToolResult failure(ToolRequest request, String operation, String message) {
        return new ToolResult(false, getName(), operation, request.requestId(), request.conversationId(), false, List.of(),
                message, Map.of("error", message), "OUTPUT_NOT_FOUND", message, false, "");
    }
}
