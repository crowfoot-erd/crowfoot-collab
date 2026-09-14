package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.ModelSavedEvent;
import net.java21.crowfoot.collab.ws.dto.PresenceEvent;
import net.java21.crowfoot.collab.ws.dto.SavedNotice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.lang.Nullable;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 와이어 계약 통합 — 실제 STOMP 클라이언트로 CONNECT 헤더 인증·presence 룸·저장 푸시를 검증한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PresenceIntegrationTest {

    @LocalServerPort
    int port;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // Jackson 3(JsonMapper)은 java.time·record를 코어에서 지원한다
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter(JsonMapper.builder().build()));
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
    @DisplayName("join 발행이 자기 에코로 돌아온다 — 전체 참가자 스냅샷 포함")
    void joinBroadcastsOwnPresence() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<PresenceEvent> presence = subscribe(alice, "/topic/models/m1/presence");
        Thread.sleep(300); // 구독 등록보다 발행이 앞서면 브로드캐스트를 놓친다

        alice.send("/app/models/m1/join", "");

        PresenceEvent event = await(presence);
        assertThat(event.type()).isEqualTo("join");
        assertThat(event.userId()).isEqualTo("alice");
        assertThat(event.name()).isEqualTo("앨리스");
        assertThat(event.participants())
                .extracting(CollabRooms.Participant::userId)
                .containsExactly("alice");
    }

    @Test
    @DisplayName("두 번째 참가자의 join이 기존 참가자에게 전달된다")
    void secondJoinReachesFirstParticipant() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<PresenceEvent> presence = subscribe(alice, "/topic/models/m2/presence");
        alice.send("/app/models/m2/join", "");
        await(presence); // 앨리스 입장 확정

        StompSession bob = connect("bob", "밥");
        BlockingQueue<PresenceEvent> bobPresence = subscribe(bob, "/topic/models/m2/presence");
        Thread.sleep(300);
        bob.send("/app/models/m2/join", "");

        PresenceEvent toAlice = await(presence);
        assertThat(toAlice.type()).isEqualTo("join");
        assertThat(toAlice.userId()).isEqualTo("bob");
        assertThat(toAlice.participants())
                .extracting(CollabRooms.Participant::userId)
                .containsExactly("alice", "bob");

        PresenceEvent toBob = await(bobPresence);
        assertThat(toBob.participants()).hasSize(2);
    }

    @Test
    @DisplayName("leave 발행이 퇴장 브로드캐스트로 이어진다 — 남은 참가자만")
    void leaveShrinksRoom() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        StompSession bob = connect("bob", "밥");
        BlockingQueue<PresenceEvent> presence = subscribe(alice, "/topic/models/m3/presence");
        Thread.sleep(300);
        alice.send("/app/models/m3/join", "");
        bob.send("/app/models/m3/join", "");
        await(presence); // 앨리스
        await(presence); // 밥 — 룸에 2명 확정

        bob.send("/app/models/m3/leave", "");

        PresenceEvent left = await(presence);
        assertThat(left.type()).isEqualTo("leave");
        assertThat(left.userId()).isEqualTo("bob");
        assertThat(left.participants())
                .extracting(CollabRooms.Participant::userId)
                .containsExactly("alice");
    }

    @Test
    @DisplayName("세션 끊김이 leave와 동일하게 정리된다 — leave 프레임 없이 사라진 경우")
    void disconnectCleansUpRoom() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        StompSession bob = connect("bob", "밥");
        BlockingQueue<PresenceEvent> presence = subscribe(alice, "/topic/models/m4/presence");
        Thread.sleep(300);
        alice.send("/app/models/m4/join", "");
        bob.send("/app/models/m4/join", "");
        await(presence); // 앨리스
        await(presence); // 밥 — 룸에 2명 확정

        bob.disconnect();

        PresenceEvent left = await(presence);
        assertThat(left.type()).isEqualTo("leave");
        assertThat(left.userId()).isEqualTo("bob");
    }

    @Test
    @DisplayName("saved 발행이 version 토픽으로 푸시된다 — 본문 없이 버전과 저장자")
    void savedPushesVersionEvent() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<ModelSavedEvent> version =
                subscribeTyped(alice, "/topic/models/m5/version", ModelSavedEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/m5/saved", new SavedNotice(553));

        ModelSavedEvent event = await(version);
        assertThat(event.modelId()).isEqualTo("m5");
        assertThat(event.version()).isEqualTo(553);
        assertThat(event.savedBy()).isEqualTo("alice");
        assertThat(event.savedByName()).isEqualTo("앨리스");
    }

    @Test
    @DisplayName("saved의 version이 양수가 아니면 무시된다 — 브로드캐스트 없음")
    void savedRejectsNonPositiveVersion() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<ModelSavedEvent> version =
                subscribeTyped(alice, "/topic/models/m6/version", ModelSavedEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/m6/saved", new SavedNotice(0));

        assertThat(version.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("X-USER-ID 없는 CONNECT는 거부된다 — 연결이 성립하지 않는다")
    void connectRejectsMissingUserId() {
        CompletableFuture<StompSession> future = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws/websocket",
                new WebSocketHttpHeaders(),
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                });

        // 인터셉터 예외로 CONNECT가 거부되면 서버가 소켓을 닫는다(ERROR 프레임 보증 없음)
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class);
    }

    private StompSession connect(String userId, String userName) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("X-USER-ID", userId);
        headers.add("X-USER-NAME", userName);
        StompSession session = stompClient.connectAsync("ws://localhost:" + port + "/ws/websocket",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                }).get(5, TimeUnit.SECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<PresenceEvent> subscribe(StompSession session, String destination) {
        return subscribeTyped(session, destination, PresenceEvent.class);
    }

    private <T> BlockingQueue<T> subscribeTyped(StompSession session, String destination, Class<T> type) {
        BlockingQueue<T> sink = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return type;
            }

            @Override
            public void handleFrame(@Nullable StompHeaders headers, Object payload) {
                sink.add(type.cast(payload));
            }
        });
        return sink;
    }

    private static <T> T await(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("5초 안에 브로드캐스트 도달").isNotNull();
        return value;
    }
}
