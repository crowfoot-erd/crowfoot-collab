package net.java21.crowfoot.collab.ws;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 문서 룸 상태 — modelId → (STOMP 세션 ID → 참가자).
 *
 * <p>동시성: 룸 단위 변경(가입·해제·스냅샷)은 룸 객체에 synchronized로 직렬화한다.
 * 참가자 수가 수십 명 이하인 협업 룸에서 락 경쟁은 무시 가능하다(세션 이벤트 빈도).
 * 맵 자체는 ConcurrentHashMap — 룸 생성 경합만 원자적으로 처리한다.
 */
@Component
public class CollabRooms {

    /** 참가자 — 클라이언트가 CONNECT 헤더로 실어 올린 신원(로컬 개발. 운영은 게이트웨이 주입).
     *  avatarUrl은 선택(제공자 프로필 사진 — 없으면 null, 웹이 이니셜로 폴백). */
    public record Participant(String userId, String name, String avatarUrl, String userLogin) {
    }

    private final Map<String, Map<String, Participant>> rooms = new ConcurrentHashMap<>();

    /** 문서 룸 가입 — 같은 세션의 재가입은 갱신(이름 변경 등). true면 새 참가자 */
    public synchronized boolean join(String modelId, String sessionId, Participant participant) {
        Map<String, Participant> room = rooms.computeIfAbsent(modelId, k -> new LinkedHashMap<>());
        return room.put(sessionId, participant) == null;
    }

    /** 세션 해제(명시적 leave·disconnect 공용) — 제거된 참가자, 없으면 null */
    public synchronized Participant leave(String modelId, String sessionId) {
        Map<String, Participant> room = rooms.get(modelId);
        if (room == null) {
            return null;
        }
        Participant removed = room.remove(sessionId);
        if (room.isEmpty()) {
            rooms.remove(modelId, room);
        }
        return removed;
    }

    /** 룸 스냅샷 — 접속자 목록 표시용(첫 가입 응답·입퇴장 브로드캐스트에 실는다) */
    public synchronized List<Participant> participants(String modelId) {
        Map<String, Participant> room = rooms.get(modelId);
        return room == null ? List.of() : new ArrayList<>(room.values());
    }

    /** 이 세션이 붙어 있는 룸 — disconnect 시 해제할 룸을 찾는다(세션 → 룸 역인덱스) */
    private final Map<String, String> sessionRoom = new ConcurrentHashMap<>();

    public void bindSession(String sessionId, String modelId) {
        sessionRoom.put(sessionId, modelId);
    }

    public String unbindSession(String sessionId) {
        return sessionRoom.remove(sessionId);
    }
}
