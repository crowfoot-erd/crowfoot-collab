package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.config.CollabProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 세션 수명 상한(기본 2h) — CONNECT 시각을 기록하고 SEND 때 만료를 검사한다.
 * 만료 세션의 SEND는 IllegalArgumentException(ERROR 프레임)로 거부되고 클라이언트가
 * 재접속하면 새 CONNECT 인증(Bearer 재발급 포함)을 통과한다. 토큰 갱신 주기보다 긴
 * 세션에 낡은 권한이 묵을 수 없게 하는 장치 (05-editor/03-collaboration.md Section 2.1).
 */
@Component
public class SessionLifetimes {

    /** 세션 수명 만료 SEND 거부 문구 */
    private static final Map<String, String> SESSION_EXPIRED_MESSAGES = Map.of(
            "ko", "세션 수명이 만료되어 다시 연결해 주세요",
            "en", "The session lifetime has expired. Please reconnect",
            "ja", "セッションの有効期間が切れました。再接続してください",
            "zh", "会话已过期，请重新连接");

    public record Session(Instant connectedAt, String lang) {
    }

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final CollabProperties properties;
    private final Clock clock;

    public SessionLifetimes(CollabProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void record(String sessionId, String lang) {
        sessions.put(sessionId, new Session(Instant.now(clock), lang));
    }

    public void forget(String sessionId) {
        sessions.remove(sessionId);
    }

    /** @return 만료됐으면 거부 문구(로케일), 살아 있으면 null */
    public String expiryMessageIfExpired(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return null; // CONNECT를 거친 적 없는 세션 — CONNECT 인터셉터가 이미 막는다
        }
        Instant deadline = session.connectedAt().plus(properties.session().maxLifetime());
        return deadline.isBefore(Instant.now(clock)) ? SESSION_EXPIRED_MESSAGES.get(session.lang()) : null;
    }
}
