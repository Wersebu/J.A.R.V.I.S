package com.jarvis.api.websocket;

import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class TestWebSocketSession implements WebSocketSession {

    private final String id;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<WebSocketMessage<?>> sentMessages = new ArrayList<>();
    private volatile boolean open = true;
    private volatile int textMessageSizeLimit;
    private volatile int binaryMessageSizeLimit;
    private volatile Consumer<WebSocketMessage<?>> sendHandler = message -> {
    };

    TestWebSocketSession(String id) {
        this.id = id;
    }

    void onSend(Consumer<WebSocketMessage<?>> sendHandler) {
        this.sendHandler = sendHandler == null ? message -> {
        } : sendHandler;
    }

    List<WebSocketMessage<?>> sentMessages() {
        return List.copyOf(sentMessages);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public URI getUri() {
        return URI.create("ws://localhost/test/" + id);
    }

    @Override
    public HttpHeaders getHandshakeHeaders() {
        return HttpHeaders.EMPTY;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return InetSocketAddress.createUnresolved("localhost", 8080);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return InetSocketAddress.createUnresolved("localhost", 49152);
    }

    @Override
    public String getAcceptedProtocol() {
        return null;
    }

    @Override
    public void setTextMessageSizeLimit(int messageSizeLimit) {
        this.textMessageSizeLimit = messageSizeLimit;
    }

    @Override
    public int getTextMessageSizeLimit() {
        return textMessageSizeLimit;
    }

    @Override
    public void setBinaryMessageSizeLimit(int messageSizeLimit) {
        this.binaryMessageSizeLimit = messageSizeLimit;
    }

    @Override
    public int getBinaryMessageSizeLimit() {
        return binaryMessageSizeLimit;
    }

    @Override
    public List<WebSocketExtension> getExtensions() {
        return List.of();
    }

    @Override
    public void sendMessage(@NonNull WebSocketMessage<?> message) throws IOException {
        sentMessages.add(message);
        sendHandler.accept(message);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public void close(@NonNull CloseStatus status) {
        open = false;
    }
}
