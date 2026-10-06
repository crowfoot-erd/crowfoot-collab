package net.java21.crowfoot.collab.ws;

import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * ErdChange → 락 대상 추출 (순수) — 서버는 문서 상태를 갖지 않으므로(DB리스)
 * change JSON의 식별자 필드만으로 판정한다. 웹 ErdChange 22종(crowfoot-web changes.ts)의 대응표:
 *
 * <ul>
 *   <li>table/remove·patch — TABLE(tableId)</li>
 *   <li>column/*·primaryKey/set·uniqueKey/set·index/set — TABLE(tableId) — 컬럼·키는 테이블 귀속</li>
 *   <li>relationship/create — TABLE(relationship.childTableId) — FK 컬럼이 자식 테이블에 추가된다</li>
 *   <li>relationship/patch·remove — RELATIONSHIP(relationshipId)</li>
 *   <li>note/patch·remove — NOTE(noteId) · area/patch·remove — AREA(areaId)</li>
 *   <li>create 계열(대상이 아직 없음)·node/move·resize·color(캔버스 인라인) — 락 없음(LWW)</li>
 * </ul>
 */
public final class ChangeTargets {

    public static final String TABLE = "table";
    public static final String RELATIONSHIP = "relationship";
    public static final String NOTE = "note";
    public static final String AREA = "area";

    private ChangeTargets() {
    }

    public static Optional<LockRegistry.LockTarget> of(JsonNode change) {
        String type = change.path("type").asText("");
        return switch (type) {
            case "table/remove", "table/patch",
                    "column/add", "column/remove", "column/patch", "column/move",
                    "primaryKey/set", "uniqueKey/set", "index/set", "check/set" ->
                    target(TABLE, change.path("tableId"));
            case "relationship/create" ->
                    target(TABLE, change.path("relationship").path("childTableId"));
            case "relationship/patch", "relationship/remove" ->
                    target(RELATIONSHIP, change.path("relationshipId"));
            case "note/patch", "note/remove" -> target(NOTE, change.path("noteId"));
            case "area/patch", "area/remove" -> target(AREA, change.path("areaId"));
            default -> Optional.empty();
        };
    }

    private static Optional<LockRegistry.LockTarget> target(String targetType, JsonNode id) {
        String targetId = id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
        return targetId == null ? Optional.empty()
                : Optional.of(new LockRegistry.LockTarget(targetType, targetId));
    }
}
