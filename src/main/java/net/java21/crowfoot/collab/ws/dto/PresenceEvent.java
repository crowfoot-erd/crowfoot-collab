package net.java21.crowfoot.collab.ws.dto;

import net.java21.crowfoot.collab.ws.CollabRooms;

import java.time.Instant;
import java.util.List;

/**
 * presence 브로드캐스트 — /topic/models/{modelId}/presence.
 * 입퇴장 직후 항상 전체 참가자 스냅샷을 실어, 늦게 구독한 클라이언트도
 * 별도 조회 없이 목록을 그릴 수 있게 한다.
 */
public record PresenceEvent(
        String type, // "join" | "leave"
        String userId,
        String name,
        Instant at,
        List<CollabRooms.Participant> participants) {
}
