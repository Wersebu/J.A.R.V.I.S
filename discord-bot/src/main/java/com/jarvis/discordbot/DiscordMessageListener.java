package com.jarvis.discordbot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Decides when the bot speaks and routes the chosen messages through {@link JarvisClient}.
 *
 * <p>Per channel it keeps a rolling buffer of messages it has seen but not yet forwarded. When it
 * answers (always on an @mention, otherwise with the channel's configured probability) it hands
 * that buffer to Core as "what happened since", while Core's own per-conversation memory carries
 * the longer thread - so the bot stays aware of the whole channel without re-sending history or
 * invoking the model for messages it stays silent on.</p>
 */
public final class DiscordMessageListener extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordMessageListener.class);
    private static final String COMMAND = "trolbot";
    private static final String SUBCOMMAND_CHANCE = "randompickmessage";
    private static final long TYPING_REFRESH_SECONDS = 8L;

    private final BotConfig config;
    private final JarvisClient jarvis;
    private final ChannelSettings channelSettings;

    private final ExecutorService workers = Executors.newFixedThreadPool(4, daemon("jarvis-bot-worker"));
    private final ScheduledExecutorService typing =
            Executors.newScheduledThreadPool(2, daemon("jarvis-bot-typing"));
    private final ConcurrentHashMap<String, ChannelState> states = new ConcurrentHashMap<>();

    public DiscordMessageListener(BotConfig config, JarvisClient jarvis, ChannelSettings channelSettings) {
        this.config = config;
        this.jarvis = jarvis;
        this.channelSettings = channelSettings;
    }

    /** Registers the /trolbot slash command in each guild as it becomes available. */
    @Override
    public void onGuildReady(GuildReadyEvent event) {
        registerCommands(event.getGuild());
    }

    private void registerCommands(Guild guild) {
        guild.updateCommands().addCommands(
                Commands.slash(COMMAND, "Ustawienia TrollBota")
                        .setGuildOnly(true)
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
                        .addSubcommands(new SubcommandData(SUBCOMMAND_CHANCE,
                                "Szansa odpowiedzi na tym kanale, bez pingu (0 = nigdy, 1 = na kazda wiadomosc)")
                                .addOptions(new OptionData(OptionType.NUMBER, "value",
                                        "Liczba od 0 do 1 (np. 0.5 = 50%)", true)
                                        .setMinValue(0.0).setMaxValue(1.0)))
        ).queue(
                success -> LOGGER.info("Slash command /{} registered in guild {}", COMMAND, guild.getName()),
                error -> LOGGER.warn("Could not register /{} in guild {}: {}. Czy bot ma scope "
                        + "'applications.commands'?", COMMAND, guild.getName(), error.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!COMMAND.equals(event.getName()) || !SUBCOMMAND_CHANCE.equals(event.getSubcommandName())) {
            return;
        }
        double value = event.getOption("value") == null ? config.defaultReplyChance
                : event.getOption("value").getAsDouble();
        double stored = channelSettings.setReplyChance(event.getChannel().getId(), value);
        int percent = (int) Math.round(stored * 100);
        event.reply("Ok. Na tym kanale odpowiadam teraz na **" + percent
                        + "%** wiadomosci bez pingu. Na ping (@) odpowiadam zawsze.")
                .setEphemeral(true)
                .queue();
        LOGGER.info("Reply chance for channel {} set to {}", event.getChannel().getId(), stored);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        // Never react to other bots, ourselves, webhooks, or messages outside a guild.
        if (event.getAuthor().isBot() || event.isWebhookMessage() || !event.isFromGuild()) {
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
        String author = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getEffectiveName();
        String line = author + ": " + content;

        ChannelState state = states.computeIfAbsent(channelId, key -> new ChannelState());
        List<String> batch = null;
        synchronized (state) {
            state.pending.addLast(line);
            while (state.pending.size() > config.contextMessages) {
                state.pending.removeFirst();
            }
            if (shouldReply(channelId, mentioned)) {
                batch = new ArrayList<>(state.pending);
                state.pending.clear();
            }
        }
        if (batch != null) {
            List<String> finalBatch = batch;
            workers.submit(() -> handle(event.getMessage(), channelId, finalBatch, mentioned));
        }
    }

    private boolean shouldReply(String channelId, boolean mentioned) {
        if (mentioned && config.alwaysReplyOnMention) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < channelSettings.replyChance(channelId);
    }

    private void handle(Message message, String channelId, List<String> batch, boolean mentioned) {
        MessageChannel channel = message.getChannel();
        ScheduledFuture<?> keepTyping = typing.scheduleAtFixedRate(
                () -> channel.sendTyping().queue(error -> { }),
                0, TYPING_REFRESH_SECONDS, TimeUnit.SECONDS);
        try {
            String prompt = buildPrompt(batch);
            String reply = jarvis.chat(config.conversationPrefix + channelId, prompt);
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
                channel.sendMessage(trimmed).queue();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to get a reply from Jarvis Core: {}", exception.getMessage());
        } finally {
            keepTyping.cancel(false);
        }
    }

    /**
     * Renders the un-forwarded messages as a short chat transcript. The framing matters: it must
     * read as a conversation the bot is already part of, never as a request to fetch something -
     * otherwise the model breaks character and apologizes that it "cannot access the channel".
     * Core's conversation memory supplies the earlier context.
     */
    private String buildPrompt(List<String> batch) {
        if (batch.size() == 1) {
            return "Ktos wlasnie napisal na czacie:\n" + batch.get(0)
                    + "\n\nOdbij pilke - odpowiedz krotko, w swoim stylu.";
        }
        StringBuilder builder = new StringBuilder(
                "Fragment rozmowy na czacie, w ktorej bierzesz udzial. "
                        + "Odpowiedz tylko na OSTATNIA wypowiedz (oznaczona >>>), krotko, w swoim stylu:\n");
        for (int i = 0; i < batch.size(); i++) {
            boolean last = i == batch.size() - 1;
            builder.append(last ? ">>> " : "").append(batch.get(i)).append('\n');
        }
        return builder.toString();
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Per-channel buffer of messages seen but not yet forwarded to Core. */
    private static final class ChannelState {
        private final Deque<String> pending = new ArrayDeque<>();
    }
}
