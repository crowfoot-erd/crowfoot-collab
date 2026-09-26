package net.java21.crowfoot.collab.ws.dto;

import java.time.Instant;

/**
 * 요청자 전용 개인 통보 — /user/queue/models/{modelId}/collab-events.
 * type "rejected": 쓰기 발행(커맨드·락)이 서버에서 차단됐다(read-only·lock-held·payload-too-large).
 * type "resync": 서버 상태로 강제 수화·읽기 전용 전환 지시(read-only 입장 등).
 */
public record RejectionEvent(String type, String modelId, String reason, Instant at) {

    public static RejectionEvent rejected(String modelId, String reason) {
        return new RejectionEvent("rejected", modelId, reason, Instant.now());
    }

    public static RejectionEvent resync(String modelId, String reason) {
        return new RejectionEvent("resync", modelId, reason, Instant.now());
    }
}
