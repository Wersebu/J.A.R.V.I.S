package com.jarvis.api.websocket;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the WebSocket frame limits used by the shared realtime endpoint.
 */
class WebSocketConfigTest {

    private static final int MAX_TEXT_MESSAGE_SIZE = 4_194_304;
    private static final int MAX_BINARY_MESSAGE_SIZE = 8_388_608;

    @ParameterizedTest
    @ValueSource(ints = {16_384, 65_536, 262_144, 1_048_576})
    void configuredTextBufferAcceptsLargeMcpBridgePayloads(int payloadBytes) {
        WebSocketConfig config = new WebSocketConfig(
                null,
                null,
                null,
                null,
                MAX_TEXT_MESSAGE_SIZE,
                MAX_BINARY_MESSAGE_SIZE
        );

        ServletServerContainerFactoryBean container = config.webSocketContainer();
        DirectFieldAccessor accessor = new DirectFieldAccessor(container);

        assertThat(payloadBytes).isLessThanOrEqualTo((Integer) accessor.getPropertyValue("maxTextMessageBufferSize"));
        assertThat((Integer) accessor.getPropertyValue("maxBinaryMessageBufferSize")).isEqualTo(MAX_BINARY_MESSAGE_SIZE);
    }
}
