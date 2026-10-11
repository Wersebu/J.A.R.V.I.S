package com.jarvis.knowledge.vault.read;

import com.jarvis.knowledge.vault.index.StoredDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRouterTest {

    private static final StoredDocument SPOTIFY = document("Spotify — odtwarzanie i biblioteka",
            List.of("Spotify", "muzyka", "piosenka", "playlista", "polubione", "telewizor"), List.of("muzyka na tv"));

    @Test
    void routesOnTagsTitleAndAliasesDespitePolishInflection() {
        List<String> keywords = WorkflowRouter.keywords(SPOTIFY);
        assertThat(WorkflowRouter.mentions("odpal coś na spotify", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("co to za piosenka?", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("puść muzykę z polubionych", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("przenieś to na telewizor", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("znajdź playlisty Maty", keywords)).isTrue();
    }

    @Test
    void everydayPhrasesRouteAsWholeAliasesButShortWordsAloneDoNot() {
        List<String> aliases = List.of("co leci", "co teraz leci", "co gra", "co teraz gra", "jaka to piosenka");
        assertThat(WorkflowRouter.saysAlias("Co teraz leci?", aliases)).isTrue();
        assertThat(WorkflowRouter.saysAlias("hej, co gra teraz?", aliases)).isTrue();
        assertThat(WorkflowRouter.saysAlias("jaka to piosenka", aliases)).isTrue();
        assertThat(WorkflowRouter.saysAlias("co leciało wczoraj w telewizji o grafice", aliases)).isFalse();
        assertThat(WorkflowRouter.saysAlias("w co gramy dziś wieczorem?", aliases)).isFalse();
    }

    @Test
    void doesNotRouteUnrelatedMessages() {
        List<String> keywords = WorkflowRouter.keywords(SPOTIFY);
        assertThat(WorkflowRouter.mentions("jaka jest jutro pogoda w Krakowie?", keywords)).isFalse();
        assertThat(WorkflowRouter.mentions("napisz funkcję w Javie", keywords)).isFalse();
        assertThat(WorkflowRouter.mentions("next", keywords)).isFalse();
    }

    private static StoredDocument document(String title, List<String> tags, List<String> aliases) {
        return new StoredDocument("id", "workflows/Spotify.md", title, "workflow", "active", "", tags, aliases, "1", "",
                "frontmatter", "hash", 1L, 0L, 10, 900, "", StoredDocument.INDEXED, "", List.of(), true);
    }
}
