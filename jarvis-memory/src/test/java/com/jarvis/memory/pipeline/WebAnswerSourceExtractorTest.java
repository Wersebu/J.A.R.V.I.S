package com.jarvis.memory.pipeline;

import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.ToolCallingResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies trusted source extraction from WebSearchTool results.
 */
class WebAnswerSourceExtractorTest {

    private final WebAnswerSourceExtractor extractor = new WebAnswerSourceExtractor();

    @Test
    void returnsNoSourcesWhenNoWebSearchWasUsed() {
        ToolCallingResult result = new ToolCallingResult(true, "", List.of(), List.of(toolResult("knowledge", "SEARCH_CONTENT", List.of(
                Map.of("title", "Local", "url", "https://example.com/local")
        ))));

        assertThat(extractor.extract(result)).isEmpty();
    }

    @Test
    void extractsSourcesFromWebSearchToolResult() {
        ToolCallingResult result = webResult(List.of(
                Map.of("title", "CoinCodex Silver", "url", "https://coincodex.com/silver/", "snippet", "Silver price", "source", "CoinCodex")
        ));

        assertThat(extractor.extract(result))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.get("title")).isEqualTo("CoinCodex Silver");
                    assertThat(source.get("url")).isEqualTo("https://coincodex.com/silver/");
                    assertThat(source.get("domain")).isEqualTo("coincodex.com");
                });
    }

    @Test
    void removesDuplicateUrlsAndDomains() {
        ToolCallingResult result = webResult(List.of(
                Map.of("title", "First", "url", "https://www.example.com/a"),
                Map.of("title", "Duplicate URL", "url", "https://www.example.com/a"),
                Map.of("title", "Same domain", "url", "https://example.com/b"),
                Map.of("title", "Other", "url", "https://other.example/c")
        ));

        List<Map<String, Object>> sources = extractor.extract(result);

        assertThat(sources).hasSize(2);
        assertThat(sources).extracting(source -> source.get("domain"))
                .containsExactly("example.com", "other.example");
    }

    @Test
    void rejectsUnsafeUrls() {
        ToolCallingResult result = webResult(List.of(
                Map.of("title", "File", "url", "file:///C:/secret.txt"),
                Map.of("title", "Script", "url", "javascript:alert(1)"),
                Map.of("title", "Shell", "url", "powershell://run"),
                Map.of("title", "Safe", "url", "https://safe.example/page")
        ));

        assertThat(extractor.extract(result))
                .singleElement()
                .extracting(source -> source.get("url"))
                .isEqualTo("https://safe.example/page");
    }

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertThat(extractor.isSafeHttpUrl("http://example.com")).isTrue();
        assertThat(extractor.isSafeHttpUrl("https://example.com")).isTrue();
        assertThat(extractor.isSafeHttpUrl("cmd://example.com")).isFalse();
    }

    @Test
    void returnsNoSourcesForEmptyResults() {
        assertThat(extractor.extract(webResult(List.of()))).isEmpty();
    }

    @Test
    void limitsSourceCount() {
        ToolCallingResult result = webResult(List.of(
                Map.of("title", "One", "url", "https://one.example/a"),
                Map.of("title", "Two", "url", "https://two.example/a"),
                Map.of("title", "Three", "url", "https://three.example/a")
        ));

        assertThat(extractor.extract(result, 2)).hasSize(2);
    }

    @Test
    void extractsPresentationDomain() {
        assertThat(extractor.domain("https://www.coindesk.com/markets/bitcoin")).isEqualTo("coindesk.com");
        assertThat(extractor.domain("https://silverprice.org/")).isEqualTo("silverprice.org");
    }

    private ToolCallingResult webResult(List<Map<String, String>> results) {
        return new ToolCallingResult(true, "", List.of(), List.of(toolResult("web", "SEARCH_WEB", results)));
    }

    private ToolResult toolResult(String tool, String operation, List<? extends Map<String, String>> results) {
        return new ToolResult(
                true,
                tool,
                operation,
                "request-1",
                "conversation-1",
                false,
                List.of(),
                "ok",
                Map.<String, Object>of("results", results),
                "",
                "",
                false,
                ""
        );
    }
}
