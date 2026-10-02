package net.java21.crowfoot.collab.auth;

import net.java21.crowfoot.collab.ws.dto.CommandEvent;
import net.java21.crowfoot.collab.ws.dto.CommandNotice;
import net.java21.crowfoot.collab.ws.dto.CursorEvent;
import net.java21.crowfoot.collab.ws.dto.PresenceEvent;
import net.java21.crowfoot.collab.ws.dto.RejectionEvent;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * token 모드 운영 계약 통합 — introspection 신원·프로필·멤버십을 MockWebServer(auth·core 목)로
 * 검증한다: Bearer 승격·X-USER-* 위조 무시·inactive·장애 fail-closed·Viewer read-only·멤버십 캐시.
 *
 * <p>컨텍스트가 클래스에서 1회 공유되므로 introspection·멤버십 캐시도 공유된다 —
 * 테스트마다 토큰·사용자·문서 ID를 달리해 캐시 충돌을 없앤다(auth의 core 목 선례 방식).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenModeIntegrationTest {

    /** 문서별 멤버십 역할 스텁 — tm1=EDITOR(42)·tm2=VIEWER(77)·tm3=EDITOR(43) */
    private static final Map<String, String> MEMBERSHIPS = Map.of(
            "tm1", "EDITOR", "tm2", "VIEWER", "tm3", "EDITOR");

    /** introspect 요청 로그 — "POST {path} {form body}" (토큰 캐시·계약 검증) */
    private static final List<String> introspectLog = new CopyOnWriteArrayList<>();

    /** 문서별 멤버십 조회 수 — 캐시 검증(쓰기 발행 때마다 재검증하되 조회는 만료 시) */
    private static final Map<String, AtomicInteger> membershipHits = new ConcurrentHashMap<>();

    private static final MockWebServer authServer = new MockWebServer();
    private static final MockWebServer coreServer = new MockWebServer();

    static {
        authServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().readUtf8();
                introspectLog.add(request.getMethod() + " " + request.getPath() + " " + body);
                String token = body.replace("token=", "");
                return switch (token) {
                    case "good-token" -> active("42");
                    case "editor-token" -> active("43");
                    case "viewer-token" -> active("77");
                    case "outage-token" -> new MockResponse().setResponseCode(500); // 장애 시나리오
                    // 워크스페이스 액세스 토큰(MCP용) — 유효하지만 협업 채널은 받지 않는다
                    case "workspace-token" -> jsonResponse("{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":true,"
                            + "\"sub\":\"42\",\"typ\":\"WORKSPACE_TOKEN\",\"workspaceId\":\"77\",\"tokenId\":\"12\"}}");
                    default -> jsonResponse("{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":false}}");
                };
            }
        });
        coreServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.startsWith("/internal/core/collab/users/")) {
                    String userId = path.substring("/internal/core/collab/users/".length(),
                            path.length() - "/profile".length());
                    String name = switch (userId) {
                        case "42" -> "앨리스";
                        case "77" -> "뷰어";
                        default -> "에디터";
                    };
                    return jsonResponse("{\"header\":{\"isSuccessful\":true},\"response\":{\"userId\":\""
                            + userId + "\",\"name\":\"" + name + "\",\"avatarUrl\":null,\"githubLogin\":\"octocat\"}}");
                }
                // membership — tm{N}/membership?userId=
                String modelId = path.substring("/internal/core/collab/models/".length(),
                        path.indexOf("/membership"));
                membershipHits.computeIfAbsent(modelId, k -> new AtomicInteger()).incrementAndGet();
                String role = MEMBERSHIPS.getOrDefault(modelId, "NONE");
                return jsonResponse("{\"header\":{\"isSuccessful\":true},\"response\":{\"workspaceId\":\"w1\",\"role\":\""
                        + role + "\"}}");
            }
        });
        try {
            authServer.start();
            coreServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("목 서버 기동 실패", ex);
        }
    }

    @DynamicPropertySource
    static void collabProperties(DynamicPropertyRegistry registry) {
        registry.add("collab.auth.mode", () -> "token");
        registry.add("collab.auth.auth-base-url", () -> "http://localhost:" + authServer.getPort());
        registry.add("collab.core.base-url", () -> "http://localhost:" + coreServer.getPort());
    }

    @AfterAll
    static void shutDownServers() throws IOException {
        authServer.shutdown();
        coreServer.shutdown();
    }

    @LocalServerPort
    int port;

    private WebSocketStompClient stompClient;
    private final List<StompSession> sessions = new ArrayList<>();

    private final JsonMapper mapper = JsonMapper.builder().build();

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
    @DisplayName("Bearer CONNECT가 introspection sub를 신원으로 승격한다 — X-USER-* 위조는 무시")
    void bearerConnectUsesIntrospectionIdentity() throws Exception {
        StompSession alice = connect("good-token", "evil", "위조자");
        BlockingQueue<PresenceEvent> presence =
                subscribeTyped(alice, "/topic/models/tm1/presence", PresenceEvent.class);
        Thread.sleep(300);
        alice.send("/app/models/tm1/join", "");

        PresenceEvent event = await(presence);
        assertThat(event.userId()).isEqualTo("42"); // X-USER-ID: evil 무시 — sub만 신원
        assertThat(event.name()).isEqualTo("앨리스"); // core 프로필 표시명
        assertThat(event.participants()).first()
                .satisfies(p -> assertThat(p.role()).isEqualTo("EDITOR")); // presence에 역할 실림

        assertThat(introspectLog).anyMatch(log ->
                log.startsWith("POST /internal/auth/introspect") && log.contains("token=good-token"));
    }

    @Test
    @DisplayName("inactive 토큰은 연결이 거부된다 — active=false는 정상 응답이지만 실패")
    void inactiveTokenIsRejected() {
        assertThatThrownBy(() -> connect("revoked-token", null, null))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("워크스페이스 액세스 토큰으로는 접속할 수 없다 — typ이 ACCESS가 아니면 거부")
    void workspaceTokenIsRejected() {
        assertThatThrownBy(() -> connect("workspace-token", null, null))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("인증 서버 장애(5xx)는 거부로 이어진다 — fail-closed")
    void authOutageIsFailClosed() {
        assertThatThrownBy(() -> connect("outage-token", null, null))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    @DisplayName("Viewer는 read-only — join에 resync, 쓰기 발행은 rejected, 커맨드 중계 없음")
    void viewerIsReadOnly() throws Exception {
        StompSession viewer = connect("viewer-token", null, null);
        BlockingQueue<PresenceEvent> presence =
                subscribeTyped(viewer, "/topic/models/tm2/presence", PresenceEvent.class);
        BlockingQueue<CommandEvent> commands =
                subscribeTyped(viewer, "/topic/models/tm2/commands", CommandEvent.class);
        BlockingQueue<RejectionEvent> events =
                subscribeTyped(viewer, "/user/queue/models/tm2/collab-events", RejectionEvent.class);
        Thread.sleep(300);

        viewer.send("/app/models/tm2/join", "");
        PresenceEvent joined = await(presence);
        assertThat(joined.participants()).first()
                .satisfies(p -> assertThat(p.role()).isEqualTo("VIEWER"));
        RejectionEvent resync = await(events);
        assertThat(resync.type()).isEqualTo("resync");
        assertThat(resync.reason()).isEqualTo("read-only");

        viewer.send("/app/models/tm2/commands",
                new CommandNotice(mapper.readTree("{\"type\":\"table/patch\",\"tableId\":\"t1\",\"patch\":{}}")));
        RejectionEvent rejected = await(events);
        assertThat(rejected.type()).isEqualTo("rejected");
        assertThat(rejected.reason()).isEqualTo("read-only");
        assertThat(commands.poll(500, TimeUnit.MILLISECONDS)).isNull(); // 중계 없음
    }

    @Test
    @DisplayName("쓰기 발행 때마다 멤버십을 재검증하되 캐시(60s) 살아 있으면 core를 다시 부르지 않는다")
    void membershipIsCachedAcrossWrites() throws Exception {
        StompSession alice = connect("editor-token", null, null);
        BlockingQueue<PresenceEvent> presence =
                subscribeTyped(alice, "/topic/models/tm3/presence", PresenceEvent.class);
        BlockingQueue<CommandEvent> commands =
                subscribeTyped(alice, "/topic/models/tm3/commands", CommandEvent.class);
        BlockingQueue<CursorEvent> cursor =
                subscribeTyped(alice, "/topic/models/tm3/cursor", CursorEvent.class);
        Thread.sleep(300);

        alice.send("/app/models/tm3/join", "");
        await(presence);
        alice.send("/app/models/tm3/commands",
                new CommandNotice(mapper.readTree("{\"type\":\"table/patch\",\"tableId\":\"t1\",\"patch\":{}}")));
        assertThat(await(commands).actorId()).isEqualTo("43");
        alice.send("/app/models/tm3/cursor", mapper.readTree("{\"x\":1,\"y\":2}"));
        await(cursor);

        assertThat(membershipHits.get("tm3")).hasValue(1); // join에서 1회 — 이후 발행은 캐시
        assertThat(introspectLog.stream().filter(log -> log.contains("token=editor-token")).count())
                .isEqualTo(1); // introspection도 토큰 캐시 — CONNECT 1회
    }

    /** active introspection 응답 — sub는 토큰별 고정(캐시·위조 검증의 정합성용) */
    private static MockResponse active(String sub) {
        return jsonResponse("{\"header\":{\"isSuccessful\":true},\"response\":{\"active\":true,\"sub\":\""
                + sub + "\",\"exp\":4102444800}}");
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private StompSession connect(String token, String forgedUserId, String forgedUserName) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        if (forgedUserId != null) { // token 모드에서는 무시되어야 하는 위조 헤더
            headers.add("X-USER-ID", forgedUserId);
        }
        if (forgedUserName != null) {
            headers.add("X-USER-NAME", forgedUserName);
        }
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
        assertThat(value).as("5초 안에 이벤트 도달").isNotNull();
        return value;
    }
}
