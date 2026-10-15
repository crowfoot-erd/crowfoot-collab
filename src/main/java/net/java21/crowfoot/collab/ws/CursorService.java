package net.java21.crowfoot.collab.ws;

import net.java21.crowfoot.collab.config.CollabProperties;
import net.java21.crowfoot.collab.ws.dto.CursorEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 참가자 커서 상태 — 일시적 채널(§1 분리 원칙): 문서(present)를 건드리지 않는다.
 * 참가자별 최종 상태만 보유하고, join 시 전체 스냅샷을 브로드캐스트한다(chat history 패턴).
 * 발신 쓰로틀(기본 30ms)은 초-프레임 폭주를 서버에서도 끊는다 — 상태 유지 없이 드롭하며,
 * 다음 발송이 최신 위치를 실어 온다(클라이언트가 계속 보낸다).
 */
@Service
public class CursorService {

    private record Entry(String userId, String name, JsonNode cursor, Instant at,
                         long lastBroadcastMillis) {
    }

    private final SimpMessagingTemplate messaging;
    private final CollabProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, ConcurrentMap<String, Entry>> cursors =
            new ConcurrentHashMap<>();

    public CursorService(SimpMessagingTemplate messaging, CollabProperties properties, Clock clock) {
        this.messaging = messaging;
        this.properties = properties;
        this.clock = clock;
    }

    /** 이동 통보 — {x, y, selection, dragging} 원문 그대로 중계한다 */
    public void move(String modelId, String sessionId, StompPrincipal actor, JsonNode cursor) {
        if (cursor == null || !cursor.isObject() || !cursor.has("x") || !cursor.has("y")) {
            return; // 좌표 없는 통보 — 조용히 무시
        }
        ConcurrentMap<String, Entry> room = cursors.computeIfAbsent(modelId, k -> new ConcurrentHashMap<>());
        long now = clock.millis();
        Entry existing = room.get(sessionId);
        if (existing != null && now - existing.lastBroadcastMillis() < properties.cursor().minInterval().toMillis()) {
            return; // 쓰로틀 창 이내 — 드롭(다음 발송이 최신 상태를 실어 온다)
        }
        Entry entry = new Entry(actor.userId(), actor.displayName(), cursor, Instant.now(clock), now);
        room.put(sessionId, entry);
        messaging.convertAndSend("/topic/models/" + modelId + "/cursor",
                CursorEvent.move(entry.userId(), entry.name(), entry.cursor(), entry.at()));
    }

    /** join 시점 전체 스냅샷 — 커서가 있는 참가자가 있을 때만(멱등 교체) */
    public void broadcastSnapshot(String modelId) {
        ConcurrentMap<String, Entry> room = cursors.get(modelId);
        if (room == null || room.isEmpty()) {
            return;
        }
        List<CursorEvent.Entry> entries = room.values().stream()
                .map(entry -> new CursorEvent.Entry(entry.userId(), entry.name(), entry.cursor(), entry.at()))
                .toList();
        messaging.convertAndSend("/topic/models/" + modelId + "/cursor", CursorEvent.snapshot(entries));
    }

    /** 세션 해제 — 커서는 조용히 사라진다(presence leave가 퇴장을 알린다) */
    public void removeSession(String modelId, String sessionId) {
        ConcurrentMap<String, Entry> room = cursors.get(modelId);
        if (room == null) {
            return;
        }
        room.remove(sessionId);
        if (room.isEmpty()) {
            cursors.remove(modelId, room);
        }
    }

    /** 미가입 세션 방어 — join 없이 이동만 통보한 세션의 커서를 전 룸에서 지운다 */
    public void removeSessionAnywhere(String sessionId) {
        cursors.forEach((modelId, room) -> {
            room.remove(sessionId);
            if (room.isEmpty()) {
                cursors.remove(modelId, room);
            }
        });
    }
}
