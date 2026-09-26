package net.java21.crowfoot.collab.ws.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * 커서 브로드캐스트 — /topic/models/{modelId}/cursor (일시적 — 문서를 건드리지 않는다).
 * type "move": 참가자 1명 이동 — cursor는 발신 원문 {x, y, selection, dragging} 그대로.
 * type "snapshot": join 시점 전체 참가자 커서(멱등 교체 — chat history 패턴).
 */
public record CursorEvent(String type, String userId, String name, JsonNode cursor, Instant at,
                          List<Entry> cursors) {

    public record Entry(String userId, String name, JsonNode cursor, Instant at) {
    }

    public static CursorEvent move(String userId, String name, JsonNode cursor, Instant at) {
        return new CursorEvent("move", userId, name, cursor, at, null);
    }

    public static CursorEvent snapshot(List<Entry> entries) {
        return new CursorEvent("snapshot", null, null, null, null, entries);
    }
}
