package com.jarvis.discordbot;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides when the bot speaks and routes the chosen messages through {@link JarvisClient}.
 *
 * <p>All Core calls run on a small executor, never on the JDA gateway thread, so a slow answer
 * never blocks Discord event processing.</p>
 */
public final class DiscordMessageListener extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordMessageListener.class);

    private final BotConfig config;
    private final JarvisClient jarvis;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "jarvis-bot-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, Long> lastReplyEpochMs = new ConcurrentHashMap<>();

    public DiscordMessageListener(BotConfig config, JarvisClient jarvis) {
        this.config = config;
        this.jarvis = jarvis;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Never react to other bots, ourselves, webhooks, or system messages.
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }
        if (!event.isFromGuild()) {
            return;
        }
        String channelId = event.getChannel().getId();
        if (!config.allowedChannelIds.isEmpty() && !config.allowedChannelIds.contains(channelId)) {
            return;
        }

        String content = event.getMessage().getContentDisplay().strip();
        if (content.isEmpty()) {
            return;
        }

        User self = event.getJDA().getSelfUser();
        boolean mentioned = event.getMessage().getMentions().getUsers().contains(self);

        if (!shouldReply(channelId, mentioned)) {
            return;
        }

        String author = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getEffectiveName();
        String prompt = "[Wiadomość od gracza \"" + author + "\" na kanale Discord]: " + content;
        String conversationId = config.conversationPrefix + channelId;

        event.getChannel().sendTyping().queue();
        workers.submit(() -> handle(event.getMessage(), conversationId, prompt, mentioned));
    }

    private void handle(Message message, String conversationId, String prompt, boolean mentioned) {
        try {
            String reply = jarvis.chat(conversationId, prompt);
            if (reply.isBlank()) {
                LOGGER.info("Jarvis returned an empty reply; staying silent.");
                return;
            }
            String trimmed = reply.length() > config.maxReplyChars
                    ? reply.substring(0, config.maxReplyChars)
                    : reply;
            if (mentioned) {
                message.reply(trimmed).mentionRepliedUser(false).queue();
            } else {
                message.getChannel().sendMessage(trimmed).queue();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to get a reply from Jarvis Core: {}", exception.getMessage());
        }
    }

    /**
     * Mentions always answer (and bypass cooldown). Otherwise reply with the configured probability,
     * but not more often than the per-channel cooldown allows.
     */
    private boolean shouldReply(String channelId, boolean mentioned) {
        if (mentioned && config.alwaysReplyOnMention) {
            lastReplyEpochMs.put(channelId, System.currentTimeMillis());
            return true;
        }
        if (ThreadLocalRandom.current().nextDouble() >= config.replyChance) {
            return false;
        }
        long now = System.currentTimeMillis();
        long cooldownMs = config.channelCooldownSeconds * 1000L;
        Long last = lastReplyEpochMs.get(channelId);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        lastReplyEpochMs.put(channelId, now);
        return true;
    }
}
