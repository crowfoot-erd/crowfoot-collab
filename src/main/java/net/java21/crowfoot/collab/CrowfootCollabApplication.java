package net.java21.crowfoot.collab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Crowfoot 실시간 협업 서버 (05-editor/03-collaboration.md 2차 구조) —
 * WebSocket(STOMP) 기반 문서 룸: 접속자 표시(presence)·저장 변경 푸시.
 *
 * <p>DB리스 — 룸 상태는 인메모리(CollabRooms)뿐이다. 영속 상태(문서 본체·버전)는
 * core-api가 원천이고 이 서버는 "누가 문서를 보고 있는가"의 실시간 계만 담는다.
 */
@SpringBootApplication
public class CrowfootCollabApplication {

    public static void main(String[] args) {
        SpringApplication.run(CrowfootCollabApplication.class, args);
    }
}
