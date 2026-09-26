package net.java21.crowfoot.collab.ws.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 락 발행 — /app/models/{modelId}/locks body. action은 acquire|release|renew,
 * targetType은 table|relationship|note|area(ChangeTargets 어휘와 같다).
 */
public record LocksNotice(
        @NotBlank String action,
        @NotBlank String targetType,
        @NotBlank String targetId) {
}
