package net.java21.crowfoot.collab.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONNECT accept-language 파싱 단위 검증 (api-design.md §5.7 collab 축소판) —
 * q값 무시 우선순위 순, zh*는 간체 통일, 미지원·빈값은 ko(게이트웨이 정책과 동일).
 */
class WebSocketConfigLanguageTest {

    @Test
    @DisplayName("우선순위 첫 지원 언어를 고른다 — q값 무시")
    void picksFirstSupportedLanguage() {
        assertThat(WebSocketConfig.languageOf("ko-KR,ko;q=0.9")).isEqualTo("ko");
        assertThat(WebSocketConfig.languageOf("ja,en;q=0.5")).isEqualTo("ja");
        assertThat(WebSocketConfig.languageOf("fr,ja;q=0.8")).isEqualTo("ja");
        assertThat(WebSocketConfig.languageOf("en-US")).isEqualTo("en");
    }

    @Test
    @DisplayName("zh* 태그는 간체(zh)로 통일한다 — zh-CN·zh-Hant·ZH 전부")
    void unifiesChineseVariantsToSimplified() {
        assertThat(WebSocketConfig.languageOf("zh-CN,zh;q=0.9,en;q=0.8")).isEqualTo("zh");
        assertThat(WebSocketConfig.languageOf("zh-Hant")).isEqualTo("zh");
        assertThat(WebSocketConfig.languageOf("ZH")).isEqualTo("zh");
    }

    @Test
    @DisplayName("빈값·미지원 언어는 ko로 폴백한다")
    void fallsBackToKorean() {
        assertThat(WebSocketConfig.languageOf(null)).isEqualTo("ko");
        assertThat(WebSocketConfig.languageOf("")).isEqualTo("ko");
        assertThat(WebSocketConfig.languageOf("   ")).isEqualTo("ko");
        assertThat(WebSocketConfig.languageOf("fr-CH,de;q=0.9")).isEqualTo("ko");
    }
}
