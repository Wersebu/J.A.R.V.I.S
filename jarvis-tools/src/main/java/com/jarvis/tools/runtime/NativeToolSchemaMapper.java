package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Converts Jarvis tool registry definitions into provider-independent native function definitions.
 */
@Service
public class NativeToolSchemaMapper {

    private final ToolRegistry toolRegistry;

    /**
     * Creates the mapper.
     *
     * @param toolRegistry tool registry
     */
    public NativeToolSchemaMapper(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Returns native tool definitions for the request's inferred scope.
     *
     * @param intent advisory capability hint (not used to filter)
     * @return native tool definitions
     */
    public List<NativeToolDefinition> definitions(ToolIntent intent) {
        return definitions(intent, "", "");
    }

    /**
     * Returns native tool definitions scoped to the request. Read-only requests receive read,
     * discovery, and inspection operations, while mutating operations remain available for
     * workflows that explicitly ask to write, create, execute, delete, submit, or save state.
     *
     * @param intent advisory capability hint
     * @param userMessage original user message
     * @param goal tool goal from the main model
     * @return native tool definitions
     */
    public List<NativeToolDefinition> definitions(ToolIntent intent, String userMessage, String goal) {
        List<NativeToolDefinition> values = new ArrayList<>();
        boolean readOnly = isReadOnlyRequest(intent, userMessage, goal);
        for (ToolDefinition definition : toolRegistry.definitions()) {
            for (ToolOperationDefinition operation : definition.operations()) {
                if (!readOnly || isReadAllowed(operation)) {
                    values.add(toNative(definition, operation));
                }
            }
        }
        return values;
    }

    /**
     * Converts a native function name back to a Jarvis tool action.
     *
     * @param functionName model-facing function name
     * @param arguments function arguments
     * @param reason action reason
     * @return tool action
     */
    public ToolAction toAction(String functionName, Map<String, Object> arguments, String reason) {
        String normalized = normalizeFunctionName(functionName);
        int separator = normalized.indexOf("__");
        String toolName = normalized.substring(0, separator);
        String operationName = normalized.substring(separator + 2).toUpperCase(Locale.ROOT);
        Map<String, Object> normalizedArguments = normalizeArguments(toolName, operationName, arguments);
        validateArguments(toolName, operationName, normalizedArguments);
        return new ToolAction(
                "TOOL_CALL",
                toolName,
                operationName,
                normalizedArguments,
                reason,
                ""
        );
    }

    /**
     * Returns required argument names for diagnostics and test assertions.
     *
     * @param functionName model-facing function name
     * @return required field names
     */
    public List<String> requiredFields(String functionName) {
        return findOperation(functionName)
                .map(operation -> operation.arguments().stream()
                        .filter(ToolArgumentDefinition::required)
                        .map(ToolArgumentDefinition::name)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Returns declared top-level argument names for diagnostics.
     *
     * @param functionName model-facing function name
     * @return expected field names
     */
    public List<String> expectedFields(String functionName) {
        return findOperation(functionName)
                .map(operation -> operation.arguments().stream()
                        .map(ToolArgumentDefinition::name)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Describes whether a definition came from a native/static tool or an MCP dynamic tool.
     *
     * @param functionName model-facing function name
     * @return schema source label
     */
    public String schemaSource(String functionName) {
        String normalized = normalizeFunctionName(functionName);
        String toolName = normalized.substring(0, normalized.indexOf("__"));
        return toolName.startsWith("mcp_") ? "mcp-tool-schema" : "jarvis-tool-schema";
    }

    /**
     * Rejects missing required fields, accidental MCP-style argument wrappers, placeholders, and
     * primitive/container type mismatches at the native-tool boundary.
     *
     * @param toolName lowercased tool name
     * @param operationName uppercased operation name
     * @param arguments raw arguments as sent by the model
     */
    private void validateArguments(String toolName, String operationName, Map<String, Object> arguments) {
        Optional<ToolOperationDefinition> operation = findOperation(toolName, operationName);
        if (operation.isEmpty()) {
            return;
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        List<ToolArgumentDefinition> definitions = operation.get().arguments();
        if (safeArguments.containsKey("arguments") && definitions.stream().noneMatch(argument -> "arguments".equals(argument.name()))) {
            throw new InvalidToolArgumentException("Unexpected wrapper 'arguments'. Expected top-level fields: "
                    + expectedFieldLabel(definitions) + ".");
        }
        for (ToolArgumentDefinition argument : definitions) {
            Object value = safeArguments.get(argument.name());
            if (value == null) {
                if (argument.required() && toolName.startsWith("mcp_")) {
                    throw new InvalidToolArgumentException("Missing required argument '" + argument.name()
                            + "'. Expected top-level fields: " + expectedFieldLabel(definitions) + ".");
                }
                continue;
            }
            if (isPlaceholder(value)) {
                throw new InvalidToolArgumentException("Argument '" + argument.name()
                        + "' looks like a placeholder. Use a concrete value from prior tool results.");
            }
            ToolJsonSchema schema = argument.schema();
            if (!matches(schema, value)) {
                throw new InvalidToolArgumentException(describeMismatch(argument.name(), schema, value));
            }
        }
    }

    private String normalizeFunctionName(String functionName) {
        String normalized = functionName == null ? "" : functionName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("__")) {
            int separator = normalized.indexOf("__");
            if (separator < 1 || separator >= normalized.length() - 2) {
                throw new IllegalArgumentException("Invalid native tool name: " + functionName);
            }
            return normalized;
        }
        List<String> matches = definitions(ToolIntent.NO_TOOL).stream()
                .map(NativeToolDefinition::name)
                .filter(name -> name.equals(normalized + "__call") || name.startsWith(normalized + "__"))
                .distinct()
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Invalid native tool name: " + functionName);
        }
        throw new IllegalArgumentException("Ambiguous native tool name: " + functionName + ". Matches: " + matches);
    }

    private Optional<ToolOperationDefinition> findOperation(String functionName) {
        String normalized = normalizeFunctionName(functionName);
        int separator = normalized.indexOf("__");
        return findOperation(normalized.substring(0, separator), normalized.substring(separator + 2).toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> normalizeArguments(String toolName, String operationName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        Optional<ToolOperationDefinition> operation = findOperation(toolName, operationName);
        if (operation.isEmpty() || !operation.get().arguments().isEmpty()) {
            return safeArguments;
        }
        Object wrapped = safeArguments.get("arguments");
        if (safeArguments.size() == 1 && wrapped instanceof Map<?, ?> map && map.isEmpty()) {
            return Map.of();
        }
        return safeArguments;
    }

    private Optional<ToolOperationDefinition> findOperation(String toolName, String operationName) {
        for (ToolDefinition definition : toolRegistry.definitions()) {
            if (!definition.name().equalsIgnoreCase(toolName)) {
                continue;
            }
            for (ToolOperationDefinition operation : definition.operations()) {
                if (operation.name().equalsIgnoreCase(operationName)) {
                    return Optional.of(operation);
                }
            }
        }
        return Optional.empty();
    }

    private boolean matches(ToolJsonSchema schema, Object value) {
        return switch (schema.type()) {
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "integer" -> value instanceof Number number && Math.floor(number.doubleValue()) == number.doubleValue();
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> value instanceof String;
        };
    }

    private String describeMismatch(String argumentName, ToolJsonSchema schema, Object actual) {
        String expectedLabel;
        if ("array".equals(schema.type()) && schema.items() != null && "object".equals(schema.items().type())) {
            expectedLabel = "an array of objects";
        } else if ("array".equals(schema.type())) {
            expectedLabel = "an array";
        } else if ("object".equals(schema.type())) {
            expectedLabel = "an object";
        } else if ("integer".equals(schema.type())) {
            expectedLabel = "an integer";
        } else if ("number".equals(schema.type())) {
            expectedLabel = "a number";
        } else if ("boolean".equals(schema.type())) {
            expectedLabel = "a boolean";
        } else {
            expectedLabel = "a string";
        }
        return "Argument '" + argumentName + "' must be " + expectedLabel + ", but received " + actualTypeLabel(actual) + ".";
    }

    private String expectedFieldLabel(List<ToolArgumentDefinition> definitions) {
        if (definitions.isEmpty()) {
            return "no arguments";
        }
        return definitions.stream()
                .map(argument -> argument.name() + (argument.required() ? " (required)" : ""))
                .toList()
                .toString();
    }

    private boolean isPlaceholder(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.contains("placeholder")
                || normalized.startsWith("example_")
                || normalized.startsWith("default_")
                || normalized.startsWith("<")
                || normalized.endsWith("_id_here");
    }

    private boolean isReadOnlyRequest(ToolIntent intent, String userMessage, String goal) {
        if (intent == ToolIntent.SAVE_KNOWLEDGE
                || intent == ToolIntent.CREATE_DOCUMENT
                || intent == ToolIntent.UPDATE_DOCUMENT
                || intent == ToolIntent.APPEND_DOCUMENT
                || intent == ToolIntent.ORGANIZE_KNOWLEDGE
                || intent == ToolIntent.DELETE_KNOWLEDGE) {
            return false;
        }
        String text = ((userMessage == null ? "" : userMessage) + " " + (goal == null ? "" : goal)).toLowerCase(Locale.ROOT);
        boolean mutating = containsAny(text,
                "create", "update", "delete", "write", "save", "store", "append", "submit", "finalize",
                "generate", "execute", "run", "play", "click", "type", "navigate", "set ", "change");
        boolean read = containsAny(text,
                "list", "show", "read", "inspect", "search", "find", "tree", "folder", "folders", "structure",
                "what is", "what are", "which", "status", "available");
        return read && !mutating;
    }

    private boolean isReadAllowed(ToolOperationDefinition operation) {
        return !operation.write() && operation.safetyLevel() == ToolSafetyLevel.READ;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String actualTypeLabel(Object value) {
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private NativeToolDefinition toNative(ToolDefinition definition, ToolOperationDefinition operation) {
        String functionName = definition.name().toLowerCase(Locale.ROOT) + "__" + operation.name().toLowerCase(Locale.ROOT);
        return new NativeToolDefinition(
                functionName,
                definition.description() + " Operation: " + operation.description(),
                parameters(operation)
        );
    }

    private Map<String, Object> parameters(ToolOperationDefinition operation) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (ToolArgumentDefinition argument : operation.arguments()) {
            properties.put(argument.name(), toJsonSchema(argument.schema()));
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    /**
     * Converts a {@link ToolJsonSchema} node into the plain {@code Map}/{@code List} shape the
     * native tool-calling transport expects, recursing into {@code items}/{@code properties} so
     * array and object arguments carry their real nested structure instead of being flattened to
     * a bare {@code "type":"string"}.
     *
     * @param node schema node
     * @return JSON-Schema-shaped map
     */
    private Map<String, Object> toJsonSchema(ToolJsonSchema node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", node.type());
        result.put("description", node.description());
        if ("array".equals(node.type()) && node.items() != null) {
            result.put("items", toJsonSchema(node.items()));
        }
        if ("object".equals(node.type()) && !node.properties().isEmpty()) {
            Map<String, Object> nestedProperties = new LinkedHashMap<>();
            for (Map.Entry<String, ToolJsonSchema> entry : node.properties().entrySet()) {
                nestedProperties.put(entry.getKey(), toJsonSchema(entry.getValue()));
            }
            result.put("properties", nestedProperties);
            result.put("required", node.required());
        }
        return result;
    }
}
