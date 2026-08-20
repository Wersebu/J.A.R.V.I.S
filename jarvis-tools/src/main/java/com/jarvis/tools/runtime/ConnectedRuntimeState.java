package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Core-owned connected runtime identity for one native loop.
 */
final class ConnectedRuntimeState {

    private String provider = "";
    private String runtimeId = "";
    private String projectIdentity = "";
    private String currentMode = "";
    private int sessionGeneration;

    String runtimeId() {
        return runtimeId;
    }

    String currentMode() {
        return currentMode;
    }

    int sessionGeneration() {
        return sessionGeneration;
    }

    boolean hasRuntimeId() {
        return !runtimeId.isBlank();
    }

    void invalidate(String oldRuntimeId) {
        if (!oldRuntimeId.isBlank() && oldRuntimeId.equals(runtimeId)) {
            runtimeId = "";
            currentMode = "";
        }
    }

    ToolAction bind(ToolAction action) {
        if (!isRoblox(action) || runtimeId.isBlank()) {
            return action;
        }
        if (!declaresStudioId(action) || action.arguments().containsKey("studio_id")) {
            return action;
        }
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(action.arguments());
        arguments.put("studio_id", runtimeId);
        return new ToolAction(action.action(), action.tool(), action.operation(), arguments, action.reason(), action.answer());
    }

    ToolAction rebind(ToolAction action, String newRuntimeId) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>(action.arguments());
        arguments.put("studio_id", newRuntimeId);
        return new ToolAction(action.action(), action.tool(), action.operation(), arguments, action.reason(), action.answer());
    }

    void observe(ToolAction action, ToolResult result) {
        if (result == null || !result.success() || !isRoblox(action)) {
            return;
        }
        if (isListStudios(action)) {
            extractSingleRuntimeId(result).ifPresent(id -> {
                String previous = runtimeId;
                provider = "roblox";
                runtimeId = id;
                projectIdentity = extractProjectIdentity(result).orElse(projectIdentity);
                if (!Objects.equals(previous, runtimeId)) {
                    sessionGeneration++;
                }
            });
            return;
        }
        String id = firstString(result.data(), "studio_id", "studioId", "id")
                .or(() -> firstString(action.arguments(), "studio_id", "studioId"))
                .orElse("");
        if (!id.isBlank()) {
            provider = "roblox";
            if (!id.equals(runtimeId)) {
                runtimeId = id;
                sessionGeneration++;
            }
        }
        firstString(result.data(), "mode", "currentMode", "datamodel_type", "datamodelType")
                .ifPresent(mode -> currentMode = mode);
    }

    Optional<String> extractSingleRuntimeId(ToolResult result) {
        Object studios = result.data().get("studios");
        if (studios instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> studio) {
            return firstString(studio, "studio_id", "studioId", "id");
        }
        Object instances = result.data().get("instances");
        if (instances instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> instance) {
            return firstString(instance, "studio_id", "studioId", "id");
        }
        return firstString(result.data(), "studio_id", "studioId", "id");
    }

    private Optional<String> extractProjectIdentity(ToolResult result) {
        Object studios = result.data().get("studios");
        if (studios instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> studio) {
            return firstString(studio, "placeId", "place_id", "name", "projectName");
        }
        return firstString(result.data(), "placeId", "place_id", "name", "projectName");
    }

    private boolean declaresStudioId(ToolAction action) {
        String tool = action.tool().toLowerCase(Locale.ROOT);
        return tool.startsWith("mcp_roblox_") && !tool.contains("list_roblox_studios");
    }

    private boolean isListStudios(ToolAction action) {
        return action.tool().toLowerCase(Locale.ROOT).contains("list_roblox_studios");
    }

    private boolean isRoblox(ToolAction action) {
        return action != null && action.tool().toLowerCase(Locale.ROOT).startsWith("mcp_roblox_");
    }

    private Optional<String> firstString(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return Optional.of(String.valueOf(value));
            }
        }
        return Optional.empty();
    }
}
