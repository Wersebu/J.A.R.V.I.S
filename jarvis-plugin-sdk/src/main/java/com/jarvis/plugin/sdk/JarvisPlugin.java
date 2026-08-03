package com.jarvis.plugin.sdk;

import com.jarvis.tools.Tool;

import java.util.List;

/**
 * Contract that future external JAR plugins will implement.
 */
public interface JarvisPlugin {

    /**
     * Returns plugin metadata.
     *
     * @return plugin metadata
     */
    PluginDescriptor descriptor();

    /**
     * Returns tools contributed by this plugin.
     *
     * @return plugin tools
     */
    List<Tool> tools();
}
