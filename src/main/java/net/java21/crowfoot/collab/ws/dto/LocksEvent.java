package net.java21.crowfoot.collab.ws.dto;

import java.time.Instant;
import java.util.List;

/**
 * 락 브로드캐스트 — /topic/models/{modelId}/locks. 모든 이벤트가 전체 락 스냅샷을 실는다
 * (presence·chat history와 같은 멱등 교체). sessionId는 내부용이라 내보내지 않는다.
 */
public record LocksEvent(List<LockView> locks) {

    /** 뷰 — 누가 어떤 객체를 언제부터 잠갔는지 */
    public record LockView(String targetType, String targetId, String userId, String userName,
                           Instant acquiredAt) {
    }
}
