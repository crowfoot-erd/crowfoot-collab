package net.java21.crowfoot.collab.auth;

import net.java21.crowfoot.collab.client.CoreInternalClient;
import net.java21.crowfoot.collab.config.CollabProperties;
import net.java21.crowfoot.collab.ws.StompPrincipal;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CONNECT 신원 확정 게이트 (v1.17 — 05-editor/03-collaboration.md Section 2.1) —
 * 모드별 한 곳에서 StompPrincipal을 만들고, 실패는 IllegalArgumentException(CONNECT 거부)으로
 * 정규화한다. 표시명·아바타·핸들은 모드별 원천이 다르다.
 *
 * <p>header(로컬 기본): X-USER-ID 필수 + X-USER-NAME/AVATAR/LOGIN 선택(기존 계약 그대로).
 * token(운영 기본): Authorization: Bearer → introspection sub가 신원, 프로필은 core 내부 API.
 * token 모드에서 X-USER-* 헤더는 무시한다(위조 차단).
 */
@Component
public class AuthGate {

    static final String HEADER_AUTHORIZATION = "Authorization";
    static final String HEADER_USER_ID = "X-USER-ID";
    static final String HEADER_USER_NAME = "X-USER-NAME";
    static final String HEADER_USER_AVATAR = "X-USER-AVATAR";
    static final String HEADER_USER_LOGIN = "X-USER-LOGIN";

    private static final String BEARER_PREFIX = "Bearer ";

    /** token 모드 CONNECT 거부 문구 — 인증 서버 계약: active=false·장애 모두 포괄 */
    private static final Map<String, String> INVALID_TOKEN_MESSAGES = Map.of(
            "ko", "유효하지 않은 액세스 토큰입니다",
            "en", "The access token is invalid",
            "ja", "アクセストークンが無効です",
            "zh", "访问令牌无效");

    /** token 모드 프로필 조회 실패 시 폴백 표시명 — 신원(sub)은 이미 확정됐다 */
    private static final Map<String, String> FALLBACK_NAME_FORMATS = Map.of(
            "ko", "사용자 {0}",
            "en", "User {0}",
            "ja", "ユーザー {0}",
            "zh", "用户 {0}");

    private final CollabProperties properties;
    private final AuthIntrospectionClient introspection;
    private final CoreInternalClient core;

    public AuthGate(CollabProperties properties, AuthIntrospectionClient introspection,
                    CoreInternalClient core) {
        this.properties = properties;
        this.introspection = introspection;
        this.core = core;
    }

    /** @throws IllegalArgumentException 거부 사유(로케일 문구) — CONNECT 에러 프레임으로 전달된다 */
    public StompPrincipal authenticate(StompHeaderAccessor accessor, String lang) {
        return isTokenMode() ? byToken(accessor, lang) : byHeaders(accessor, lang);
    }

    public boolean isTokenMode() {
        return "token".equalsIgnoreCase(properties.auth().mode());
    }

    private StompPrincipal byToken(StompHeaderAccessor accessor, String lang) {
        String header = accessor.getFirstNativeHeader(HEADER_AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException(INVALID_TOKEN_MESSAGES.get(lang));
        }
        // X-USER-*는 무시 — introspection sub만 신원이다(위조 차단)
        String sub = introspection.subject(header.substring(BEARER_PREFIX.length()))
                .orElseThrow(() -> new IllegalArgumentException(INVALID_TOKEN_MESSAGES.get(lang)));
        CoreInternalClient.Profile profile = core.profile(sub).orElse(null);
        String name = profile != null && profile.name() != null
                ? profile.name()
                : FALLBACK_NAME_FORMATS.get(lang).replace("{0}", sub);
        return new StompPrincipal(sub, name,
                profile == null ? null : profile.avatarUrl(),
                profile == null ? null : profile.githubLogin());
    }

    private StompPrincipal byHeaders(StompHeaderAccessor accessor, String lang) {
        String userId = accessor.getFirstNativeHeader(HEADER_USER_ID);
        String userName = accessor.getFirstNativeHeader(HEADER_USER_NAME);
        String avatarUrl = accessor.getFirstNativeHeader(HEADER_USER_AVATAR);
        String userLogin = accessor.getFirstNativeHeader(HEADER_USER_LOGIN);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException(AuthGateMessages.missingUserId(lang));
        }
        return new StompPrincipal(userId,
                userName == null || userName.isBlank()
                        ? FALLBACK_NAME_FORMATS.get(lang).replace("{0}", userId) : userName,
                avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl,
                userLogin == null || userLogin.isBlank() ? null : userLogin);
    }
}
