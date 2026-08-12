package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        return new ToolAction(
                "TOOL_CALL",
                normalized.substring(0, separator),
                normalized.substring(separator + 2).toUpperCase(Locale.ROOT),
                arguments,
                reason,
                ""
        );
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
            properties.put(argument.name(), Map.of(
                    "type", jsonType(argument.type()),
                    "description", argument.description()
            ));
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

    private String jsonType(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (normalized.contains("number") || normalized.contains("integer") || normalized.contains("int")) {
            return "number";
        }
        if (normalized.contains("boolean")) {
            return "boolean";
        }
        return "string";
    }
}
