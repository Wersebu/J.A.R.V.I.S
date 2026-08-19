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
    void readOnlyRequestScopesOutWriteOperations() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(mixedRegistry());

        List<NativeToolDefinition> definitions = mapper.definitions(ToolIntent.NO_TOOL,
                "List folders in the active project tree", "Read the folder structure");

        assertThat(definitions).extracting(NativeToolDefinition::name)
                .contains("mcp_roblox_search_game_tree__call")
                .doesNotContain("mcp_roblox_execute_luau__call");
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
