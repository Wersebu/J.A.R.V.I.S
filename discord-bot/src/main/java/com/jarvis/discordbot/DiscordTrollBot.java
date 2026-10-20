package com.jarvis.discordbot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Authenticates to Jarvis Core as the dedicated bot user, optionally publishes the
 * persona as that user's global prompt, then connects to Discord and starts answering.
 */
public final class DiscordTrollBot {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordTrollBot.class);

    private DiscordTrollBot() {
    }

    public static void main(String[] args) throws InterruptedException {
        BotConfig config;
        try {
            config = BotConfig.fromEnvironment();
        } catch (RuntimeException exception) {
            LOGGER.error("Configuration error: {}", exception.getMessage());
            System.exit(1);
            return;
        }

        JarvisClient jarvis = new JarvisClient(config);
        try {
            jarvis.login();
            if (config.pushPersona) {
                jarvis.publishPersona(config.persona);
            } else {
                LOGGER.info("BOT_PUSH_PERSONA=false; leaving the user's existing global prompt untouched.");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Could not initialize against Jarvis Core: {}", exception.getMessage());
            System.exit(1);
            return;
        }

        JDA jda = JDABuilder.createLight(config.discordToken,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT)
                .setActivity(Activity.playing("i tak jestem od was lepszy"))
                .addEventListeners(new DiscordMessageListener(config, jarvis))
                .build();

        jda.awaitReady();
        LOGGER.info("Jarvis troll bot is online as {}. Reply chance={}, mention-always={}.",
                jda.getSelfUser().getName(), config.replyChance, config.alwaysReplyOnMention);
    }
}
