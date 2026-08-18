package com.jarvis.tools.schema;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal, self-similar JSON Schema tree for describing a native tool argument's real shape -
 * including nested arrays and objects - instead of collapsing everything to a flat type name.
 *
 * <p>Built explicitly by each tool's own {@code definition()}, never inferred by parsing an
 * argument's free-text description - the description is for the model to read, not a schema
 * source.</p>
 *
 * @param type JSON Schema primitive type: {@code string}, {@code number}, {@code integer},
 *         {@code boolean}, {@code array}, or {@code object}
 * @param description short description shown to the model for this node
 * @param items element schema, present only when {@code type} is {@code array}
 * @param properties named property schemas, present only when {@code type} is {@code object}
 * @param required required property names, present only when {@code type} is {@code object}
 */
public record ToolJsonSchema(
        String type,
        String description,
        ToolJsonSchema items,
        Map<String, ToolJsonSchema> properties,
        List<String> required
) {

    /**
     * Normalizes collection fields.
     */
    public ToolJsonSchema {
        type = type == null || type.isBlank() ? "string" : type;
        description = description == null ? "" : description;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        required = required == null ? List.of() : List.copyOf(required);
    }

    /**
     * Creates a flat primitive schema node (no items/properties).
     *
     * @param type JSON Schema primitive type
     * @param description short description
     * @return primitive schema
     */
    public static ToolJsonSchema of(String type, String description) {
        return new ToolJsonSchema(type, description, null, Map.of(), List.of());
    }

    /**
     * Creates a {@code string} schema node.
     *
     * @param description short description
     * @return string schema
     */
    public static ToolJsonSchema string(String description) {
        return of("string", description);
    }

    /**
     * Creates an {@code integer} schema node.
     *
     * @param description short description
     * @return integer schema
     */
    public static ToolJsonSchema integer(String description) {
        return of("integer", description);
    }

    /**
     * Creates a {@code number} schema node.
     *
     * @param description short description
     * @return number schema
     */
    public static ToolJsonSchema number(String description) {
        return of("number", description);
    }

    /**
     * Creates a {@code boolean} schema node.
     *
     * @param description short description
     * @return boolean schema
     */
    public static ToolJsonSchema bool(String description) {
        return of("boolean", description);
    }

    /**
     * Creates an {@code array} schema node with the given element schema.
     *
     * @param items element schema (e.g. {@link #string} for an array of strings, or {@link
     *         #object} for an array of objects)
     * @param description short description
     * @return array schema
     */
    public static ToolJsonSchema arrayOf(ToolJsonSchema items, String description) {
        return new ToolJsonSchema("array", description, items, Map.of(), List.of());
    }

    /**
     * Creates an {@code object} schema node with named properties.
     *
     * @param properties named property schemas, in the order they should be presented to the model
     * @param required required property names (subset of {@code properties}' keys)
     * @param description short description
     * @return object schema
     */
    public static ToolJsonSchema object(Map<String, ToolJsonSchema> properties, List<String> required, String description) {
        return new ToolJsonSchema("object", description, null, properties, required);
    }

    /**
     * Normalizes a legacy free-text type label (e.g. {@code "int"}, {@code "String"}, {@code
     * "array"}) into a flat JSON Schema primitive node. This is the fallback used when a tool
     * argument was declared with only a type string and no explicit {@link ToolJsonSchema} - it
     * reproduces the same string/number/boolean normalization the mapper always applied, but
     * (unlike the historical behavior) leaves {@code array} and {@code object} as their own
     * types rather than collapsing them to {@code string}. Callers that need real {@code items}
     * or {@code properties} for an array/object argument must build the schema explicitly via
     * {@link #arrayOf} or {@link #object} instead of relying on this fallback.
     *
     * @param type free-text legacy type label, may be {@code null}
     * @param description short description
     * @return normalized primitive schema node
     */
    public static ToolJsonSchema fromLegacyType(String type, String description) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (normalized.contains("array") || normalized.contains("list")) {
            return arrayOf(string(""), description);
        }
        if (normalized.contains("object") || normalized.contains("map")) {
            return object(Map.of(), List.of(), description);
        }
        if (normalized.contains("number") || normalized.contains("integer") || normalized.contains("int")) {
            return number(description);
        }
        if (normalized.contains("boolean")) {
            return bool(description);
        }
        return string(description);
    }
}
