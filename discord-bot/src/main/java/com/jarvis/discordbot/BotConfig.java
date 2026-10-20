package com.jarvis.discordbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * All runtime configuration, read from environment variables so the bot can be dropped on a server
 * without editing code. Only the three connection values are required; everything else has a sane
 * default.
 */
public final class BotConfig {

    private static final String DEFAULT_STYLE_HINT =
            "Odpowiadasz w swojej roli: krotko (1-2 zdania), ostro i wulgarnie, bez emoji, bez "
                    + "gratulacji i bez list. Gdy ktos chwali sie misja albo nagroda - nie gratuluj, "
                    + "tylko zjedz go, ze zrobiles to dawno i lepiej.";

    /** Discord bot token from the Developer Portal (Bot -> Reset Token). */
    public final String discordToken;

    /** Base URL of the running Jarvis Core, e.g. {@code http://localhost:8080}. */
    public final String jarvisBaseUrl;

    /** E-mail (login) of the dedicated Jarvis user this bot authenticates as. */
    public final String jarvisEmail;

    /** Password of that Jarvis user. */
    public final String jarvisPassword;

    /** When true, the bot pushes its persona to Core as the user's global prompt on startup. */
    public final boolean pushPersona;

    /** Persona text used as the Jarvis global prompt. Overridable via file; otherwise built-in. */
    public final String persona;

    /**
     * Default probability (0..1) of replying to an ordinary, non-mention message, used for any
     * channel that has no per-channel value set via {@code /trolbot randompickmessage}.
     */
    public final double defaultReplyChance;

    /** Always reply when the bot is @mentioned, regardless of the per-channel probability. */
    public final boolean alwaysReplyOnMention;

    /** If non-empty, the bot only reacts in these channel IDs. Empty = every text channel. */
    public final Set<String> allowedChannelIds;

    /** Hard cap on reply length (Discord's own limit is 2000). */
    public final int maxReplyChars;

    /** Prefix for the per-channel Jarvis conversationId, giving the bot memory per channel. */
    public final String conversationPrefix;

    /** Maximum number of recent channel messages carried as context into a single reply. */
    public final int contextMessages;

    /**
     * Short style directive injected into every message sent to Core, right where the model weighs
     * it most. It is what reliably keeps the bot in character against Core's own helpful identity;
     * override via {@code BOT_STYLE_HINT} without rebuilding. Blank disables it.
     */
    public final String styleHint;

    /** File where per-channel reply probabilities are persisted so they survive a restart. */
    public final Path settingsFile;

    /** File where a persona edited from Discord is persisted so it survives a restart. */
    public final Path personaStateFile;

    /** HTTP timeout for calls to Core (a tool-using answer can take a while). */
    public final int requestTimeoutSeconds;

    private BotConfig(
            String discordToken,
            String jarvisBaseUrl,
            String jarvisEmail,
            String jarvisPassword,
            boolean pushPersona,
            String persona,
            double defaultReplyChance,
            boolean alwaysReplyOnMention,
            Set<String> allowedChannelIds,
            int maxReplyChars,
            String conversationPrefix,
            int contextMessages,
            String styleHint,
            Path settingsFile,
            Path personaStateFile,
            int requestTimeoutSeconds) {
        this.discordToken = discordToken;
        this.jarvisBaseUrl = jarvisBaseUrl;
        this.jarvisEmail = jarvisEmail;
        this.jarvisPassword = jarvisPassword;
        this.pushPersona = pushPersona;
        this.persona = persona;
        this.defaultReplyChance = defaultReplyChance;
        this.alwaysReplyOnMention = alwaysReplyOnMention;
        this.allowedChannelIds = allowedChannelIds;
        this.maxReplyChars = maxReplyChars;
        this.conversationPrefix = conversationPrefix;
        this.contextMessages = contextMessages;
        this.styleHint = styleHint;
        this.settingsFile = settingsFile;
        this.personaStateFile = personaStateFile;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    /**
     * Builds the configuration from the process environment, failing fast with a clear message when
     * a required value is missing.
     */
    public static BotConfig fromEnvironment() {
        String discordToken = required("DISCORD_TOKEN");
        String baseUrl = stripTrailingSlash(optional("JARVIS_BASE_URL", "http://localhost:8080"));
        String email = required("JARVIS_BOT_EMAIL");
        String password = required("JARVIS_BOT_PASSWORD");

        boolean pushPersona = Boolean.parseBoolean(optional("BOT_PUSH_PERSONA", "true"));
        String persona = resolvePersona(optional("BOT_PERSONA_FILE", ""));

        double replyChance = clamp(parseDouble("BOT_REPLY_CHANCE", 0.15), 0.0, 1.0);
        boolean alwaysOnMention = Boolean.parseBoolean(optional("BOT_ALWAYS_REPLY_ON_MENTION", "true"));
        Set<String> allowed = parseChannelIds(optional("BOT_ALLOWED_CHANNEL_IDS", ""));
        int maxChars = (int) clamp(parseLong("BOT_MAX_REPLY_CHARS", 2000), 1, 2000);
        String conversationPrefix = optional("BOT_CONVERSATION_PREFIX", "discord-");
        int contextMessages = (int) clamp(parseLong("BOT_CONTEXT_MESSAGES", 50), 1, 200);
        String styleHint = optional("BOT_STYLE_HINT", DEFAULT_STYLE_HINT);
        Path settingsFile = Path.of(optional("BOT_SETTINGS_FILE", "channel-settings.json"));
        Path personaStateFile = Path.of(optional("BOT_PERSONA_STATE_FILE", "persona-current.txt"));
        int timeout = (int) Math.max(5, parseLong("JARVIS_REQUEST_TIMEOUT_SECONDS", 120));

        return new BotConfig(discordToken, baseUrl, email, password, pushPersona, persona, replyChance,
                alwaysOnMention, allowed, maxChars, conversationPrefix, contextMessages, styleHint,
                settingsFile, personaStateFile, timeout);
    }

    private static String resolvePersona(String personaFile) {
        if (personaFile != null && !personaFile.isBlank()) {
            try {
                String fromFile = Files.readString(Path.of(personaFile.strip())).strip();
                if (!fromFile.isBlank()) {
                    return fromFile;
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "BOT_PERSONA_FILE could not be read: " + personaFile, exception);
            }
        }
        return Persona.DEFAULT_PL;
    }

    private static Set<String> parseChannelIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value.strip();
    }

    private static String optional(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static double parseDouble(String key, double fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid number for " + key + ": " + value, exception);
        }
    }

    private static long parseLong(String key, long fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Invalid number for " + key + ": " + value, exception);
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
