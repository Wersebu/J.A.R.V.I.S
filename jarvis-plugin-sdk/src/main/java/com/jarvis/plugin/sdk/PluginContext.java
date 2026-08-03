package com.jarvis.plugin.sdk;

/**
 * Context provided to future plugins during initialization.
 */
public interface PluginContext {

    /**
     * Returns the Jarvis runtime version.
     *
     * @return runtime version
     */
    String runtimeVersion();
}
