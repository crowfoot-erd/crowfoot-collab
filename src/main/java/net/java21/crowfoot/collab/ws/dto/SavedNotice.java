package net.java21.crowfoot.collab.ws.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 저장 알림 발행 — /app/models/{modelId}/saved.
 * 문서를 저장한 클라이언트가 core-api 저장에 성공한 뒤 확정된 버전을 실어 보낸다.
 */
public record SavedNotice(
        @NotNull @Positive Integer version) {
}
