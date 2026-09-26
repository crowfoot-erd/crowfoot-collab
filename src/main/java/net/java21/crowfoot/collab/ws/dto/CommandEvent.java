package net.java21.crowfoot.collab.ws.dto;

import tools.jackson.databind.JsonNode;

/**
 * 커맨드 브로드캐스트 — /topic/models/{modelId}/commands. 발신자 포함 전원에게 간다
 * (심플 브로커) — 클라이언트는 actorId가 자신이면 적용을 스킵하고 seq만 기록한다.
 * seq는 룸 단위 단조 순번(Sequences — saved 푸시와 같은 공간)이자 "LWW의 시계"다.
 */
public record CommandEvent(long seq, String actorId, String actorName, JsonNode change) {
}
