package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for native model-facing tool schemas and argument validation.
 */
class NativeToolSchemaMapperTest {

    @Test
    void legacyIntentOnlyCallStillReturnsTheFullCatalog() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(twoToolRegistry());

        List<NativeToolDefinition> definitions = mapper.definitions(ToolIntent.SEARCH_WEB);

        assertThat(definitions).extracting(NativeToolDefinition::name)
                .anySatisfy(name -> assertThat(name).startsWith("knowledge__"));
        assertThat(definitions).extracting(NativeToolDefinition::name)
                .anySatisfy(name -> assertThat(name).startsWith("web__"));
    }

    @Test
    void readOnlyRequestStillReturnsTheFullRuntimeCatalog() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mixedRegistry());

        List<NativeToolDefinition> definitions = mapper.definitions(ToolIntent.NO_TOOL,
                "List folders in the active project tree", "Read the folder structure");

        assertThat(definitions).extracting(NativeToolDefinition::name)
                .contains("mcp_roblox_search_game_tree__call")
                .contains("mcp_roblox_execute_luau__call");
    }

    @Test
    void mutatingRequestKeepsWriteOperationsAvailable() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mixedRegistry());

        List<NativeToolDefinition> definitions = mapper.definitions(ToolIntent.NO_TOOL,
                "Run this Lua script in the active project", "Execute script");

        assertThat(definitions).extracting(NativeToolDefinition::name)
                .contains("mcp_roblox_execute_luau__call");
    }

    @Test
    void missingCallSuffixIsNormalizedWhenOnlyOneOperationMatches() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mcpRegistry());

        ToolAction action = mapper.toAction("mcp_roblox_search_game_tree", Map.of("query", "Workspace"), "test");

        assertThat(action.tool()).isEqualTo("mcp_roblox_search_game_tree");
        assertThat(action.operation()).isEqualTo("CALL");
    }

    @Test
    void missingRequiredArgumentIsRejectedBeforeExecution() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mcpRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Missing required argument 'query'");
    }

    @Test
    void runtimeSchemaKeepsExactSnakeCaseRequiredEnumAndDescriptions() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(strictRobloxRegistry());

        Map<String, Object> schema = parametersFor(mapper, "mcp_roblox_search_game_tree__call");

        assertThat(schema.get("required")).isEqualTo(List.of("studio_id", "datamodel_type"));
        Map<String, Object> studioId = propertySchema(schema, "studio_id");
        Map<String, Object> datamodelType = propertySchema(schema, "datamodel_type");
        assertThat(studioId.get("description")).isEqualTo("Exact connected Studio id from list_roblox_studios.");
        assertThat(datamodelType.get("enum")).isEqualTo(List.of("Edit", "Client", "Server"));
        assertThat(datamodelType.get("description")).isEqualTo("Roblox datamodel type.");
    }

    @Test
    void unknownCamelCaseFieldIsRejectedBeforeMcpExecution() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(strictRobloxRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call",
                Map.of("studioId", "studio-1", "datamodel_type", "Edit"), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Unknown argument")
                .hasMessageContaining("studioId")
                .hasMessageContaining("studio_id")
                .hasMessageContaining("Do not rename snake_case fields to camelCase");
    }

    @Test
    void enumValueOutsideRuntimeSchemaIsRejectedBeforeMcpExecution() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(strictRobloxRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call",
                Map.of("studio_id", "studio-1", "datamodel_type", "Model"), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("datamodel_type")
                .hasMessageContaining("Edit")
                .hasMessageContaining("Client")
                .hasMessageContaining("Server")
                .hasMessageContaining("Model");
    }

    @Test
    void wrappedArgumentsAreRejectedWhenSchemaDeclaresTopLevelFields() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mcpRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call",
                Map.of("arguments", Map.of("query", "Workspace")), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Unexpected wrapper 'arguments'");
    }

    @Test
    void noArgToolTreatsEmptyWrapperAsEmptyArguments() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mcpRegistry());

        ToolAction plain = mapper.toAction("mcp_roblox_list_roblox_studios__call", Map.of(), "test");
        ToolAction wrapped = mapper.toAction("mcp_roblox_list_roblox_studios__call",
                Map.of("arguments", Map.of()), "test");

        assertThat(plain.arguments()).isEmpty();
        assertThat(wrapped.arguments()).isEmpty();
    }

    @Test
    void placeholderArgumentIsRejected() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mcpRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call",
                Map.of("query", "studio_id_placeholder"), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void robloxConnectedProjectFolderRequestKeepsFullCatalogAndMarksRobloxAffinity() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(runtimeRegistry());

        ToolScopeResolution scope = mapper.resolveScope(ToolIntent.SEARCH_WEB,
                "Podaj liste folderow dostepnych w aktualnie polaczonym projekcie Roblox Studio.",
                "Inspect the currently connected Roblox Studio project folders.", Map.of());

        assertThat(scope.resolvedIntent()).isEqualTo(ToolIntent.CONNECTED_SYSTEM_INSPECTION);
        assertThat(scope.detectedProvider()).isEqualTo("roblox");
        assertThat(scope.selectedRoles()).contains(
                com.jarvis.tools.workflow.ToolOperationRole.DISCOVERY,
                com.jarvis.tools.workflow.ToolOperationRole.SEARCH,
                com.jarvis.tools.workflow.ToolOperationRole.INSPECT);
        assertThat(scope.selectedTools())
                .contains("mcp_roblox_list_roblox_studios__call", "mcp_roblox_search_game_tree__call")
                .contains("web__search_web", "mcp_postgres_get_status__call", "mcp_roblox_execute_luau__call");
        assertThat(scope.rejectedTools()).isEmpty();
    }

    @Test
    void publicInternetDocumentationRequestAllowsWebToolsEvenWhenProviderNameIsMentioned() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(runtimeRegistry());

        ToolScopeResolution scope = mapper.resolveScope(ToolIntent.SEARCH_WEB,
                "Znajdz w internecie dokumentacje Roblox search_game_tree",
                "Find public documentation for Roblox search_game_tree.", Map.of());

        assertThat(scope.selectedTools()).contains("web__search_web");
    }

    @Test
    void currentlyConnectedSystemRequestPrefersMatchingMcpProviderOverWeb() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(runtimeRegistry());

        ToolScopeResolution scope = mapper.resolveScope(ToolIntent.SEARCH_WEB,
                "Sprawdz aktualnie podlaczony system postgres",
                "Inspect connected system postgres.", Map.of());

        assertThat(scope.resolvedIntent()).isEqualTo(ToolIntent.CONNECTED_SYSTEM_INSPECTION);
        assertThat(scope.detectedProvider()).isEqualTo("postgres");
        assertThat(scope.selectedTools())
                .contains("mcp_postgres_get_status__call", "web__search_web", "mcp_roblox_search_game_tree__call");
    }

    @Test
    void missingMcpProviderAndDocumentationRequestAllowsWeb() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(runtimeRegistry());

        ToolScopeResolution scope = mapper.resolveScope(ToolIntent.SEARCH_WEB,
                "Znajdz w internecie dokumentacje systemu snowflake",
                "Find public docs for snowflake.", Map.of());

        assertThat(scope.detectedProvider()).isBlank();
        assertThat(scope.selectedTools()).contains("web__search_web");
    }

    @Test
    void contextProviderAffinityWinsOverWrongSearchWebIntentHint() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(runtimeRegistry());

        ToolScopeResolution scope = mapper.resolveScope(ToolIntent.SEARCH_WEB,
                "Podaj liste folderow w aktualnie polaczonym projekcie",
                "List connected project folders.", Map.of("provider", "roblox"));

        assertThat(scope.resolvedIntent()).isEqualTo(ToolIntent.CONNECTED_SYSTEM_INSPECTION);
        assertThat(scope.detectedProvider()).isEqualTo("roblox");
        assertThat(scope.providerAffinitySource()).isEqualTo("context.provider");
        assertThat(scope.selectedTools())
                .contains("mcp_roblox_list_roblox_studios__call", "mcp_roblox_search_game_tree__call", "web__search_web");
        assertThat(scope.rejectedTools()).isEmpty();
    }

    // Regression coverage for the root-cause bug: NativeToolSchemaMapper.jsonType() used to
    // collapse every non-number/boolean argument type - including array and object - down to the
    // literal string "string", so the model's native tool-calling grammar never saw a real array
    // or object shape for arguments like storeDataset.records.

    @Test
    void arrayTypedArgumentProducesArrayNotString() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(datasetRegistry());

        Map<String, Object> schema = parametersFor(mapper, "storedataset__create_dataset");
        Map<String, Object> sourceAttachmentIds = propertySchema(schema, "sourceAttachmentIds");

        assertThat(sourceAttachmentIds.get("type")).isEqualTo("array");
    }

    @Test
    void arrayOfStringsDeclaresStringItems() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(datasetRegistry());

        Map<String, Object> schema = parametersFor(mapper, "storedataset__create_dataset");
        Map<String, Object> sourceAttachmentIds = propertySchema(schema, "sourceAttachmentIds");

        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) sourceAttachmentIds.get("items");
        assertThat(items).isNotNull();
        assertThat(items.get("type")).isEqualTo("string");
    }

    @Test
    void arrayOfObjectsDeclaresNestedObjectItemsWithProperties() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(datasetRegistry());

        Map<String, Object> schema = parametersFor(mapper, "storedataset__create_dataset");
        Map<String, Object> records = propertySchema(schema, "records");

        assertThat(records.get("type")).isEqualTo("array");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) records.get("items");
        assertThat(items.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) items.get("properties");
        assertThat(properties).containsKeys("fullAddress", "sourceAttachmentId");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) items.get("required");
        assertThat(required).containsExactlyInAnyOrder("fullAddress", "sourceAttachmentId");
    }

    @Test
    void wrongTypedArrayArgumentIsRejectedWithAPreciseValidationErrorInsteadOfSilentlyCoercing() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(datasetRegistry());

        assertThatThrownBy(() -> mapper.toAction("storedataset__create_dataset",
                Map.of("sourceImageCount", 1, "sourceAttachmentIds", List.of("att-1"), "records", "not-an-array"),
                "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("records")
                .hasMessageContaining("array")
                .hasMessageContaining("string");
    }

    @Test
    void correctlyTypedArrayArgumentIsAccepted() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(datasetRegistry());

        ToolAction action = mapper.toAction("storedataset__create_dataset",
                Map.of("sourceImageCount", 1, "sourceAttachmentIds", List.of("att-1"),
                        "records", List.of(Map.of("fullAddress", "A 1", "sourceAttachmentId", "att-1"))),
                "test");

        assertThat(action.tool()).isEqualTo("storedataset");
        assertThat(action.operation()).isEqualTo("CREATE_DATASET");
    }

    // Regression coverage for the reported production bug: search_game_tree's optional string
    // "keywords" sent as "" by the model was rejected as a "placeholder" before the call ever
    // reached MCP, even though it is an OPTIONAL field and "" simply means the model had nothing to
    // filter by. See NativeToolSchemaMapper#normalizeArguments/#isLiteralPlaceholder javadoc.

    @Test
    void optionalBlankStringArgumentIsOmittedRatherThanRejected() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        ToolAction action = mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "path", "Workspace",
                "keywords", "",
                "instance_type", "Folder",
                "studio_id", "abc-123",
                "datamodel_type", "Edit",
                "max_depth", 5
        ), "test");

        assertThat(action.arguments()).doesNotContainKey("keywords");
        assertThat(action.arguments())
                .containsEntry("path", "Workspace")
                .containsEntry("instance_type", "Folder")
                .containsEntry("studio_id", "abc-123")
                .containsEntry("datamodel_type", "Edit")
                .containsEntry("max_depth", 5);
    }

    @Test
    void requiredBlankStringArgumentIsStillRejected() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "studio_id", "",
                "datamodel_type", "Edit"
        ), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("Missing required argument 'studio_id'");
    }

    @Test
    void literalPlaceholderAngleBracketValueIsRejected() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "studio_id", "<studio_id>",
                "datamodel_type", "Edit"
        ), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void literalPlaceholderExampleIdValueIsRejected() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        assertThatThrownBy(() -> mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "studio_id", "example_id",
                "datamodel_type", "Edit"
        ), "test"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .hasMessageContaining("placeholder");
    }

    // Regression: coding__file_write's "content" argument for a legitimate pom.xml (or any
    // XML/HTML file) was rejected as "looks like a placeholder" purely because real XML starts
    // with "<" just like the single-token placeholder "<studio_id>" does - forcing the model to
    // abandon the tool and write the file via command_start + a Python one-liner instead. Only a
    // value that is ENTIRELY a single bracket-wrapped token is a placeholder; real content has
    // attributes, nested tags, punctuation, or newlines after the first "<".
    @Test
    void xmlFileContentStartingWithAngleBracketIsNotRejectedAsPlaceholder() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        ToolAction action = mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "studio_id", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project><modelVersion>4.0.0</modelVersion></project>",
                "datamodel_type", "Edit"
        ), "test");

        assertThat(action.arguments().get("studio_id")).asString().startsWith("<?xml");
    }

    @Test
    void normalConcreteRequiredStringIsAccepted() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        ToolAction action = mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "studio_id", "3fe2edf5-1043-43dc-8db3-38b3b980522a",
                "datamodel_type", "Edit"
        ), "test");

        assertThat(action.tool()).isEqualTo("mcp_roblox_search_game_tree");
        assertThat(action.arguments()).containsEntry("studio_id", "3fe2edf5-1043-43dc-8db3-38b3b980522a");
    }

    @Test
    void exactReportedSearchGameTreeModelOutputPassesValidationWithKeywordsOmitted() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(searchGameTreeFullSchemaRegistry());

        // The exact model output from the production bug report.
        ToolAction action = mapper.toAction("mcp_roblox_search_game_tree__call", Map.of(
                "path", "Workspace",
                "keywords", "",
                "instance_type", "Folder",
                "studio_id", "3fe2edf5-1043-43dc-8db3-38b3b980522a",
                "datamodel_type", "Edit",
                "max_depth", 5
        ), "test");

        assertThat(action.tool()).isEqualTo("mcp_roblox_search_game_tree");
        assertThat(action.operation()).isEqualTo("CALL");
        assertThat(action.arguments()).doesNotContainKey("keywords");
    }

    /**
     * Matches the real MCP {@code search_game_tree} schema from the bug report exactly: fields
     * {@code datamodel_type, head_limit, instance_type, keywords, max_depth, path, studio_id},
     * required only {@code datamodel_type, studio_id}.
     */
    private static ToolRegistry searchGameTreeFullSchemaRegistry() {
        ToolDefinition searchTree = new ToolDefinition("mcp_roblox_search_game_tree", "Searches Roblox tree.", List.of(
                new ToolOperationDefinition("CALL", "Search.", List.of(
                        new ToolArgumentDefinition("datamodel_type", true, ToolJsonSchema.string("Roblox datamodel type.")),
                        new ToolArgumentDefinition("head_limit", false, ToolJsonSchema.integer("Result limit.")),
                        new ToolArgumentDefinition("instance_type", false, ToolJsonSchema.string("Instance class filter.")),
                        new ToolArgumentDefinition("keywords", false, ToolJsonSchema.string("Optional keyword filter.")),
                        new ToolArgumentDefinition("max_depth", false, ToolJsonSchema.integer("Max tree depth.")),
                        new ToolArgumentDefinition("path", false, ToolJsonSchema.string("Root path to search from.")),
                        new ToolArgumentDefinition("studio_id", true, ToolJsonSchema.string("Exact connected Studio id."))
                ), false, ToolSafetyLevel.READ)
        ));
        return registry(searchTree);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parametersFor(NativeToolSchemaMapper mapper, String functionName) {
        return mapper.definitions(ToolIntent.LOCATION).stream()
                .filter(definition -> definition.name().equals(functionName))
                .map(NativeToolDefinition::parameters)
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertySchema(Map<String, Object> parameters, String argumentName) {
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");
        return (Map<String, Object>) properties.get(argumentName);
    }

    private static ToolRegistry datasetRegistry() {
        ToolJsonSchema recordSchema = ToolJsonSchema.object(
                Map.of(
                        "fullAddress", ToolJsonSchema.string("Full postal address"),
                        "sourceAttachmentId", ToolJsonSchema.string("Source attachment id")
                ),
                List.of("fullAddress", "sourceAttachmentId"),
                "One extracted store record");
        ToolDefinition storeDataset = new ToolDefinition("storedataset", "Canonical dataset.", List.of(
                new ToolOperationDefinition("CREATE_DATASET", "Create dataset.", List.of(
                        new ToolArgumentDefinition("sourceImageCount", "number", true, "Count"),
                        new ToolArgumentDefinition("sourceAttachmentIds", true,
                                ToolJsonSchema.arrayOf(ToolJsonSchema.string("An attachment id"), "Attachment ids")),
                        new ToolArgumentDefinition("records", true,
                                ToolJsonSchema.arrayOf(recordSchema, "Extracted records"))
                ), true, ToolSafetyLevel.WRITE)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(storeDataset);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static ToolRegistry twoToolRegistry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Manages the Knowledge Workspace.", List.of(
                new ToolOperationDefinition("SEARCH_CONTENT", "Search knowledge content.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition web = new ToolDefinition("web", "Searches the live public web.", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search current internet information.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(knowledge, web);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static ToolRegistry mixedRegistry() {
        ToolDefinition robloxSearch = new ToolDefinition("mcp_roblox_search_game_tree", "Searches Roblox tree.", List.of(
                new ToolOperationDefinition("CALL", "Search.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition robloxExecute = new ToolDefinition("mcp_roblox_execute_luau", "Executes Luau.", List.of(
                new ToolOperationDefinition("CALL", "Execute.", List.of(
                        new ToolArgumentDefinition("script", "string", true, "Script")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return registry(robloxSearch, robloxExecute);
    }

    private static ToolRegistry mcpRegistry() {
        ToolDefinition searchTree = new ToolDefinition("mcp_roblox_search_game_tree", "Searches Roblox tree.", List.of(
                new ToolOperationDefinition("CALL", "Search.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition listStudios = new ToolDefinition("mcp_roblox_list_roblox_studios", "Lists studios.", List.of(
                new ToolOperationDefinition("CALL", "List.", List.of(), false, ToolSafetyLevel.READ)
        ));
        return registry(searchTree, listStudios);
    }

    private static ToolRegistry strictRobloxRegistry() {
        ToolDefinition searchTree = new ToolDefinition("mcp_roblox_search_game_tree", "Searches Roblox tree.", List.of(
                new ToolOperationDefinition("CALL", "Search.", List.of(
                        new ToolArgumentDefinition("studio_id", true,
                                ToolJsonSchema.string("Exact connected Studio id from list_roblox_studios.")),
                        new ToolArgumentDefinition("datamodel_type", true,
                                ToolJsonSchema.string("Roblox datamodel type.")
                                        .withEnumValues(List.of("Edit", "Client", "Server"))),
                        new ToolArgumentDefinition("query", false,
                                ToolJsonSchema.string("Search query."))
                ), false, ToolSafetyLevel.READ)
        ));
        return registry(searchTree);
    }

    private static ToolRegistry runtimeRegistry() {
        ToolDefinition web = new ToolDefinition("web", "Searches the public web.", List.of(
                new ToolOperationDefinition("SEARCH_WEB", "Search public web.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition robloxSearch = new ToolDefinition("mcp_roblox_search_game_tree", "Search Roblox tree.", List.of(
                new ToolOperationDefinition("CALL", "Search tree.", List.of(
                        new ToolArgumentDefinition("query", "string", true, "Search query")
                ), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition robloxList = new ToolDefinition("mcp_roblox_list_roblox_studios", "List Roblox studios.", List.of(
                new ToolOperationDefinition("CALL", "List studios.", List.of(), false, ToolSafetyLevel.READ)
        ));
        ToolDefinition robloxExecute = new ToolDefinition("mcp_roblox_execute_luau", "Execute Luau.", List.of(
                new ToolOperationDefinition("CALL", "Execute script.", List.of(
                        new ToolArgumentDefinition("script", "string", true, "Script")
                ), true, ToolSafetyLevel.WRITE)
        ));
        ToolDefinition postgresStatus = new ToolDefinition("mcp_postgres_get_status", "Inspect Postgres.", List.of(
                new ToolOperationDefinition("CALL", "Get status.", List.of(), false, ToolSafetyLevel.READ)
        ));
        return registry(web, robloxSearch, robloxList, robloxExecute, postgresStatus);
    }

    private static ToolRegistry registry(ToolDefinition... definitions) {
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(definitions);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }
}
