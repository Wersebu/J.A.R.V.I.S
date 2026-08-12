package com.jarvis.tools.runtime;

import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving the model always sees the full tool catalog.
 * Core's advisory intent hint must never narrow which tools the model can call.
 */
class NativeToolSchemaMapperTest {

    @Test
    void searchWebIntentDoesNotHideKnowledgeTool() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(twoToolRegistry());

        List<NativeToolDefinition> definitions = mapper.definitions(ToolIntent.SEARCH_WEB);

        assertThat(definitions).extracting(NativeToolDefinition::name)
                .anySatisfy(name -> assertThat(name).startsWith("knowledge__"));
        assertThat(definitions).extracting(NativeToolDefinition::name)
                .anySatisfy(name -> assertThat(name).startsWith("web__"));
    }

    @Test
    void everyIntentSeesTheSameFullCatalog() {
        NativeToolSchemaMapper mapper = new NativeToolSchemaMapper(twoToolRegistry());

        List<NativeToolDefinition> noToolIntent = mapper.definitions(ToolIntent.NO_TOOL);
        List<NativeToolDefinition> saveKnowledgeIntent = mapper.definitions(ToolIntent.SAVE_KNOWLEDGE);
        List<NativeToolDefinition> searchWebIntent = mapper.definitions(ToolIntent.SEARCH_WEB);

        assertThat(noToolIntent).hasSameSizeAs(saveKnowledgeIntent);
        assertThat(noToolIntent).hasSameSizeAs(searchWebIntent);
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
}
