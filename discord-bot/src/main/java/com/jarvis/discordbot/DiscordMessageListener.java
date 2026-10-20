package com.jarvis.discordbot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
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
    private static final String SUBCOMMAND_SHOW_PROMPT = "showprompt";
    private static final String SUBCOMMAND_EDIT_PROMPT = "editprompt";
    private static final String MODAL_EDIT_PROMPT = "trolbot-editprompt";
    private static final String MODAL_FIELD_PERSONA = "persona";
    private static final int MODAL_MAX_CHARS = 4000;
    private static final int DISCORD_MESSAGE_LIMIT = 2000;
    private static final long TYPING_REFRESH_SECONDS = 8L;

    private final BotConfig config;
    private final JarvisClient jarvis;
    private final ChannelSettings channelSettings;
    private final PersonaStore personaStore;

    private final ExecutorService workers = Executors.newFixedThreadPool(4, daemon("jarvis-bot-worker"));
    private final ScheduledExecutorService typing =
            Executors.newScheduledThreadPool(2, daemon("jarvis-bot-typing"));
    private final ConcurrentHashMap<String, ChannelState> states = new ConcurrentHashMap<>();

    public DiscordMessageListener(BotConfig config, JarvisClient jarvis, ChannelSettings channelSettings,
            PersonaStore personaStore) {
        this.config = config;
        this.jarvis = jarvis;
        this.channelSettings = channelSettings;
        this.personaStore = personaStore;
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
                        .addSubcommands(
                                new SubcommandData(SUBCOMMAND_CHANCE,
                                        "Szansa odpowiedzi na tym kanale, bez pingu (0 = nigdy, 1 = na kazda wiadomosc)")
                                        .addOptions(new OptionData(OptionType.NUMBER, "value",
                                                "Liczba od 0 do 1 (np. 0.5 = 50%)", true)
                                                .setMinValue(0.0).setMaxValue(1.0)),
                                new SubcommandData(SUBCOMMAND_SHOW_PROMPT,
                                        "Pokazuje aktualna osobowosc bota (prompt)"),
                                new SubcommandData(SUBCOMMAND_EDIT_PROMPT,
                                        "Edytuje osobowosc bota (prompt) w okienku"))
        ).queue(
                success -> LOGGER.info("Slash command /{} registered in guild {}", COMMAND, guild.getName()),
                error -> LOGGER.warn("Could not register /{} in guild {}: {}. Czy bot ma scope "
                        + "'applications.commands'?", COMMAND, guild.getName(), error.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!COMMAND.equals(event.getName())) {
            return;
        }
        String sub = event.getSubcommandName();
        if (SUBCOMMAND_CHANCE.equals(sub)) {
            handleChanceCommand(event);
        } else if (SUBCOMMAND_SHOW_PROMPT.equals(sub)) {
            handleShowPrompt(event);
        } else if (SUBCOMMAND_EDIT_PROMPT.equals(sub)) {
            handleEditPrompt(event);
        }
    }

    private void handleChanceCommand(SlashCommandInteractionEvent event) {
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

    private void handleShowPrompt(SlashCommandInteractionEvent event) {
        String persona = personaStore.current();
        String fenced = "Aktualna osobowosc bota:\n```\n" + persona + "\n```";
        if (fenced.length() <= DISCORD_MESSAGE_LIMIT) {
            event.reply(fenced).setEphemeral(true).queue();
            return;
        }
        // Too long for one ephemeral message: attach it as a text file instead.
        event.replyFiles(net.dv8tion.jda.api.utils.FileUpload.fromData(
                        persona.getBytes(java.nio.charset.StandardCharsets.UTF_8), "persona.txt"))
                .setEphemeral(true)
                .queue();
    }

    private void handleEditPrompt(SlashCommandInteractionEvent event) {
        String current = personaStore.current();
        String prefill = current.length() > MODAL_MAX_CHARS ? current.substring(0, MODAL_MAX_CHARS) : current;
        TextInput input = TextInput.create(MODAL_FIELD_PERSONA, "Osobowosc bota (prompt)", TextInputStyle.PARAGRAPH)
                .setValue(prefill)
                .setRequired(true)
                .setMaxLength(MODAL_MAX_CHARS)
                .build();
        Modal modal = Modal.create(MODAL_EDIT_PROMPT, "Edycja osobowosci TrollBota")
                .addComponents(ActionRow.of(input))
                .build();
        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!MODAL_EDIT_PROMPT.equals(event.getModalId())) {
            return;
        }
        String persona = event.getValue(MODAL_FIELD_PERSONA) == null ? ""
                : event.getValue(MODAL_FIELD_PERSONA).getAsString();
        event.deferReply(true).queue();
        workers.submit(() -> {
            try {
                personaStore.update(persona);
                jarvis.publishPersona(personaStore.current());
                event.getHook().sendMessage("Zapisane. Nowa osobowosc dziala od kolejnych odpowiedzi ("
                        + personaStore.current().length() + " znakow).").setEphemeral(true).queue();
                LOGGER.info("Persona edited from Discord by {}", event.getUser().getName());
            } catch (RuntimeException exception) {
                event.getHook().sendMessage("Nie udalo sie zapisac: " + exception.getMessage())
                        .setEphemeral(true).queue();
                LOGGER.warn("Persona edit failed: {}", exception.getMessage());
            }
        });
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
