package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.workflow.ToolOperationClassifier;
import com.jarvis.tools.workflow.ToolOperationRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
     * Returns native tool definitions for the request's inferred scope.
     *
     * @param intent advisory capability hint (not used to filter)
     * @return native tool definitions
     */
    public List<NativeToolDefinition> definitions(ToolIntent intent) {
        return definitions(intent, "", "");
    }

    /**
     * Returns native tool definitions scoped to the request. Read-only requests receive read,
     * discovery, and inspection operations, while mutating operations remain available for
     * workflows that explicitly ask to write, create, execute, delete, submit, or save state.
     *
     * @param intent advisory capability hint
     * @param userMessage original user message
     * @param goal tool goal from the main model
     * @return native tool definitions
     */
    public List<NativeToolDefinition> definitions(ToolIntent intent, String userMessage, String goal) {
        return resolveScope(intent, userMessage, goal, Map.of()).definitions();
    }

    /**
     * Resolves and returns detailed tool scoping diagnostics for one native loop request.
     *
     * @param intent raw advisory intent
     * @param userMessage original user message
     * @param goal tool goal from the main model
     * @param context structured tool request context
     * @return selected definitions and routing diagnostics
     */
    public ToolScopeResolution resolveScope(ToolIntent intent, String userMessage, String goal, Map<String, Object> context) {
        List<NativeToolDefinition> values = new ArrayList<>();
        Map<String, String> rejected = new LinkedHashMap<>();
        String text = requestText(userMessage, goal, context);
        ProviderAffinity affinity = providerAffinity(text, context);
        boolean publicWeb = requestsPublicWeb(text);
        boolean connectedRuntime = requestsConnectedRuntime(text);
        ToolIntent resolvedIntent = affinity.provider().isBlank()
                ? intent
                : ToolIntent.CONNECTED_SYSTEM_INSPECTION;
        boolean providerScoped = !affinity.provider().isBlank() && (connectedRuntime || !publicWeb || affinity.fromContext());
        boolean webAllowed = publicWeb && !providerScoped;
        boolean readOnly = isReadOnlyRequest(intent, userMessage, goal) || providerScoped;
        List<ToolOperationRole> selectedRoles = providerScoped
                ? List.of(ToolOperationRole.DISCOVERY, ToolOperationRole.SELECTION, ToolOperationRole.READ,
                ToolOperationRole.SEARCH, ToolOperationRole.INSPECT)
                : readOnly ? List.of(ToolOperationRole.DISCOVERY, ToolOperationRole.SELECTION, ToolOperationRole.READ,
                ToolOperationRole.SEARCH, ToolOperationRole.INSPECT, ToolOperationRole.VERIFY, ToolOperationRole.UNKNOWN)
                : List.of();
        for (ToolDefinition definition : toolRegistry.definitions()) {
            for (ToolOperationDefinition operation : definition.operations()) {
                NativeToolDefinition nativeDefinition = toNative(definition, operation);
                String reason = rejectionReason(definition, operation, providerScoped, affinity.provider(), webAllowed, readOnly, selectedRoles);
                if (reason.isBlank()) {
                    values.add(nativeDefinition);
                } else {
                    rejected.put(nativeDefinition.name(), reason);
                }
            }
        }
        if (values.isEmpty() && providerScoped) {
            for (ToolDefinition definition : toolRegistry.definitions()) {
                for (ToolOperationDefinition operation : definition.operations()) {
                    if (isMcpTool(definition) && isReadAllowed(operation)) {
                        NativeToolDefinition nativeDefinition = toNative(definition, operation);
                        values.add(nativeDefinition);
                        rejected.remove(nativeDefinition.name());
                    }
                }
            }
            if (!values.isEmpty()) {
                affinity = new ProviderAffinity("", "fallback:mcp-read-only", false);
                resolvedIntent = ToolIntent.CONNECTED_SYSTEM_INSPECTION;
            }
        }
        return new ToolScopeResolution(intent, resolvedIntent, affinity.provider(), affinity.source(),
                selectedRoles, values.stream().map(NativeToolDefinition::name).toList(), rejected, values);
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
        String normalized = normalizeFunctionName(functionName);
        int separator = normalized.indexOf("__");
        String toolName = normalized.substring(0, separator);
        String operationName = normalized.substring(separator + 2).toUpperCase(Locale.ROOT);
        Map<String, Object> normalizedArguments = normalizeArguments(toolName, operationName, arguments);
        validateArguments(toolName, operationName, normalizedArguments);
        return new ToolAction(
                "TOOL_CALL",
                toolName,
                operationName,
                normalizedArguments,
                reason,
                ""
        );
    }

    /**
     * Returns required argument names for diagnostics and test assertions.
     *
     * @param functionName model-facing function name
     * @return required field names
     */
    public List<String> requiredFields(String functionName) {
        return findOperation(functionName)
                .map(operation -> operation.arguments().stream()
                        .filter(ToolArgumentDefinition::required)
                        .map(ToolArgumentDefinition::name)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Returns declared top-level argument names for diagnostics.
     *
     * @param functionName model-facing function name
     * @return expected field names
     */
    public List<String> expectedFields(String functionName) {
        return findOperation(functionName)
                .map(operation -> operation.arguments().stream()
                        .map(ToolArgumentDefinition::name)
                        .toList())
                .orElse(List.of());
    }

    /**
     * Describes whether a definition came from a native/static tool or an MCP dynamic tool.
     *
     * @param functionName model-facing function name
     * @return schema source label
     */
    public String schemaSource(String functionName) {
        String normalized = normalizeFunctionName(functionName);
        String toolName = normalized.substring(0, normalized.indexOf("__"));
        return toolName.startsWith("mcp_") ? "mcp-tool-schema" : "jarvis-tool-schema";
    }

    /**
     * Rejects missing required fields, accidental MCP-style argument wrappers, placeholders, and
     * primitive/container type mismatches at the native-tool boundary.
     *
     * @param toolName lowercased tool name
     * @param operationName uppercased operation name
     * @param arguments raw arguments as sent by the model
     */
    private void validateArguments(String toolName, String operationName, Map<String, Object> arguments) {
        Optional<ToolOperationDefinition> operation = findOperation(toolName, operationName);
        if (operation.isEmpty()) {
            return;
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        List<ToolArgumentDefinition> definitions = operation.get().arguments();
        if (safeArguments.containsKey("arguments") && definitions.stream().noneMatch(argument -> "arguments".equals(argument.name()))) {
            throw new InvalidToolArgumentException("Unexpected wrapper 'arguments'. Expected top-level fields: "
                    + expectedFieldLabel(definitions) + ".");
        }
        for (ToolArgumentDefinition argument : definitions) {
            Object value = safeArguments.get(argument.name());
            if (value == null) {
                if (argument.required() && toolName.startsWith("mcp_")) {
                    throw new InvalidToolArgumentException("Missing required argument '" + argument.name()
                            + "'. Expected top-level fields: " + expectedFieldLabel(definitions) + ".");
                }
                continue;
            }
            if (isPlaceholder(value)) {
                throw new InvalidToolArgumentException("Argument '" + argument.name()
                        + "' looks like a placeholder. Use a concrete value from prior tool results.");
            }
            ToolJsonSchema schema = argument.schema();
            if (!matches(schema, value)) {
                throw new InvalidToolArgumentException(describeMismatch(argument.name(), schema, value));
            }
        }
    }

    private String normalizeFunctionName(String functionName) {
        String normalized = functionName == null ? "" : functionName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("__")) {
            int separator = normalized.indexOf("__");
            if (separator < 1 || separator >= normalized.length() - 2) {
                throw new IllegalArgumentException("Invalid native tool name: " + functionName);
            }
            return normalized;
        }
        List<String> matches = definitions(ToolIntent.NO_TOOL).stream()
                .map(NativeToolDefinition::name)
                .filter(name -> name.equals(normalized + "__call") || name.startsWith(normalized + "__"))
                .distinct()
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Invalid native tool name: " + functionName);
        }
        throw new IllegalArgumentException("Ambiguous native tool name: " + functionName + ". Matches: " + matches);
    }

    private Optional<ToolOperationDefinition> findOperation(String functionName) {
        String normalized = normalizeFunctionName(functionName);
        int separator = normalized.indexOf("__");
        return findOperation(normalized.substring(0, separator), normalized.substring(separator + 2).toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> normalizeArguments(String toolName, String operationName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        Optional<ToolOperationDefinition> operation = findOperation(toolName, operationName);
        if (operation.isEmpty() || !operation.get().arguments().isEmpty()) {
            return safeArguments;
        }
        Object wrapped = safeArguments.get("arguments");
        if (safeArguments.size() == 1 && wrapped instanceof Map<?, ?> map && map.isEmpty()) {
            return Map.of();
        }
        return safeArguments;
    }

    private Optional<ToolOperationDefinition> findOperation(String toolName, String operationName) {
        for (ToolDefinition definition : toolRegistry.definitions()) {
            if (!definition.name().equalsIgnoreCase(toolName)) {
                continue;
            }
            for (ToolOperationDefinition operation : definition.operations()) {
                if (operation.name().equalsIgnoreCase(operationName)) {
                    return Optional.of(operation);
                }
            }
        }
        return Optional.empty();
    }

    private boolean matches(ToolJsonSchema schema, Object value) {
        return switch (schema.type()) {
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "integer" -> value instanceof Number number && Math.floor(number.doubleValue()) == number.doubleValue();
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            default -> value instanceof String;
        };
    }

    private String describeMismatch(String argumentName, ToolJsonSchema schema, Object actual) {
        String expectedLabel;
        if ("array".equals(schema.type()) && schema.items() != null && "object".equals(schema.items().type())) {
            expectedLabel = "an array of objects";
        } else if ("array".equals(schema.type())) {
            expectedLabel = "an array";
        } else if ("object".equals(schema.type())) {
            expectedLabel = "an object";
        } else if ("integer".equals(schema.type())) {
            expectedLabel = "an integer";
        } else if ("number".equals(schema.type())) {
            expectedLabel = "a number";
        } else if ("boolean".equals(schema.type())) {
            expectedLabel = "a boolean";
        } else {
            expectedLabel = "a string";
        }
        return "Argument '" + argumentName + "' must be " + expectedLabel + ", but received " + actualTypeLabel(actual) + ".";
    }

    private String expectedFieldLabel(List<ToolArgumentDefinition> definitions) {
        if (definitions.isEmpty()) {
            return "no arguments";
        }
        return definitions.stream()
                .map(argument -> argument.name() + (argument.required() ? " (required)" : ""))
                .toList()
                .toString();
    }

    private boolean isPlaceholder(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank()
                || normalized.contains("placeholder")
                || normalized.startsWith("example_")
                || normalized.startsWith("default_")
                || normalized.startsWith("<")
                || normalized.endsWith("_id_here");
    }

    private boolean isReadOnlyRequest(ToolIntent intent, String userMessage, String goal) {
        if (intent == ToolIntent.SAVE_KNOWLEDGE
                || intent == ToolIntent.CREATE_DOCUMENT
                || intent == ToolIntent.UPDATE_DOCUMENT
                || intent == ToolIntent.APPEND_DOCUMENT
                || intent == ToolIntent.ORGANIZE_KNOWLEDGE
                || intent == ToolIntent.DELETE_KNOWLEDGE) {
            return false;
        }
        String text = ((userMessage == null ? "" : userMessage) + " " + (goal == null ? "" : goal)).toLowerCase(Locale.ROOT);
        boolean mutating = containsAny(text,
                "create", "update", "delete", "write", "save", "store", "append", "submit", "finalize",
                "generate", "execute", "run", "play", "click", "type", "navigate", "set ", "change");
        boolean read = containsAny(text,
                "list", "show", "read", "inspect", "search", "find", "tree", "folder", "folders", "structure",
                "what is", "what are", "which", "status", "available");
        return read && !mutating;
    }

    private String rejectionReason(
            ToolDefinition definition,
            ToolOperationDefinition operation,
            boolean providerScoped,
            String provider,
            boolean webAllowed,
            boolean readOnly,
            List<ToolOperationRole> selectedRoles
    ) {
        ToolOperationRole role = ToolOperationClassifier.classify(definition.name(), operation.name());
        if (providerScoped) {
            if (!isMcpTool(definition)) {
                return "connected-provider scope excludes non-MCP tool";
            }
            String currentProvider = providerId(definition.name());
            if (!provider.isBlank() && !provider.equals(currentProvider)) {
                return "different MCP provider";
            }
            if (!selectedRoles.contains(role)) {
                return "operation role not selected for connected inspection: " + role;
            }
            if (!isReadAllowed(operation)) {
                return "operation is not read-only";
            }
            return "";
        }
        if (isWebTool(definition) && !webAllowed && readOnly) {
            return "web not requested";
        }
        if (readOnly && !isReadAllowed(operation)) {
            return "read-only request excludes mutating operation";
        }
        return "";
    }

    private ProviderAffinity providerAffinity(String text, Map<String, Object> context) {
        Set<String> providers = mcpProviders();
        String contextProvider = contextProvider(context);
        if (!contextProvider.isBlank() && providers.contains(contextProvider)) {
            return new ProviderAffinity(contextProvider, "context.provider", true);
        }
        for (String provider : providers) {
            if (mentionsProvider(text, provider)) {
                return new ProviderAffinity(provider, "message-or-goal-provider-name", false);
            }
        }
        return new ProviderAffinity("", "", false);
    }

    private Set<String> mcpProviders() {
        Set<String> providers = new LinkedHashSet<>();
        for (ToolDefinition definition : toolRegistry.definitions()) {
            if (isMcpTool(definition)) {
                providers.add(providerId(definition.name()));
            }
        }
        return providers;
    }

    private String contextProvider(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        for (String key : List.of("provider", "system", "server", "mcpServer", "runtime")) {
            Object value = context.get(key);
            if (value != null) {
                return normalizeToken(String.valueOf(value));
            }
        }
        return "";
    }

    private boolean mentionsProvider(String text, String provider) {
        String normalizedProvider = normalizeToken(provider);
        return text.matches(".*\\b" + java.util.regex.Pattern.quote(normalizedProvider) + "\\b.*");
    }

    private boolean requestsConnectedRuntime(String text) {
        return containsAny(text,
                "connected", "attached", "active", "currently connected", "current project",
                "podlacz", "polacz", "aktywn", "aktualnie polacz", "aktualnie podlacz",
                "biezac", "otwart", "runtime", "studio", "projekt");
    }

    private boolean requestsPublicWeb(String text) {
        return containsAny(text,
                "internet", "w internecie", "web", "online", "public", "publiczn", "documentation",
                "docs", "dokumentacj", "wyszukaj w sieci", "sprawdz w sieci", "google",
                "cena", "ceny", "koszt", "kosztuje", "po ile", "uzywan", "rynek", "market",
                "marketplace", "listing", "listings", "oferta", "oferty", "kupic", "buy", "price");
    }

    private String requestText(String userMessage, String goal, Map<String, Object> context) {
        return normalize((userMessage == null ? "" : userMessage) + " "
                + (goal == null ? "" : goal) + " "
                + (context == null ? "" : context.toString()));
    }

    private String normalize(String value) {
        String normalized = java.text.Normalizer.normalize(value == null ? "" : value.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    private String normalizeToken(String value) {
        return normalize(value).replaceAll("[^a-z0-9]+", "");
    }

    private boolean isMcpTool(ToolDefinition definition) {
        return definition.name().toLowerCase(Locale.ROOT).startsWith("mcp_");
    }

    private boolean isWebTool(ToolDefinition definition) {
        return "web".equalsIgnoreCase(definition.name());
    }

    private String providerId(String toolName) {
        String normalized = toolName.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("mcp_")) {
            return "";
        }
        String remainder = normalized.substring("mcp_".length());
        int separator = remainder.indexOf('_');
        return separator < 1 ? remainder : remainder.substring(0, separator);
    }

    private boolean isReadAllowed(ToolOperationDefinition operation) {
        return !operation.write() && operation.safetyLevel() == ToolSafetyLevel.READ;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private record ProviderAffinity(String provider, String source, boolean fromContext) {
    }

    private String actualTypeLabel(Object value) {
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value == null ? "null" : value.getClass().getSimpleName();
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
            properties.put(argument.name(), toJsonSchema(argument.schema()));
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

    /**
     * Converts a {@link ToolJsonSchema} node into the plain {@code Map}/{@code List} shape the
     * native tool-calling transport expects, recursing into {@code items}/{@code properties} so
     * array and object arguments carry their real nested structure instead of being flattened to
     * a bare {@code "type":"string"}.
     *
     * @param node schema node
     * @return JSON-Schema-shaped map
     */
    private Map<String, Object> toJsonSchema(ToolJsonSchema node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", node.type());
        result.put("description", node.description());
        if ("array".equals(node.type()) && node.items() != null) {
            result.put("items", toJsonSchema(node.items()));
        }
        if ("object".equals(node.type()) && !node.properties().isEmpty()) {
            Map<String, Object> nestedProperties = new LinkedHashMap<>();
            for (Map.Entry<String, ToolJsonSchema> entry : node.properties().entrySet()) {
                nestedProperties.put(entry.getKey(), toJsonSchema(entry.getValue()));
            }
            result.put("properties", nestedProperties);
            result.put("required", node.required());
        }
        return result;
    }
}
