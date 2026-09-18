package net.java21.crowfoot.collab.ws.dto;

import java.time.Instant;

/**
 * 채팅 기록 항목 — 룸당 최근 50건 창의 원소. history 이벤트에 오래된 순으로 실린다.
 * seq는 서버가 부여한 단조 순번 — 인바운드 채널이 멀티스레드라 프레임 도착 순서가
 * 뒤집힐 수 있어, 정렬의 근거는 시각(at)이 아니라 seq다.
 */
public record ChatMessageEntry(
        String userId,
        String name,
        String avatarUrl,
        String userLogin,
        String message,
        Instant at,
        long seq) {
}
