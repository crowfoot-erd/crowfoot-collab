package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.ws.dto.CommandEvent;
import net.java21.crowfoot.collab.ws.dto.CommandNotice;
import net.java21.crowfoot.collab.ws.dto.CursorEvent;
import net.java21.crowfoot.collab.ws.dto.LocksEvent;
import net.java21.crowfoot.collab.ws.dto.LocksNotice;
import net.java21.crowfoot.collab.ws.dto.ModelSavedEvent;
import net.java21.crowfoot.collab.ws.dto.RejectionEvent;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17 실시간 채널 와이어 계약 — 커맨드(수렴 seq)·Edit Session Lock·커서를 실제 STOMP 왕복으로
 * 검증한다. header 모드(전원 EDITOR)라 인가 거부·token 계약은 TokenModeIntegrationTest가 담당한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "collab.cursor.min-interval=2000ms") // 쓰로틀 창을 넉넉히 — 드롭 판정을 결정적으로
class RealtimeIntegrationTest {

    @LocalServerPort
    int port;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter(mapper));
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
    @DisplayName("커맨드 발행이 발신자 에코로 돌아온다 — 단조 seq·actorId·change 원문 그대로")
    void commandEchoesWithMonotonicSeq() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<CommandEvent> commands = subscribeTyped(alice, "/topic/models/r1/commands", CommandEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r1/commands", notice("{\"type\":\"table/patch\",\"tableId\":\"t1\",\"patch\":{\"name\":\"회원\"}}"));
        CommandEvent first = await(commands);
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.actorId()).isEqualTo("alice");
        assertThat(first.actorName()).isEqualTo("앨리스");
        assertThat(first.change().path("type").asText()).isEqualTo("table/patch");
        assertThat(first.change().path("patch").path("name").asText()).isEqualTo("회원");

        alice.send("/app/models/r1/commands", notice("{\"type\":\"note/patch\",\"noteId\":\"n1\",\"patch\":{\"text\":\"메모\"}}"));
        assertThat(await(commands).seq()).isEqualTo(2); // 룸 단위 단조
    }

    @Test
    @DisplayName("두 클라이언트가 같은 순서로 받는다 — 수렴 규칙(서버 seq 순서)의 전제")
    void bothClientsReceiveSameOrder() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        StompSession bob = connect("bob", "밥");
        BlockingQueue<CommandEvent> toAlice = subscribeTyped(alice, "/topic/models/r2/commands", CommandEvent.class);
        BlockingQueue<CommandEvent> toBob = subscribeTyped(bob, "/topic/models/r2/commands", CommandEvent.class);
        Thread.sleep(300);

        // 에코를 기다렸다 보낸다 — 무수면 연속 send는 인바운드 처리 순서가 뒤집힐 수 있다
        alice.send("/app/models/r2/commands", notice("{\"type\":\"table/patch\",\"tableId\":\"a\",\"patch\":{}}"));
        await(toAlice);
        alice.send("/app/models/r2/commands", notice("{\"type\":\"table/patch\",\"tableId\":\"b\",\"patch\":{}}"));
        await(toAlice);

        assertThat(await(toBob).seq()).isEqualTo(1);
        assertThat(await(toBob).seq()).isEqualTo(2);
        assertThat(toBob.poll(300, TimeUnit.MILLISECONDS)).isNull(); // 그 이상 없다
    }

    @Test
    @DisplayName("형식 불량 커맨드는 조용히 무시된다 — 브로드캐스트 없음")
    void malformedCommandIsIgnored() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<CommandEvent> commands = subscribeTyped(alice, "/topic/models/r3/commands", CommandEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r3/commands", notice("{}")); // type 없음
        alice.send("/app/models/r3/commands", notice("[1,2,3]")); // 객체가 아님

        assertThat(commands.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("락 획득은 전체 스냅샷 브로드캐스트, 타인 획득 시도는 개인 큐 거부")
    void lockAcquireBroadcastsAndCompetingAcquireIsRejected() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        StompSession bob = connect("bob", "밥");
        BlockingQueue<LocksEvent> locks = subscribeTyped(alice, "/topic/models/r4/locks", LocksEvent.class);
        BlockingQueue<RejectionEvent> bobEvents =
                subscribeTyped(bob, "/user/queue/models/r4/collab-events", RejectionEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r4/locks", new LocksNotice("acquire", "table", "t1"));
        LocksEvent acquired = await(locks);
        assertThat(acquired.locks()).hasSize(1);
        assertThat(acquired.locks().get(0).targetType()).isEqualTo("table");
        assertThat(acquired.locks().get(0).targetId()).isEqualTo("t1");
        assertThat(acquired.locks().get(0).userId()).isEqualTo("alice");
        assertThat(acquired.locks().get(0).userName()).isEqualTo("앨리스");

        bob.send("/app/models/r4/locks", new LocksNotice("acquire", "table", "t1"));
        RejectionEvent rejected = await(bobEvents);
        assertThat(rejected.type()).isEqualTo("rejected");
        assertThat(rejected.reason()).isEqualTo("lock-held");

        alice.send("/app/models/r4/locks", new LocksNotice("release", "table", "t1"));
        LocksEvent released = await(locks);
        assertThat(released.locks()).isEmpty();

        bob.send("/app/models/r4/locks", new LocksNotice("acquire", "table", "t1"));
        LocksEvent taken = await(locks);
        assertThat(taken.locks()).singleElement()
                .satisfies(view -> assertThat(view.userId()).isEqualTo("bob"));
    }

    @Test
    @DisplayName("타인이 잠근 대상의 커맨드는 거부된다 — 2차 방어, 개인 큐 통보")
    void commandOnLockedTargetIsRejected() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        StompSession bob = connect("bob", "밥");
        BlockingQueue<LocksEvent> locks = subscribeTyped(alice, "/topic/models/r5/locks", LocksEvent.class);
        BlockingQueue<CommandEvent> commands = subscribeTyped(bob, "/topic/models/r5/commands", CommandEvent.class);
        BlockingQueue<RejectionEvent> bobEvents =
                subscribeTyped(bob, "/user/queue/models/r5/collab-events", RejectionEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r5/locks", new LocksNotice("acquire", "table", "t1"));
        await(locks);

        bob.send("/app/models/r5/commands", notice("{\"type\":\"table/patch\",\"tableId\":\"t1\",\"patch\":{}}"));
        RejectionEvent rejected = await(bobEvents);
        assertThat(rejected.reason()).isEqualTo("locked");
        assertThat(commands.poll(500, TimeUnit.MILLISECONDS)).isNull(); // 브로드캐스트 없음
    }

    @Test
    @DisplayName("세션 끊김이 락을 해제한다 — 남은 참가자에게 빈 스냅샷")
    void disconnectReleasesLocks() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        StompSession bob = connect("bob", "밥");
        BlockingQueue<LocksEvent> bobLocks = subscribeTyped(bob, "/topic/models/r6/locks", LocksEvent.class);
        Thread.sleep(300);
        alice.send("/app/models/r6/locks", new LocksNotice("acquire", "table", "t1"));
        await(bobLocks); // 앨리스 획득 확정

        alice.disconnect();

        LocksEvent cleared = await(bobLocks);
        assertThat(cleared.locks()).isEmpty();
    }

    @Test
    @DisplayName("늦게 join한 참가자가 현재 락 스냅샷을 받는다")
    void lateJoinerReceivesLockSnapshot() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<LocksEvent> aliceLocks = subscribeTyped(alice, "/topic/models/r7/locks", LocksEvent.class);
        Thread.sleep(300);
        alice.send("/app/models/r7/locks", new LocksNotice("acquire", "note", "n1"));
        await(aliceLocks);

        StompSession bob = connect("bob", "밥");
        BlockingQueue<LocksEvent> bobLocks = subscribeTyped(bob, "/topic/models/r7/locks", LocksEvent.class);
        Thread.sleep(300);
        bob.send("/app/models/r7/join", "");

        LocksEvent snapshot = await(bobLocks);
        assertThat(snapshot.locks()).singleElement()
                .satisfies(view -> assertThat(view.targetId()).isEqualTo("n1"));
    }

    @Test
    @DisplayName("커서 이동이 브로드캐스트되고 쓰로틀 창 이내 재발송은 드롭된다")
    void cursorMoveIsBroadcastAndThrottled() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<CursorEvent> cursor = subscribeTyped(alice, "/topic/models/r8/cursor", CursorEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r8/cursor", mapper.readTree("{\"x\":120,\"y\":40,\"selection\":[\"t1\"]}"));
        CursorEvent moved = await(cursor);
        assertThat(moved.type()).isEqualTo("move");
        assertThat(moved.userId()).isEqualTo("alice");
        assertThat(moved.name()).isEqualTo("앨리스");
        assertThat(moved.cursor().path("x").asInt()).isEqualTo(120);
        assertThat(moved.cursor().path("selection").get(0).asText()).isEqualTo("t1");

        alice.send("/app/models/r8/cursor", mapper.readTree("{\"x\":130,\"y\":50}")); // 창(min 2s) 이내
        assertThat(cursor.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("좌표 없는 커서 통보는 무시된다 — 브로드캐스트 없음")
    void cursorWithoutCoordinatesIsIgnored() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<CursorEvent> cursor = subscribeTyped(alice, "/topic/models/r9/cursor", CursorEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r9/cursor", mapper.readTree("{\"selection\":[\"t1\"]}"));
        assertThat(cursor.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @DisplayName("join 시점에 참가자 커서 스냅샷이 전달된다 — 멱등 교체")
    void cursorSnapshotOnJoin() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<CursorEvent> aliceCursor = subscribeTyped(alice, "/topic/models/r10/cursor", CursorEvent.class);
        Thread.sleep(300);
        alice.send("/app/models/r10/cursor", mapper.readTree("{\"x\":10,\"y\":20}"));
        await(aliceCursor); // 앨리스 커서 확정

        StompSession bob = connect("bob", "밥");
        BlockingQueue<CursorEvent> bobCursor = subscribeTyped(bob, "/topic/models/r10/cursor", CursorEvent.class);
        Thread.sleep(300);
        bob.send("/app/models/r10/join", "");

        CursorEvent snapshot = await(bobCursor);
        assertThat(snapshot.type()).isEqualTo("snapshot");
        assertThat(snapshot.cursors()).singleElement().satisfies(entry -> {
            assertThat(entry.userId()).isEqualTo("alice");
            assertThat(entry.cursor().path("x").asInt()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("saved 푸시가 커맨드와 같은 seq 공간의 현재값을 실은다 — 승계 판정용")
    void savedCarriesCurrentSeq() throws Exception {
        StompSession alice = connect("alice", "앨리스");
        BlockingQueue<CommandEvent> commands = subscribeTyped(alice, "/topic/models/r11/commands", CommandEvent.class);
        BlockingQueue<ModelSavedEvent> version =
                subscribeTyped(alice, "/topic/models/r11/version", ModelSavedEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/r11/commands", notice("{\"type\":\"table/patch\",\"tableId\":\"t1\",\"patch\":{}}"));
        await(commands);
        alice.send("/app/models/r11/commands", notice("{\"type\":\"table/patch\",\"tableId\":\"t1\",\"patch\":{}}"));
        long secondSeq = await(commands).seq(); // 두 번째 에코 seq

        alice.send("/app/models/r11/saved", new SavedNotice(553));

        ModelSavedEvent event = await(version);
        assertThat(event.version()).isEqualTo(553);
        assertThat(event.seq()).isEqualTo(secondSeq); // 저장 시점의 마지막 발급값 — 소비 없음
    }

    private CommandNotice notice(String json) {
        return new CommandNotice(mapper.readTree(json));
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
