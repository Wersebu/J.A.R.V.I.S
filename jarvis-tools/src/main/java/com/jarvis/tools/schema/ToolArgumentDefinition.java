package com.jarvis.tools.schema;

/**
 * Model-facing argument definition.
 *
 * @param name argument name
 * @param type argument type
 * @param required whether the argument is required
 * @param description short argument description
 */
public record ToolArgumentDefinition(String name, String type, boolean required, String description) {
}
