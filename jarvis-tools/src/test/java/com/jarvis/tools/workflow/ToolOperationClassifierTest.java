package com.jarvis.tools.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the generic bootstrap-vs-answering tool classification. Uses the exact MCP
 * Roblox Studio tool names from the reported production bug (Core accepted a {@code
 * list_roblox_studios} result as a finished answer to "list the project's folders" instead of
 * continuing to {@code search_game_tree}) plus a spread of native Jarvis tool operations, to prove
 * the classifier is genuinely tool-catalog-agnostic rather than hardcoded to Roblox.
 */
class ToolOperationClassifierTest {

    @Test
    void pcActionsSeparateReadsSearchesPlaybackAndWrites() {
        for (String action : new String[] {"status", "devices", "current", "queue", "liked", "library", "playlists", "playlist_tracks", "album_tracks"}) {
            assertThat(ToolOperationClassifier.classify("pc", "SPOTIFY", java.util.Map.of("action", action)))
                    .isEqualTo(ToolOperationRole.READ);
        }
        assertThat(ToolOperationClassifier.classify("pc", "SPOTIFY", java.util.Map.of("action", "search"))).isEqualTo(ToolOperationRole.SEARCH);
        for (String action : new String[] {"play", "connect", "disconnect", "pause", "volume"}) {
            assertThat(ToolOperationClassifier.classify("pc", "SPOTIFY", java.util.Map.of("action", action)))
                    .isEqualTo(ToolOperationRole.EXECUTE);
        }
        for (String action : new String[] {"save", "remove", "create_playlist", "add_to_playlist"}) {
            assertThat(ToolOperationClassifier.classify("pc", "SPOTIFY", java.util.Map.of("action", action)))
                    .isEqualTo(ToolOperationRole.WRITE);
        }
        assertThat(ToolOperationClassifier.classify("pc", "SPOTIFY")).isEqualTo(ToolOperationRole.UNKNOWN);
        assertThat(ToolOperationClassifier.classify("pc", "SPOTIFY", java.util.Map.of("action", "future_action"))).isEqualTo(ToolOperationRole.UNKNOWN);
        assertThat(ToolOperationClassifier.classify("pc", "MEDIA", java.util.Map.of("action", "status"))).isEqualTo(ToolOperationRole.READ);
        assertThat(ToolOperationClassifier.classify("pc", "MEDIA", java.util.Map.of("action", "play"))).isEqualTo(ToolOperationRole.EXECUTE);
    }

    @Test
    void fileListingsAreInspectionButRuntimeListingsRemainDiscovery() {
        assertThat(ToolOperationClassifier.classify("coding", "FILE_LIST")).isEqualTo(ToolOperationRole.INSPECT);
        assertThat(ToolOperationClassifier.classify("mcp_fs_list_files", "CALL")).isEqualTo(ToolOperationRole.INSPECT);
        assertThat(ToolOperationClassifier.classify("coding", "WORKSPACE_LIST")).isEqualTo(ToolOperationRole.DISCOVERY);
    }

    @Test
    void listRobloxStudiosIsBootstrapDiscovery() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_list_roblox_studios", "CALL"))
                .isEqualTo(ToolOperationRole.DISCOVERY);
        assertThat(ToolOperationClassifier.classify("mcp_roblox_list_roblox_studios", "CALL").isBootstrap()).isTrue();
    }

    @Test
    void setActiveStudioIsBootstrapSelection() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_set_active_studio", "CALL"))
                .isEqualTo(ToolOperationRole.SELECTION);
        assertThat(ToolOperationClassifier.classify("mcp_roblox_set_active_studio", "CALL").isBootstrap()).isTrue();
    }

    @Test
    void searchGameTreeIsAnsweringSearchNotBootstrap() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_search_game_tree", "CALL"))
                .isEqualTo(ToolOperationRole.SEARCH);
        assertThat(ToolOperationClassifier.classify("mcp_roblox_search_game_tree", "CALL").isBootstrap()).isFalse();
    }

    @Test
    void inspectInstanceIsAnsweringInspect() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_inspect_instance", "CALL"))
                .isEqualTo(ToolOperationRole.INSPECT);
    }

    @Test
    void scriptReadIsAnsweringRead() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_script_read", "CALL"))
                .isEqualTo(ToolOperationRole.READ);
    }

    @Test
    void scriptSearchAndGrepAreAnsweringSearch() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_script_search", "CALL"))
                .isEqualTo(ToolOperationRole.SEARCH);
        assertThat(ToolOperationClassifier.classify("mcp_roblox_script_grep", "CALL"))
                .isEqualTo(ToolOperationRole.SEARCH);
    }

    @Test
    void executeLuauIsAnsweringExecute() {
        assertThat(ToolOperationClassifier.classify("mcp_roblox_execute_luau", "CALL"))
                .isEqualTo(ToolOperationRole.EXECUTE);
    }

    @Test
    void nativeToolOperationsClassifyReasonably() {
        assertThat(ToolOperationClassifier.classify("storedataset", "GET_DATASET")).isEqualTo(ToolOperationRole.READ);
        assertThat(ToolOperationClassifier.classify("storedataset", "SUBMIT_SCHEDULE")).isEqualTo(ToolOperationRole.WRITE);
        assertThat(ToolOperationClassifier.classify("knowledge", "READ_DOCUMENT")).isEqualTo(ToolOperationRole.READ);
        assertThat(ToolOperationClassifier.classify("knowledge", "LIST_TREE")).isEqualTo(ToolOperationRole.DISCOVERY);
        assertThat(ToolOperationClassifier.classify("web", "SEARCH_WEB")).isEqualTo(ToolOperationRole.SEARCH);
    }

    @Test
    void unrecognizedSignatureClassifiesAsUnknownAndIsNeverBootstrap() {
        ToolOperationRole role = ToolOperationClassifier.classify("mcp_widget_do_the_thing", "CALL");
        assertThat(role).isEqualTo(ToolOperationRole.UNKNOWN);
        assertThat(role.isBootstrap()).isFalse();
    }

    @Test
    void nullToolAndOperationDoNotThrow() {
        assertThat(ToolOperationClassifier.classify(null, null)).isEqualTo(ToolOperationRole.UNKNOWN);
    }
}
