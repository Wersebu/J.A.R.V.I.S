package com.jarvis.tools.knowledge;

import com.jarvis.knowledge.workspace.KnowledgeToolResult;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving {@code KnowledgeTool.wrapResult} reports an honest {@code success}
 * flag instead of the previous hardcoded {@code true} - a blocked or not-yet-applied write must
 * never look identical to a completed one to the model.
 */
class KnowledgeToolWrapResultTest {

    @Test
    void appliedWriteIsReportedAsSuccess() throws Exception {
        KnowledgeToolResult result = new KnowledgeToolResult("knowledge.createDocument", true, false,
                "Document created", "node-1", "notes/file.txt", Instant.now(), Map.of());

        ToolResult wrapped = wrapResult(result);

        assertThat(wrapped.success()).isTrue();
        assertThat(wrapped.errorCode()).isEmpty();
        assertThat(wrapped.errorMessage()).isEmpty();
        assertThat(wrapped.requiresApproval()).isFalse();
    }

    @Test
    void readOnlyBlockedWriteIsReportedAsFailure() throws Exception {
        KnowledgeToolResult result = new KnowledgeToolResult("knowledge.createDocument", false, false,
                "Knowledge workspace is read-only", "node-1", "notes/file.txt", Instant.now(), Map.of());

        ToolResult wrapped = wrapResult(result);

        assertThat(wrapped.success()).isFalse();
        assertThat(wrapped.requiresApproval()).isFalse();
        assertThat(wrapped.errorCode()).isEqualTo("WRITE_NOT_APPLIED");
        assertThat(wrapped.errorMessage()).isEqualTo("Knowledge workspace is read-only");
    }

    @Test
    void autoDraftQueuedWriteIsReportedAsNotYetAppliedWithoutErrorCode() throws Exception {
        Map<String, Object> data = Map.of(
                "requiresApproval", true,
                "draftId", "draft-42",
                "targetPath", "notes/file.txt"
        );
        KnowledgeToolResult result = new KnowledgeToolResult("knowledge.createDocument", false, true,
                "Draft created. No filesystem changes applied.", "node-1", "notes/file.txt", Instant.now(), data);

        ToolResult wrapped = wrapResult(result);

        assertThat(wrapped.success()).isFalse();
        assertThat(wrapped.requiresApproval()).isTrue();
        assertThat(wrapped.draftId()).isEqualTo("draft-42");
        assertThat(wrapped.errorCode()).isEmpty();
        assertThat(wrapped.message()).isEqualTo("Draft created. No filesystem changes applied.");
    }

    private ToolResult wrapResult(KnowledgeToolResult result) throws Exception {
        KnowledgeTool tool = new KnowledgeTool(null, null, null);
        Method wrapResult = KnowledgeTool.class.getDeclaredMethod("wrapResult", KnowledgeToolResult.class);
        wrapResult.setAccessible(true);
        return (ToolResult) wrapResult.invoke(tool, result);
    }
}
