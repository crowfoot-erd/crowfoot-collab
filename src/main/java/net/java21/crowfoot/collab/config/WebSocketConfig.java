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

/**
 * STOMP 설정 — 엔드포인트 /ws(SockJS 폴백 포함), 내장 심플 브로커.
 *
 * <p>신원: CONNECT 프레임 헤더 X-USER-ID·X-USER-NAME을 세션 Principal로 승격한다.
 * 로컬 개발 계약 — 운영에서는 게이트웨이가 introspection으로 검증한 값을
 * 같은 헤더로 주입한다(클라이언트 위조 불가 구조로 확장).
 * 헤더가 없으면 연결을 거부한다(익명 세션을 두면 presence 신원이 무의미하다).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final String HEADER_USER_ID = "X-USER-ID";
    static final String HEADER_USER_NAME = "X-USER-NAME";
    static final String HEADER_USER_AVATAR = "X-USER-AVATAR";
    static final String HEADER_USER_LOGIN = "X-USER-LOGIN";

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
                    String userId = accessor.getFirstNativeHeader(HEADER_USER_ID);
                    String userName = accessor.getFirstNativeHeader(HEADER_USER_NAME);
                    String avatarUrl = accessor.getFirstNativeHeader(HEADER_USER_AVATAR);
                    String userLogin = accessor.getFirstNativeHeader(HEADER_USER_LOGIN);
                    if (userId == null || userId.isBlank()) {
                        // CONNECT 거부 — 에러 프레임으로 클라이언트에 전달된다
                        throw new IllegalArgumentException("X-USER-ID 헤더가 필요합니다");
                    }
                    accessor.setUser(new StompPrincipal(userId, userName == null || userName.isBlank()
                            ? "사용자 " + userId : userName,
                            avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl,
                            userLogin == null || userLogin.isBlank() ? null : userLogin));
                }
                return message;
            }
        });
    }
}
