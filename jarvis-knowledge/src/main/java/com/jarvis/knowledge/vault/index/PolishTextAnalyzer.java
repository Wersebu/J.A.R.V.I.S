package com.jarvis.knowledge.vault.index;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical analyzer for Polish and English notes: lowercases, folds diacritics (including
 * {@code ł}, which Unicode decomposition does not fold), drops stop words and strips common Polish
 * inflection suffixes so that "audytów", "audyty" and "audytach" share the stem "audyt".
 * It is a light, rule-based stemmer, not a dictionary lemmatizer; the search additionally
 * matches stems by prefix to absorb what the rules miss.
 */
public final class PolishTextAnalyzer {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Set<String> STOP_WORDS = Set.copyOf(Arrays.asList(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is", "it", "of", "on", "or",
            "the", "to", "what", "when", "where", "which", "who", "with", "you", "your", "do", "does", "can",
            "aby", "albo", "ale", "bo", "by", "byc", "byl", "byla", "bylo", "czy", "dla", "do", "gdy", "gdzie", "go", "i",
            "ich", "im", "ja", "jak", "jaka", "jaki", "jakie", "jakiej", "jakim", "jako", "je", "jego", "jej", "jest",
            "jestem", "juz", "ktora", "ktore", "ktorej", "ktory", "ktorych", "kiedy", "lub", "ma", "mam", "mi", "mnie",
            "moj", "moja", "moje", "mozna", "my", "na", "nad", "nam", "nas", "nie", "o", "od", "oraz", "po", "pod",
            "przez", "przy", "sa", "sie", "sobie", "ta", "tak", "tam", "te", "tego", "tej", "ten", "to", "tu", "tym",
            "u", "w", "we", "wiec", "z", "za", "ze", "co", "jesli", "czego", "czym", "prosze", "powiedz", "podaj"
    ));
    private static final List<String> SUFFIXES = Arrays.stream(new String[]{
            "owania", "owanie", "owaniu", "owaniem", "ujacych", "ujacego", "ujacej",
            "ami", "ach", "owi", "owie", "ow", "om", "em", "ego", "emu", "ych", "ich", "ymi", "imi", "iej", "ej",
            "cie", "ach", "iem", "ie", "ia", "iu", "ii", "ym", "im", "ow", "y", "a", "e", "i", "o", "u"
    }).distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    private static final int MIN_STEM = 4;

    private PolishTextAnalyzer() {
    }

    /**
     * Folds case and diacritics.
     *
     * @param text text
     * @return folded text
     */
    public static String fold(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    /**
     * Returns stems of meaningful tokens.
     *
     * @param text text
     * @return stems in order (duplicates kept for term frequency)
     */
    public static List<String> stems(String text) {
        List<String> stems = new ArrayList<>();
        Matcher matcher = WORD.matcher(fold(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            if (token.length() < 2 && !Character.isDigit(token.charAt(0))) {
                continue;
            }
            stems.add(stem(token));
        }
        return stems;
    }

    /**
     * Stems one folded token.
     *
     * @param token folded token
     * @return stem
     */
    public static String stem(String token) {
        if (token.length() <= MIN_STEM || token.chars().allMatch(Character::isDigit)) {
            return token;
        }
        for (String suffix : SUFFIXES) {
            if (token.endsWith(suffix) && token.length() - suffix.length() >= MIN_STEM) {
                return token.substring(0, token.length() - suffix.length());
            }
        }
        return token;
    }
}
