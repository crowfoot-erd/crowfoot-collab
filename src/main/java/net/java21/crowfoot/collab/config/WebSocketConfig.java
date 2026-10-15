package net.java21.crowfoot.collab.config;

import net.java21.crowfoot.collab.auth.AuthGate;
import net.java21.crowfoot.collab.ws.SessionLifetimes;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * STOMP 설정 — 엔드포인트 /ws(SockJS 폴백 포함), 내장 심플 브로커.
 *
 * <p>신원: CONNECT에서 {@link AuthGate}가 모드별(header·token)로 StompPrincipal을 확정한다.
 * 실패는 IllegalArgumentException → ERROR 프레임으로 연결이 거부된다(익명 세션을 두면
 * presence 신원이 무의미하다).
 *
 * <p>브로커 prefix에 /queue를 추가했다 — /user/queue/** 개인 큐(거부·resync 통보)가
 * 심플 브로커의 user destination 해석 결과로 /queue/**에 내려오기 때문이다.
 *
 * <p>하트비트 10s/10s: CONNECT에서 서버 계약으로 협상을 덮어쓰고(클라이언트가 0,0을
 * 보내도), 스케줄러를 등록해 죽은 세션을 서버가 능동적으로 끊는다 — disconnect가
 * 락·커서 정리의 원천이라 하트비트 없이는 잔존 락이 TTL까지 남는다.
 *
 * <p>세션 수명 상한(기본 2h): SEND 때 만료를 검사해 거부(ERROR)하면 클라이언트가
 * 재접속·재인증한다({@link SessionLifetimes}).
 *
 * <p>문구는 CONNECT 프레임의 accept-language 네이티브 헤더(ko/en/ja/zh)로 해석한다
 * (api-design.md §5.7 — resultCode 불변·resultMessage만 로케일화의 collab 축소판).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String HEADER_LANGUAGE = "accept-language";

    private static final List<String> SUPPORTED = List.of("ko", "en", "ja", "zh");

    /** 서버 하트비트 계약 — CONNECT 협상 값을 이 값으로 덮어쓴다 */
    private static final long[] HEARTBEAT = new long[]{10_000, 10_000};

    private final AuthGate authGate;
    private final SessionLifetimes lifetimes;
    private final ObjectProvider<TaskScheduler> heartbeatScheduler;

    /** heartbeatScheduler는 @EnableWebSocketMessageBroker가 등록하는 기본 bean
     *  (messageBrokerTaskScheduler)을 쓴다 — ObjectProvider로 늦게 해석하는 건 순환
     *  방지(생성자에서 당기면 setConfigurers 순환에 걸린다). 같은 틱을 심플 브로커 '\n'
     *  발송에도 재사용하고, 별도 스케줄러 bean을 만들면 이름 충돌로 기동에 실패한다. */
    public WebSocketConfig(AuthGate authGate, SessionLifetimes lifetimes,
                           ObjectProvider<TaskScheduler> heartbeatScheduler) {
        this.authGate = authGate;
        this.lifetimes = lifetimes;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // 개발 — vite 8080. 운영은 게이트웨이 도메인으로 제한
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 구독(브로커) — 서버→클라이언트 푸시(/user 목적지는 해석 결과가 /queue로 내려온다).
        // 발행(앱) — 클라이언트→서버 처리 요청
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(HEARTBEAT)
                .setTaskScheduler(heartbeatScheduler.getObject());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setTimeToFirstMessage(15_000); // 하트비트가 멈춘 세션을 끊는 상한 — 락 잔존 방지
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String lang = languageOf(accessor.getFirstNativeHeader(HEADER_LANGUAGE));
                    accessor.setHeartbeat(HEARTBEAT[0], HEARTBEAT[1]);
                    accessor.setUser(authGate.authenticate(accessor, lang));
                    lifetimes.record(Objects.requireNonNull(accessor.getSessionId(), "STOMP 세션 ID 없음"), lang);
                } else if (StompCommand.SEND.equals(accessor.getCommand())) {
                    String expired = lifetimes.expiryMessageIfExpired(
                            Objects.requireNonNull(accessor.getSessionId(), "STOMP 세션 ID 없음"));
                    if (expired != null) {
                        // 만료 세션의 발행 거부 — ERROR 프레임, 클라이언트 재접속이 새 인증을 통과한다
                        throw new IllegalArgumentException(expired);
                    }
                }
                return message;
            }
        });
    }

    /** accept-language 파싱 — q값 무시 우선순위 순, zh*는 간체 통일. 미매칭·빈값은 ko (게이트웨이 정책과 동일) */
    static String languageOf(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return "ko";
        }
        for (String part : acceptLanguage.split(",")) {
            String tag = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (tag.startsWith("zh")) {
                return "zh";
            }
            String primary = tag.split("-")[0];
            if (SUPPORTED.contains(primary)) {
                return primary;
            }
        }
        return "ko";
    }
}
