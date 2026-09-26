package net.java21.crowfoot.collab.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ErdChange → 락 대상 매핑(순수) — 웹 changes.ts 22종 대응표의 핵심 분기만 짚는다.
 * 서버는 문서를 몰라도 change JSON의 식별자 필드로 같은 락 단위를 찾아내야 한다.
 */
class ChangeTargetsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("table/patch는 TABLE(tableId) 대상이다")
    void tablePatchLocksTable() {
        Optional<LockRegistry.LockTarget> target =
                ChangeTargets.of(change("""
                        {"type":"table/patch","tableId":"t1","patch":{"name":"회원"}}"""));
        assertThat(target).contains(new LockRegistry.LockTarget("table", "t1"));
    }

    @Test
    @DisplayName("column·primaryKey·uniqueKey·index 변경은 모두 테이블 귀속 TABLE 대상이다")
    void structuralChildChangesLockParentTable() {
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"column/add\",\"tableId\":\"t1\",\"column\":{\"id\":\"c1\"}}")))
                .contains(new LockRegistry.LockTarget("table", "t1"));
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"column/remove\",\"tableId\":\"t1\",\"columnId\":\"c1\"}")))
                .contains(new LockRegistry.LockTarget("table", "t1"));
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"primaryKey/set\",\"tableId\":\"t1\",\"columnIds\":[\"c1\"]}")))
                .contains(new LockRegistry.LockTarget("table", "t1"));
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"index/set\",\"tableId\":\"t1\",\"index\":{\"id\":\"i1\"}}")))
                .contains(new LockRegistry.LockTarget("table", "t1"));
    }

    @Test
    @DisplayName("relationship/create는 자식 테이블(FK가 붙는 쪽) TABLE 대상이다")
    void relationshipCreateLocksChildTable() {
        Optional<LockRegistry.LockTarget> target = ChangeTargets.of(change("""
                {"type":"relationship/create","relationship":{"id":"r1","childTableId":"t2","parentTableId":"t1"}}"""));
        assertThat(target).contains(new LockRegistry.LockTarget("table", "t2"));
    }

    @Test
    @DisplayName("relationship/patch·remove는 RELATIONSHIP(relationshipId) 대상이다")
    void relationshipChangesLockRelationship() {
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"relationship/patch\",\"relationshipId\":\"r1\",\"patch\":{\"name\":\"fk\"}}")))
                .contains(new LockRegistry.LockTarget("relationship", "r1"));
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"relationship/remove\",\"relationshipId\":\"r1\"}")))
                .contains(new LockRegistry.LockTarget("relationship", "r1"));
    }

    @Test
    @DisplayName("note·area 변경은 각자 NOTE·AREA 대상이다")
    void noteAndAreaLockOwnTargets() {
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"note/patch\",\"noteId\":\"n1\",\"patch\":{\"text\":\"메모\"}}")))
                .contains(new LockRegistry.LockTarget("note", "n1"));
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"area/remove\",\"areaId\":\"a1\"}")))
                .contains(new LockRegistry.LockTarget("area", "a1"));
    }

    @Test
    @DisplayName("create 계열·캔버스 인라인(move·resize·color)은 락 없음 — LWW 대상")
    void createAndInlineCanvasChangesHaveNoTarget() {
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"table/create\",\"table\":{\"id\":\"t1\"}}"))).isEmpty();
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"note/create\",\"note\":{\"id\":\"n1\"}}"))).isEmpty();
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"node/move\",\"nodes\":{\"t1\":{\"x\":10,\"y\":20}}}"))).isEmpty();
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"node/color\",\"targetId\":\"t1\",\"color\":\"#fff\"}"))).isEmpty();
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"area/resize\",\"areaId\":\"a1\",\"size\":{\"w\":10,\"h\":10}}"))).isEmpty();
    }

    @Test
    @DisplayName("식별자가 없거나 비면 대상 없음 — 방어적으로 통과시킨다")
    void blankOrMissingIdentifierYieldsNoTarget() {
        assertThat(ChangeTargets.of(change("{\"type\":\"table/patch\"}"))).isEmpty();
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"table/patch\",\"tableId\":\"  \"}"))).isEmpty();
        assertThat(ChangeTargets.of(change(
                "{\"type\":\"table/patch\",\"tableId\":{\"nested\":true}}"))).isEmpty();
        assertThat(ChangeTargets.of(change("{\"type\":\"unknown/kind\"}"))).isEmpty();
    }

    private JsonNode change(String json) {
        return mapper.readTree(json);
    }
}
