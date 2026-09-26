package net.java21.crowfoot.collab.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세션 수명 상한 계약 — 만료 세션의 SEND는 ERROR로 거부되고 클라이언트 재접속이
 * 새 CONNECT 인증을 통과한다(2h 상한의 축소 재현, collab.session.max-lifetime=1ms).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "collab.session.max-lifetime=1ms")
class SessionLifetimeIntegrationTest {

    @LocalServerPort
    int port;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(session -> {
            if (session.isConnected()) {
                session.disconnect();
            }
        });
        sessions.clear();
    }

    @Test
    @DisplayName("수명 만료 후 SEND가 거부된다 — ERROR 프레임, 이어진 재접속은 새 인증으로 성립")
    void expiredSessionSendIsRejectedAndReconnectSucceeds() throws Exception {
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        StompSession session = connect(failure);

        Thread.sleep(50); // max-lifetime 1ms 경과 확정
        session.send("/app/models/sl1/join", "");

        // 서버가 ERROR 프레임을 보낸다(transport error로도 표면화) — 재접속 계약의 출발점
        Throwable signal = failure.get(5, TimeUnit.SECONDS);
        assertThat(signal).isNotNull();

        // 재접속은 새 CONNECT 인증을 통과해 그대로 성립한다 (stompjs reconnectDelay 등가)
        StompSession reconnected = connect(new CompletableFuture<>());
        assertThat(reconnected.isConnected()).isTrue();
    }

    private StompSession connect(CompletableFuture<Throwable> failure) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("X-USER-ID", "alice");
        headers.add("X-USER-NAME", "앨리스");
        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws/websocket",
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession s, StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable ex) {
                        failure.complete(ex);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable ex) {
                        failure.complete(ex);
                    }
                }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }
}
