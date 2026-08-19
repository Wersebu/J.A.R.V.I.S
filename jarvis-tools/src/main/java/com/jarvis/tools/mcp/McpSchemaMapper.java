package com.jarvis.tools.mcp;

import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts MCP JSON Schema maps into Jarvis tool schema definitions.
 */
public class McpSchemaMapper {

    /**
     * Converts an MCP input schema into top-level Jarvis tool arguments.
     *
     * @param inputSchema MCP input schema
     * @return Jarvis argument definitions
     */
    public List<ToolArgumentDefinition> arguments(Map<String, Object> inputSchema) {
        if (inputSchema != null
                && "object".equalsIgnoreCase(string(inputSchema.get("type"), "object"))
                && inputSchema.containsKey("properties")) {
            Map<String, Object> properties = asMap(inputSchema.get("properties"));
            List<String> required = asStringList(inputSchema.get("required"));
            List<ToolArgumentDefinition> arguments = new ArrayList<>();
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                ToolJsonSchema schema = schema(asMap(entry.getValue()), "");
                arguments.add(new ToolArgumentDefinition(entry.getKey(), required.contains(entry.getKey()), schema));
            }
            return List.copyOf(arguments);
        }
        return List.of(new ToolArgumentDefinition("arguments", false,
                ToolJsonSchema.object(Map.of(), List.of(), "Raw MCP tool arguments.")));
    }

    private ToolJsonSchema schema(Map<String, Object> source, String fallbackDescription) {
        String type = string(source.get("type"), "string").toLowerCase(Locale.ROOT);
        String description = string(source.get("description"), fallbackDescription);
        List<Object> enumValues = asObjectList(source.get("enum"));
        ToolJsonSchema schema;
        if ("array".equals(type)) {
            schema = ToolJsonSchema.arrayOf(schema(asMap(source.get("items")), ""), description);
        } else if ("object".equals(type)) {
            Map<String, Object> rawProperties = asMap(source.get("properties"));
            Map<String, ToolJsonSchema> properties = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : rawProperties.entrySet()) {
                properties.put(entry.getKey(), schema(asMap(entry.getValue()), ""));
            }
            schema = ToolJsonSchema.object(properties, asStringList(source.get("required")), description);
        } else if ("integer".equals(type)) {
            schema = ToolJsonSchema.integer(description);
        } else if ("number".equals(type)) {
            schema = ToolJsonSchema.number(description);
        } else if ("boolean".equals(type)) {
            schema = ToolJsonSchema.bool(description);
        } else {
            schema = ToolJsonSchema.string(description);
        }
        return enumValues.isEmpty() ? schema : schema.withEnumValues(enumValues);
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(item -> item != null)
                .map(String::valueOf)
                .toList();
    }

    private List<Object> asObjectList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(item -> item != null)
                .map(item -> (Object) item)
                .toList();
    }

    private String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
