package com.jarvis.plugin.sdk;

/**
 * Metadata describing a Jarvis plugin.
 *
 * @param id stable plugin identifier
 * @param name display name
 * @param version plugin version
 * @param description plugin description
 */
public record PluginDescriptor(String id, String name, String version, String description) {
}
