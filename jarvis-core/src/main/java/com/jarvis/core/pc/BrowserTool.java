package com.jarvis.core.pc;

import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A real web browser for the model, running on the user's PC through the Windows bridge: open
 * pages, read them, click, type, scroll, go back and look at screenshots - for sites a plain HTTP
 * fetch cannot use (JavaScript apps, consent dialogs, Google Maps, shops, dashboards, forms).
 *
 * <p>Each action returns a snapshot with numbered interactive elements; the model refers to them by
 * {@code ref}. Screenshots are shown to vision models as images. Buying, paying and passwords need
 * the user's approval on the PC.</p>
 */
@Service
public class BrowserTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserTool.class);
    private static final String TOOL_NAME = "browser";
    private static final Duration ACTION = Duration.ofSeconds(60);
    /** Clicking "buy"/"pay" or typing a password waits for the user's click on the PC. */
    private static final Duration APPROVAL_WINDOW = Duration.ofSeconds(330);

    private final WindowsCodingBridgeGateway gateway;
    private final boolean enabled;

    /**
     * Creates the tool.
     *
     * @param gateway Windows bridge
     * @param enabled whether the tool is offered to the model
     */
    public BrowserTool(WindowsCodingBridgeGateway gateway, @Value("${jarvis.browser.enabled:true}") boolean enabled) {
        this.gateway = gateway;
        this.enabled = enabled;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "A real web browser on the user's PC that you control like a person: open a URL, read the page, click "
                + "links/buttons, type into fields, scroll, go back, take screenshots. Use it for pages that need "
                + "JavaScript or interaction (Google Maps reviews, shops, logins the user started, forms, consent "
                + "dialogs) and to check a website you built. Every action returns the page's numbered elements - "
                + "use those numbers as ref.";
    }

    @Override
    public ToolDefinition definition() {
        if (!enabled) {
            return new ToolDefinition(TOOL_NAME, getDescription(), List.of());
        }
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                op("OPEN", "Open a URL (https://..., or a local .html file via path) in the browser tab of this "
                                + "conversation. Returns the page snapshot: url, title, elements [ref] and text.",
                        ToolSafetyLevel.READ, false,
                        arg("url", false, "Address to open, e.g. https://maps.app.goo.gl/... or www.example.pl"),
                        arg("path", false, "Local .html file instead of url (inside folders the user allowed)")),
                op("SNAPSHOT", "Read the current page again (after waiting, or for more text with textOffset).",
                        ToolSafetyLevel.READ, false,
                        intArg("textOffset", "Character offset in the page text for long pages")),
                op("CLICK", "Click an element - preferably by ref from the latest elements list, or by its visible text. "
                                + "Close cookie/consent dialogs first (click 'Zaakceptuj wszystko' / 'Accept all').",
                        ToolSafetyLevel.READ, false,
                        arg("ref", false, "Element number, e.g. 12"),
                        arg("text", false, "Visible text of the element when you have no ref")),
                op("TYPE", "Type text into a field (replaces its content unless clear=false); submit=true presses Enter.",
                        ToolSafetyLevel.READ, false,
                        arg("text", true, "Text to type"),
                        arg("ref", false, "Field number from the elements list"),
                        arg("field", false, "Field label/placeholder when you have no ref"),
                        boolArg("submit", "Press Enter afterwards (search boxes, forms)"),
                        boolArg("clear", "Clear the field first (default true)")),
                op("PRESS", "Press a key: Enter, Tab, Escape, PageDown, PageUp, ArrowDown, ArrowUp, End, Home, Backspace.",
                        ToolSafetyLevel.READ, false, arg("key", true, "Key name")),
                op("SCROLL", "Scroll the page, or a scrollable panel by its ref (e.g. the reviews list on Google Maps - "
                                + "more items load as you scroll).",
                        ToolSafetyLevel.READ, false,
                        arg("direction", false, "down (default) or up"),
                        arg("ref", false, "Panel to scroll inside"),
                        intArg("amount", "Pixels (default 800)")),
                op("BACK", "Go back to the previous page.", ToolSafetyLevel.READ, false),
                op("WAIT", "Wait for slow pages (optionally until a text appears).", ToolSafetyLevel.READ, false,
                        intArg("seconds", "Max seconds (default 3, max 30)"),
                        arg("text", false, "Text to wait for")),
                op("SCREENSHOT", "See the page: a screenshot of the visible part (fullPage=true for the whole page). "
                                + "Use it when layout, images or what is visible matters; vision models get the image.",
                        ToolSafetyLevel.READ, false,
                        boolArg("fullPage", "Capture the whole page")),
                op("CLOSE", "Close this conversation's browser tab when you are done browsing.",
                        ToolSafetyLevel.READ, false)
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = request.operation() == null ? "" : request.operation().toUpperCase(Locale.ROOT);
        if (!enabled) {
            return failure(request, operation, "BROWSER_DISABLED", "The browser is disabled in Core (jarvis.browser.enabled=false).");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        request.arguments().forEach((key, value) -> {
            if (!key.startsWith("_") && value != null) {
                arguments.put(key, value);
            }
        });
        String workingDirectory = String.valueOf(request.arguments().getOrDefault("_workingDirectory", "")).strip();
        if (!workingDirectory.isBlank()) {
            arguments.put("baseDir", workingDirectory); // relative local pages resolve against the conversation folder
        }
        arguments.put("session", request.conversationId() == null ? "default" : request.conversationId());
        Duration timeout = "CLICK".equals(operation) || "TYPE".equals(operation) ? APPROVAL_WINDOW : ACTION;
        try {
            LOGGER.info("[BROWSER_TOOL] requestId={} operation={} target={}", request.requestId(), operation,
                    arguments.getOrDefault("url", arguments.getOrDefault("ref", arguments.getOrDefault("text", ""))));
            Map<String, Object> result = gateway.codingRequest("web_" + operation.toLowerCase(Locale.ROOT), arguments, timeout);
            Map<String, Object> data = result == null ? Map.of() : result;
            if (data.containsKey("imageBase64")) {
                // The loop shows the image to a vision model separately; keep the bytes out of the text.
                Map<String, Object> copy = new LinkedHashMap<>(data);
                copy.put("_imageBase64", copy.remove("imageBase64"));
                data = copy;
            }
            return new ToolResult(true, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(), "Browser " + operation + " finished", data, "", "", false, "");
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (message.contains("not connected") || message.contains("no Windows Bridge")) {
                message = message + " - the Jarvis Windows app must be running on the PC for browser__* tools.";
            }
            return failure(request, operation, "BROWSER_FAILED", message);
        }
    }

    private ToolResult failure(ToolRequest request, String operation, String code, String message) {
        return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                List.of(), message, Map.of("error", message), code, message, false, "");
    }

    private static ToolOperationDefinition op(String name, String description, ToolSafetyLevel safety, boolean write,
                                              ToolArgumentDefinition... arguments) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safety);
    }

    private static ToolArgumentDefinition arg(String name, boolean required, String description) {
        return new ToolArgumentDefinition(name, "string", required, description);
    }

    private static ToolArgumentDefinition intArg(String name, String description) {
        return new ToolArgumentDefinition(name, "integer", false, description);
    }

    private static ToolArgumentDefinition boolArg(String name, String description) {
        return new ToolArgumentDefinition(name, "boolean", false, description);
    }
}
