package net.java21.crowfoot.collab.config;

import net.java21.crowfoot.collab.ws.StompPrincipal;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * STOMP 설정 — 엔드포인트 /ws(SockJS 폴백 포함), 내장 심플 브로커.
 *
 * <p>신원: CONNECT 프레임 헤더 X-USER-ID·X-USER-NAME을 세션 Principal로 승격한다.
 * 로컬 개발 계약 — 운영에서는 게이트웨이가 introspection으로 검증한 값을
 * 같은 헤더로 주입한다(클라이언트 위조 불가 구조로 확장).
 * 헤더가 없으면 연결을 거부한다(익명 세션을 두면 presence 신원이 무의미하다).
 *
 * <p>문구는 CONNECT 프레임의 accept-language 네이티브 헤더(ko/en/ja/zh)로 해석한다
 * (api-design.md §5.7 — resultCode 불변·resultMessage만 로케일화의 collab 축소판).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String HEADER_USER_ID = "X-USER-ID";
    static final String HEADER_USER_NAME = "X-USER-NAME";
    static final String HEADER_USER_AVATAR = "X-USER-AVATAR";
    static final String HEADER_USER_LOGIN = "X-USER-LOGIN";
    static final String HEADER_LANGUAGE = "accept-language";

    /** CONNECT 거부 문구 — 에러 프레임으로 클라이언트에 전달된다 */
    private static final Map<String, String> MISSING_USER_ID_MESSAGES = Map.of(
            "ko", "X-USER-ID 헤더가 필요합니다",
            "en", "The X-USER-ID header is required",
            "ja", "X-USER-IDヘッダーが必要です",
            "zh", "需要 X-USER-ID 请求头");

    /** X-USER-NAME 폴백 표시명 — {0} 자리에 userId */
    private static final Map<String, String> FALLBACK_NAME_FORMATS = Map.of(
            "ko", "사용자 {0}",
            "en", "User {0}",
            "ja", "ユーザー {0}",
            "zh", "用户 {0}");

    private static final List<String> SUPPORTED = List.of("ko", "en", "ja", "zh");

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // 개발 — vite 8080. 운영은 게이트웨이 도메인으로 제한
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 구독(브로커) — 서버→클라이언트 푸시. 발행(앱) — 클라이언트→서버 처리 요청
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String lang = languageOf(accessor.getFirstNativeHeader(HEADER_LANGUAGE));
                    String userId = accessor.getFirstNativeHeader(HEADER_USER_ID);
                    String userName = accessor.getFirstNativeHeader(HEADER_USER_NAME);
                    String avatarUrl = accessor.getFirstNativeHeader(HEADER_USER_AVATAR);
                    String userLogin = accessor.getFirstNativeHeader(HEADER_USER_LOGIN);
                    if (userId == null || userId.isBlank()) {
                        // CONNECT 거부 — 에러 프레임으로 클라이언트에 전달된다
                        throw new IllegalArgumentException(MISSING_USER_ID_MESSAGES.get(lang));
                    }
                    accessor.setUser(new StompPrincipal(userId,
                            userName == null || userName.isBlank()
                                    ? FALLBACK_NAME_FORMATS.get(lang).replace("{0}", userId) : userName,
                            avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl,
                            userLogin == null || userLogin.isBlank() ? null : userLogin));
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
