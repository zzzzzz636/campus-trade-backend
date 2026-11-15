package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_agent_browser;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.ai.enabled=true", "campus.ai.api-key=fake-test-key", "campus.ai.model=test-model"
})
class AiAgentBrowserTests {
    private static final HttpServer server = startServer();

    @DynamicPropertySource
    static void aiUrl(DynamicPropertyRegistry registry) {
        registry.add("campus.ai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll static void stop() { server.stop(0); }

    @LocalServerPort int port;
    @Autowired VerificationService verification;
    @Autowired RegistrationService registration;
    @Autowired LocalConsoleMailSender mail;
    @Autowired AuthService auth;
    @Autowired ProductService products;

    @Test
    @Timeout(60)
    void assistantShowsRealProductAndOpensDetailOnDesktopAndMobile() {
        String email = UUID.randomUUID() + "@example.invalid";
        verification.send(email);
        long userId = registration.register(new RegisterRequest(email, "password123", "助手浏览器测试",
                mail.latestCodeForTest(email))).id();
        products.create(new CreateProductRequest(1L, "测试机械键盘", "键帽完整，校内面交",
                new BigDecimal("90.00"), ConditionLevel.GOOD), userId);
        String token = auth.login(new LoginRequest(email, "password123")).accessToken();
        Path edge = Path.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(edge).setHeadless(true));
            try {
                for (int width : new int[]{1280, 390}) {
                    Page page = browser.newPage();
                    page.setViewportSize(width, 844);
                    page.navigate("http://127.0.0.1:" + port + "/", new Page.NavigateOptions()
                            .setWaitUntil(WaitUntilState.NETWORKIDLE));
                    page.evaluate("token => sessionStorage.setItem('campusTradeToken', token)", token);
                    page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                    page.locator("#open-ai-agent").click();
                    page.locator("#ai-agent-input").fill("找 100 元以内的机械键盘");
                    page.locator("#ai-agent-send").click();
                    assertThat(page.locator(".agent-product")).containsText("测试机械键盘");
                    boolean noOverflow = (boolean) page.evaluate("() => document.documentElement.scrollWidth <= innerWidth + 1");
                    assertTrue(noOverflow, "AI 助手不应导致页面横向溢出，宽度=" + width);
                    page.locator(".agent-product").click();
                    assertThat(page.locator("#detail-title")).containsText("测试机械键盘");
                    page.close();
                }
            } finally { browser.close(); }
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            created.createContext("/responses", AiAgentBrowserTests::respond);
            created.start();
            return created;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String body = request.contains("function_call_output")
                ? "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"找到一件在售商品，请查看真实商品卡片。\"}]}]}"
                : "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"searchProducts\",\"call_id\":\"call_1\",\"arguments\":\"{\\\"query\\\":\\\"机械键盘\\\",\\\"categoryId\\\":null,\\\"minPrice\\\":null,\\\"maxPrice\\\":100}\"}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
