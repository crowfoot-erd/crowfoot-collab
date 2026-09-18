package net.java21.crowfoot.collab.ws.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 채팅 발행 — /app/models/{modelId}/chat.
 * 검증 실패(blank·500자 초과)면 saved와 같이 조용히 무시된다(브로드캐스트 없음).
 */
public record ChatNotice(
        @NotBlank @Size(max = 500) String message) {
}
