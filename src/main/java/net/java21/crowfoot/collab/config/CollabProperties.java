package net.java21.crowfoot.collab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 협업 서버 운영 설정 (v1.17 — 05-editor/03-collaboration.md Section 2.1).
 *
 * <p>auth.mode: {@code header}(로컬 기본)는 기존 X-USER-* CONNECT 헤더 계약(인가 원천 없음),
 * {@code token}(운영 기본)은 Authorization: Bearer → 인증 서버 introspection + core 내부 API로
 * 신원·프로필·멤버십을 해석한다. token 모드에서는 X-USER-* 헤더를 무시한다(위조 차단).
 */
@ConfigurationProperties(prefix = "collab")
public record CollabProperties(
        @DefaultValue Auth auth,
        @DefaultValue Core core,
        @DefaultValue Session session,
        @DefaultValue Locks locks,
        @DefaultValue Cursor cursor) {

    /** @param mode header|token, authBaseUrl 인증 서버 내부 베이스(예: http://crowfoot-auth:8081) */
    public record Auth(@DefaultValue("header") String mode,
                       @DefaultValue("") String authBaseUrl,
                       @DefaultValue("45s") Duration tokenCacheTtl) {
    }

    /** @param baseUrl core 내부 API 베이스(예: http://crowfoot-core-api:8082) */
    public record Core(@DefaultValue("") String baseUrl) {
    }

    /** @param maxLifetime 세션 수명 상한 — 초과하면 SEND를 거부(ERROR)해 재접속·재인증하게 한다 */
    public record Session(@DefaultValue("2h") Duration maxLifetime) {
    }

    /** @param ttl Edit Session Lock 무갱신 만료(lazy 정리) */
    public record Locks(@DefaultValue("2m") Duration ttl) {
    }

    /** @param minInterval 커서 발신 최소 간격(쓰로틀) — 이내의 재발송은 드롭한다 */
    public record Cursor(@DefaultValue("30ms") Duration minInterval) {
    }
}
