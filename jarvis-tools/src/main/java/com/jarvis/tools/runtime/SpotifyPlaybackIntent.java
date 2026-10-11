package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Recognizes Spotify playback requests from the user's own words, so they skip the separate routing decision. */
public final class SpotifyPlaybackIntent {
    private static final Pattern PLAY = Pattern.compile("\\b(play|listen)\\b|\\b(odpal|pusc|puszcz|wlacz|zagraj|odtworz)\\w*");
    private static final Pattern NEGATED = Pattern.compile("nie (uruchamiaj|odtwarzaj|wlaczaj|puszczaj)[^.!?]*|(do not|don't|without) (play|start)[^.!?]*");

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

    static boolean playVerb(String normalized) {
        return PLAY.matcher(NEGATED.matcher(normalized).replaceAll("")).find();
    }

    static String normalize(String text) {
        return Normalizer.normalize(String.valueOf(text).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ł', 'l');
    }
}
