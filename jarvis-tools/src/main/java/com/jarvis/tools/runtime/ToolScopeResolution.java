package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.workflow.ToolOperationRole;

import java.util.List;
import java.util.Map;

/**
 * Result of resolving the model-facing native tool catalog for one request.
 *
 * @param rawIntent advisory intent before provider affinity
 * @param resolvedIntent intent after connected-system affinity and web/public-info checks
 * @param detectedProvider MCP provider/server id selected from available runtime tools
 * @param providerAffinitySource why the provider was selected
 * @param selectedRoles operation roles allowed in the selected scope
 * @param selectedTools model-facing native tools included in the catalog
 * @param rejectedTools model-facing native tools excluded with short reasons
 * @param definitions selected native tool definitions
 */
public record ToolScopeResolution(
        ToolIntent rawIntent,
        ToolIntent resolvedIntent,
        String detectedProvider,
        String providerAffinitySource,
        List<ToolOperationRole> selectedRoles,
        List<String> selectedTools,
        Map<String, String> rejectedTools,
        List<NativeToolDefinition> definitions
) {

    public ToolScopeResolution {
        detectedProvider = detectedProvider == null ? "" : detectedProvider;
        providerAffinitySource = providerAffinitySource == null ? "" : providerAffinitySource;
        selectedRoles = selectedRoles == null ? List.of() : List.copyOf(selectedRoles);
        selectedTools = selectedTools == null ? List.of() : List.copyOf(selectedTools);
        rejectedTools = rejectedTools == null ? Map.of() : Map.copyOf(rejectedTools);
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
    }
}
