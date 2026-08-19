package com.jarvis.tools.mcp;

import com.jarvis.tools.schema.ToolArgumentDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
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
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("studio_id", Map.of("type", "string", "description", "Studio id"));
        properties.put("datamodel_type", Map.of(
                "type", "string",
                "description", "Roblox datamodel type",
                "enum", List.of("Edit", "Client", "Server")));

        List<ToolArgumentDefinition> arguments = mapper.arguments(Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("studio_id", "datamodel_type")
        ));

        assertThat(arguments).extracting(ToolArgumentDefinition::name).containsExactly("studio_id", "datamodel_type");
        assertThat(arguments.get(0).required()).isTrue();
        assertThat(arguments.get(1).schema().enumValues()).containsExactly("Edit", "Client", "Server");
    }
}
