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

    @Test
    void mailQuestionsRouteToGmailAndNotToSpotify() {
        StoredDocument gmail = document("Gmail — czytanie poczty", List.of("Gmail", "poczta", "mail", "maile", "mejl", "mejle",
                "email", "e-mail", "skrzynka", "odebrane", "nieprzeczytane"), List.of("sprawdź pocztę", "co nowego na poczcie"));
        List<String> keywords = WorkflowRouter.keywords(gmail);
        assertThat(WorkflowRouter.mentions("co nowego na poczcie?", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("mam jakieś nowe maile?", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("sprawdź mejle od Anny", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("przeczytaj tego maila z banku", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("puść muzykę z polubionych", keywords)).isFalse();
        assertThat(WorkflowRouter.mentions("co nowego na poczcie?", WorkflowRouter.keywords(SPOTIFY))).isFalse();
    }

    @Test
    void planQuestionsRouteToTheCalendarAndNotToMailOrMusic() {
        StoredDocument calendar = document("Kalendarz Google — plan, wolne terminy, wydarzenia", List.of("kalendarz", "Google Calendar",
                "plan", "terminarz", "wydarzenie", "wydarzenia", "spotkanie", "spotkania", "wizyta", "przypomnienie"),
                List.of("co mam dzisiaj", "co mam jutro", "kiedy mam wolne", "dodaj do kalendarza", "przesuń spotkanie"));
        List<String> keywords = WorkflowRouter.keywords(calendar);
        List<String> aliases = List.of("co mam dzisiaj", "co mam jutro", "kiedy mam wolne", "dodaj do kalendarza", "przesuń spotkanie");
        assertThat(WorkflowRouter.saysAlias("co mam jutro?", aliases)).isTrue();
        assertThat(WorkflowRouter.saysAlias("Kiedy mam wolne w piątek?", aliases)).isTrue();
        assertThat(WorkflowRouter.mentions("dodaj wizytę u dentysty w piątek o 14", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("przełóż spotkanie z Anną na 16", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("co jest w moim kalendarzu?", keywords)).isTrue();
        assertThat(WorkflowRouter.mentions("puść muzykę z polubionych", keywords)).isFalse();
        assertThat(WorkflowRouter.mentions("co mam jutro?", WorkflowRouter.keywords(SPOTIFY))).isFalse();
    }

    private static StoredDocument document(String title, List<String> tags, List<String> aliases) {
        return new StoredDocument("id", "workflows/Spotify.md", title, "workflow", "active", "", tags, aliases, "1", "",
                "frontmatter", "hash", 1L, 0L, 10, 900, "", StoredDocument.INDEXED, "", List.of(), true);
    }
}
