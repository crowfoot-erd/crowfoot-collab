package net.java21.crowfoot.collab.ws.dto;

import java.time.Instant;
import java.util.List;

/**
 * 채팅 브로드캐스트 — /topic/models/{modelId}/chat. 두 가지 형태가 같은 토픽에 흐른다.
 *
 * <ul>
 *   <li>type "message" — 발언 1건의 즉시 전파. 단일 필드(userId~seq)만 채워지고 messages는 null.
 *       발신자에게도 에코된다(심플 브로커) — 클라이언트가 자기 발언을 우측 정렬로 그린다.
 *   <li>type "history" — join 시점의 최근 대화 창(오래된 순). messages만 채워지고 단일 필드는 null.
 *       수신 측은 목록을 이 창으로 통째로 교체한다(멱등 — presence 스냅샷과 같은 철학).
 * </ul>
 * seq는 서버 부여 단조 순번 — 클라이언트 정렬의 근거(프레임 처리 순서가 뒤집힐 수 있어서).
 */
public record ChatEvent(
        String type, // "message" | "history"
        String modelId,
        String userId,
        String name,
        String avatarUrl,
        String userLogin,
        String message,
        Instant at,
        long seq,
        List<ChatMessageEntry> messages) {
}
