package net.java21.crowfoot.collab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 협업 서버 공용 시계(UTC) — 세션 수명·락 TTL·커서 쓰로틀·인증 캐시 만료 계산이 공유한다.
 * WebSocketConfig(AuthGate 주입)와 분리한 건 순환 방지: 클라이언트들이 시계를 먼저
 * 필요로 하기 때문이다. 테스트는 가짜 시계 bean으로 대체한다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
