package com.jarvis.tools.mcp;

import com.jarvis.tools.schema.ToolArgumentDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpSchemaMapperTest {

    private final McpSchemaMapper mapper = new McpSchemaMapper();

    @Test
    void objectSchemaWithEmptyPropertiesDeclaresNoArguments() {
        List<ToolArgumentDefinition> arguments = mapper.arguments(Map.of(
                "type", "object",
                "properties", Map.of()
        ));

        assertThat(arguments).isEmpty();
    }

    @Test
    void missingSchemaFallsBackToRawArgumentsObject() {
        List<ToolArgumentDefinition> arguments = mapper.arguments(null);

        assertThat(arguments).hasSize(1);
        assertThat(arguments.get(0).name()).isEqualTo("arguments");
    }

    @Test
    void namedPropertiesRemainTopLevelArguments() {
        List<ToolArgumentDefinition> arguments = mapper.arguments(Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query")
                ),
                "required", List.of("query")
        ));

        assertThat(arguments).extracting(ToolArgumentDefinition::name).containsExactly("query");
        assertThat(arguments.get(0).required()).isTrue();
    }
}
