package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes Spotify playback requests from the user's own words, so routine ones skip model deliberation. */
public final class SpotifyPlaybackIntent {
    private static final Pattern PLAY = Pattern.compile("\\b(play|listen)\\b|\\b(odpal|pusc|puszcz|wlacz|zagraj|odtworz)\\w*");
    private static final Pattern NEGATED = Pattern.compile("nie (uruchamiaj|odtwarzaj|wlaczaj|puszczaj)[^.!?]*|(do not|don't|without) (play|start)[^.!?]*");
    /** Greetings, verbs and fillers of "play me something (from liked) on Spotify" - nothing that names what to play. */
    private static final Set<String> FILLER = Set.of(
            "siemka", "siema", "siemanko", "hej", "czesc", "elo", "yo", "jarvis", "prosze", "please", "hey", "hi", "hello",
            "mi", "nam", "sobie", "cos", "cokolwiek", "jakas", "jakies", "jakiegos", "jakis", "fajnego", "fajnej", "fajne",
            "dobrego", "dobrej", "dobre", "muzyke", "muzyki", "muzyka", "music", "some", "something", "anything", "me", "us",
            "on", "na", "w", "z", "ze", "moich", "moje", "moja", "polubionych", "polubione", "ulubionych", "ulubione",
            "liked", "songs", "song", "utwor", "utwory", "utworow", "piosenke", "piosenki", "spotify", "spotifaju",
            "spotifyu", "mozesz", "czy", "a", "i", "the", "for", "from", "my", "dla", "troche", "can", "you", "could");

    private SpotifyPlaybackIntent() {
    }

    /**
     * Whether the message itself asks to play something on Spotify (not merely mentions it).
     *
     * @param message the user's original message
     * @return true for a non-negated playback request naming Spotify
     */
    public static boolean isPlaybackRequest(String message) {
        String text = normalize(message);
        return text.contains("spotify") && !text.contains("web player") && playVerb(text);
    }

    /**
     * A playback request naming nothing specific ("odpal coś", "puść coś z polubionych"): the whole
     * procedure is known in advance, so Core can carry it out without asking the model at every step.
     *
     * @param message the user's original message
     * @return true when every word is a greeting, a playback verb or a filler
     */
    public static boolean isGeneric(String message) {
        if (!isPlaybackRequest(message)) return false;
        return Arrays.stream(normalize(message).split("[^a-z0-9]+"))
                .filter(word -> !word.isBlank())
                .allMatch(word -> FILLER.contains(word) || PLAY.matcher(word).matches());
    }

    static boolean playVerb(String normalized) {
        return PLAY.matcher(NEGATED.matcher(normalized).replaceAll("")).find();
    }

    static String normalize(String text) {
        return Normalizer.normalize(String.valueOf(text).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ł', 'l');
    }
}
