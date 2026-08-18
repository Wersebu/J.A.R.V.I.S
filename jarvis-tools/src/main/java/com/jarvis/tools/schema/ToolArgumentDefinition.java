package com.jarvis.tools.schema;

/**
 * Model-facing argument definition.
 *
 * <p>{@code schema} carries the argument's real JSON Schema shape, including nested {@code
 * items}/{@code properties} for array/object arguments. Call sites that only need a flat
 * primitive type can keep using the 4-arg constructor - {@code schema} is then derived from
 * {@code type}/{@code description} via {@link ToolJsonSchema#fromLegacyType}. Call sites with an
 * array or object argument should use the 3-arg constructor and build the shape explicitly with
 * {@link ToolJsonSchema#arrayOf} or {@link ToolJsonSchema#object}.</p>
 *
 * @param name argument name
 * @param type argument type
 * @param required whether the argument is required
 * @param description short argument description
 * @param schema the argument's real JSON Schema shape
 */
public record ToolArgumentDefinition(String name, String type, boolean required, String description, ToolJsonSchema schema) {

    /**
     * Derives {@code schema} from the legacy type string when not explicitly supplied.
     */
    public ToolArgumentDefinition {
        schema = schema != null ? schema : ToolJsonSchema.fromLegacyType(type, description);
    }

    /**
     * Declares a flat primitive-typed argument (string/number/integer/boolean).
     *
     * @param name argument name
     * @param type primitive type label, e.g. {@code "string"}, {@code "integer"}, {@code "boolean"}
     * @param required whether the argument is required
     * @param description short argument description
     */
    public ToolArgumentDefinition(String name, String type, boolean required, String description) {
        this(name, type, required, description, null);
    }

    /**
     * Declares an argument from an explicit JSON Schema node - the only way to correctly describe
     * an array or object argument with nested {@code items}/{@code properties}.
     *
     * @param name argument name
     * @param required whether the argument is required
     * @param schema the argument's real JSON Schema shape
     */
    public ToolArgumentDefinition(String name, boolean required, ToolJsonSchema schema) {
        this(name, schema.type(), required, schema.description(), schema);
    }
}
