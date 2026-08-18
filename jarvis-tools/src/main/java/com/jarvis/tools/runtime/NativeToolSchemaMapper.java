package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
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
     * Returns native tool definitions for every registered tool.
     *
     * <p>The model always sees the full tool catalog. {@code intent} is kept in the
     * signature only as an advisory hint for callers/telemetry; it must never narrow
     * which tools the model is allowed to call — that decision belongs to the model.
     *
     * @param intent advisory capability hint (not used to filter)
     * @return native tool definitions
     */
    public List<NativeToolDefinition> definitions(ToolIntent intent) {
        List<NativeToolDefinition> values = new ArrayList<>();
        for (ToolDefinition definition : toolRegistry.definitions()) {
            for (ToolOperationDefinition operation : definition.operations()) {
                values.add(toNative(definition, operation));
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
        String normalized = functionName == null ? "" : functionName.trim().toLowerCase(Locale.ROOT);
        int separator = normalized.indexOf("__");
        if (separator < 1 || separator >= normalized.length() - 2) {
            throw new IllegalArgumentException("Invalid native tool name: " + functionName);
        }
        String toolName = normalized.substring(0, separator);
        String operationName = normalized.substring(separator + 2).toUpperCase(Locale.ROOT);
        validateArguments(toolName, operationName, arguments);
        return new ToolAction(
                "TOOL_CALL",
                toolName,
                operationName,
                arguments,
                reason,
                ""
        );
    }

    /**
     * Rejects an argument whose runtime shape does not match its declared schema (e.g. a plain
     * string sent where an array or object was declared) as close to the native-tool-call
     * boundary as possible, instead of letting it silently coerce to an empty list several
     * classes downstream. Only {@code array}/{@code object} mismatches are enforced — {@code
     * string}/{@code number}/{@code boolean} arguments keep tolerating the loose, stringify-able
     * values individual tools already accept, since that leniency isn't the defect being closed
     * here and enforcing it could reject values tools have always handled.
     *
     * @param toolName lowercased tool name
     * @param operationName uppercased operation name
     * @param arguments raw arguments as sent by the model
     */
    private void validateArguments(String toolName, String operationName, Map<String, Object> arguments) {
        Optional<ToolOperationDefinition> operation = findOperation(toolName, operationName);
        if (operation.isEmpty() || arguments == null) {
            return;
        }
        for (ToolArgumentDefinition argument : operation.get().arguments()) {
            Object value = arguments.get(argument.name());
            if (value == null) {
                continue;
            }
            ToolJsonSchema schema = argument.schema();
            boolean isArrayMismatch = "array".equals(schema.type()) && !(value instanceof List<?>);
            boolean isObjectMismatch = "object".equals(schema.type()) && !(value instanceof Map<?, ?>);
            if (isArrayMismatch || isObjectMismatch) {
                throw new InvalidToolArgumentException(describeMismatch(argument.name(), schema, value));
            }
        }
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

    private String describeMismatch(String argumentName, ToolJsonSchema schema, Object actual) {
        String expectedLabel;
        if ("array".equals(schema.type()) && schema.items() != null && "object".equals(schema.items().type())) {
            expectedLabel = "an array of objects";
        } else if ("array".equals(schema.type())) {
            expectedLabel = "an array";
        } else {
            expectedLabel = "an " + schema.type();
        }
        return "Argument '" + argumentName + "' must be " + expectedLabel + ", but received " + actualTypeLabel(actual) + ".";
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
