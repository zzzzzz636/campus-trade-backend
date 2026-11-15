package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_ai_risk_browser;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.ai.enabled=false"
})
class AiProductRiskBrowserTests {
    @LocalServerPort int port;
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired AuthService authService;

    @Test
    @Timeout(60)
    void publishFormChecksRiskWithoutPublishingAndFitsMobile() {
        Path edge = Path.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(edge).setHeadless(true));
            try {
                Page page = browser.newPage();
                page.navigate("http://127.0.0.1:" + port + "/", new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
                page.evaluate("token => sessionStorage.setItem('campusTradeToken', token)", token());
                page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                page.locator("#nav-publish").click();
                page.locator("#publish-form [name=title]").fill("毕业清理教材");
                page.locator("#publish-form [name=description]").fill("书页完整，东门面交，价格便宜");
                page.locator("#publish-form [name=price]").fill("1.00");
                page.locator("#category-select").selectOption("1");
                page.locator("#product-risk-button").click();
                assertThat(page.locator("#product-risk-result")).isVisible();
                assertThat(page.locator("#product-risk-result")).containsText("模型分析未启用");
                assertThat(page.locator("#product-risk-result")).containsText("低风险提示");

                page.locator("#publish-form [name=description]").fill("先交定金，东门面交");
                assertThat(page.locator("#product-risk-result")).isHidden();
                page.locator("#product-risk-button").click();
                assertThat(page.locator("#product-risk-result")).containsText("建议谨慎核实");
                assertThat(page.locator("#publish-view")).isVisible();

                page.setViewportSize(390, 844);
                assertThat(page.locator("#product-risk-button")).isVisible();
                boolean noHorizontalOverflow = (boolean) page.evaluate(
                        "() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1");
                assertTrue(noHorizontalOverflow, "手机端风险检查不应造成横向溢出");
            } finally {
                browser.close();
            }
        }
    }

    private String token() {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        registrationService.register(new RegisterRequest(email, "password123", "浏览器风险测试",
                localMail.latestCodeForTest(email)));
        return authService.login(new LoginRequest(email, "password123")).accessToken();
    }
}
