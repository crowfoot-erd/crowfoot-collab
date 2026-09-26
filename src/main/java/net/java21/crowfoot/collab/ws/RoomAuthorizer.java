package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.auth.AuthGate;
import net.java21.crowfoot.collab.client.CoreInternalClient;
import org.springframework.stereotype.Component;

/**
 * 룸 인가 판정 단일 진입 (02-auth/requirements.md Section 1.6) — role 원천은 모드별로 다르다.
 *
 * <p>header(로컬): 인가 원천이 없으므로 EDITOR(기존 동작 — 전원 쓰기 가능 개발 계약).
 * token(운영): core 내부 API 멤버십(캐시 60s). "쓰기 발행 직전 재검증"은 매 발행 때
 * 이 메서드를 다시 부르는 것으로 실천된다 — 캐시가 살아 있으면 조회 없이, 만료됐으면 재조회.
 * core 장애는 "NONE"(read-only 폴백) — fail-closed.
 */
@Component
public class RoomAuthorizer {

    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_EDITOR = "EDITOR";
    public static final String ROLE_NONE = "NONE";

    private final AuthGate authGate;
    private final CoreInternalClient core;

    public RoomAuthorizer(AuthGate authGate, CoreInternalClient core) {
        this.authGate = authGate;
        this.core = core;
    }

    /** 현재 유효 역할 — 쓰기 경로에서 호출될 때마다 최신 판정(캐시 만료분은 재조회) */
    public String currentRole(String modelId, String userId) {
        if (!authGate.isTokenMode()) {
            return ROLE_EDITOR;
        }
        return core.membership(modelId, userId).orElse(ROLE_NONE);
    }

    /** 쓰기(커맨드·락 발행) 가능 — Owner/Editor만. Commenter/Viewer는 read-only 강제 */
    public static boolean canWrite(String role) {
        return ROLE_OWNER.equals(role) || ROLE_EDITOR.equals(role);
    }
}
