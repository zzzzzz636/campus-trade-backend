package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.RequestOptions;
import com.microsoft.playwright.options.WaitUntilState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.nio.file.Path;
import java.util.UUID;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_v4_browser;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.storage.upload-dir=${java.io.tmpdir}/campus-trade-v4-browser-images"
})
@EnabledOnOs(OS.WINDOWS)
class V4BrowserFlowTests {
    @LocalServerPort int port;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired ObjectMapper objectMapper;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext sellerContext;
    private BrowserContext buyerContext;

    @BeforeEach
    void launchBrowser() {
        // 测试直接复用本机 Edge，避免 Playwright 在每台开发机首次运行时额外下载 Chromium。
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        Path installedEdge = Path.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setExecutablePath(installedEdge)
                .setHeadless(true));
        sellerContext = browser.newContext();
        buyerContext = browser.newContext();
        sellerContext.setDefaultTimeout(10_000);
        buyerContext.setDefaultTimeout(10_000);
    }

    @AfterEach
    void closeBrowser() {
        if (sellerContext != null) sellerContext.close();
        if (buyerContext != null) buyerContext.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @Test
    @Timeout(90)
    void twoBrowserUsersPublishBuyCompleteRefreshAndLogout() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String title = "V4 浏览器教材 " + marker;
        Page seller = sellerContext.newPage();
        Page buyer = buyerContext.newPage();

        register(seller, "seller-" + marker + "@example.invalid", "浏览器卖家 A");
        long productId = publish(seller, title);

        seller.locator("#products .product-card").filter(new Locator.FilterOptions().setHasText(title)).click();
        assertThat(seller.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("编辑商品信息"))).hasCount(0);
        assertThat(seller.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("前往“我发布的”"))).isVisible();
        seller.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("前往“我发布的”")).click();
        Locator sellerListing = listing(seller, title);
        assertThat(sellerListing).isVisible();
        sellerListing.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("编辑商品")).click();
        assertThat(seller.locator("#publish-title")).hasText("编辑商品信息");
        assertThat(seller.locator("#publish-back")).hasText("← 返回我的发布");
        seller.locator("#publish-back").click();
        assertThat(seller.locator("#listings-pane")).isVisible();

        seller.reload();
        assertThat(seller.locator("#app-view")).isVisible();
        assertThat(seller.locator("#auth-view")).isHidden();
        assertThat(seller.locator("#header-avatar")).hasText("浏");

        register(buyer, "buyer-" + marker + "@example.invalid", "浏览器买家 B");
        searchAndOpen(buyer, title);
        assertThat(buyer.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("选择商品，创建线下面交订单"))).isVisible();
        assertThat(buyer.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("编辑商品信息"))).hasCount(0);

        String buyerToken = (String) buyer.evaluate("sessionStorage.getItem('campusTradeToken')");
        assertNotNull(buyerToken);
        APIResponse forbidden = buyerContext.request().put(baseUrl() + "/api/products/" + productId,
                RequestOptions.create()
                        .setHeader("Authorization", "Bearer " + buyerToken)
                        .setData(Map.of("categoryId", 1, "title", "越权修改", "description", "不允许",
                                "price", 1.00, "conditionLevel", "GOOD")));
        assertEquals(403, forbidden.status());
        forbidden.dispose();

        Response createOrder = buyer.waitForResponse(response ->
                        response.url().endsWith("/api/orders") && response.request().method().equals("POST"),
                () -> buyer.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("选择商品，创建线下面交订单")).click());
        assertEquals(201, createOrder.status());
        long orderId = objectMapper.readTree(createOrder.text()).path("id").asLong();

        buyer.locator("#nav-profile").click();
        assertThat(buyer.locator("#purchases-pane").getByText(title)).isVisible();
        assertThat(buyer.locator("#purchases-pane").getByText("订单 #" + orderId, new Locator.GetByTextOptions().setExact(false))).isVisible();

        seller.reload();
        assertThat(seller.locator("#app-view")).isVisible();
        seller.locator("#nav-profile").click();
        seller.locator("#tab-sales").click();
        assertThat(seller.locator("#sales-pane").getByText(title)).isVisible();

        buyer.onceDialog(dialog -> dialog.accept());
        Response complete = buyer.waitForResponse(response ->
                        response.url().endsWith("/api/orders/" + orderId + "/complete"),
                () -> buyer.locator("#purchases-pane").getByRole(AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("确认成交")).click());
        assertEquals(200, complete.status());
        assertThat(buyer.locator("#purchases-pane")).containsText("已完成");

        seller.reload();
        seller.locator("#nav-profile").click();
        seller.locator("#tab-listings").click();
        assertThat(listing(seller, title)).containsText("已售出");

        buyer.locator("#logout").click();
        assertThat(buyer.locator("#auth-view")).isVisible();
        assertNull(buyer.evaluate("sessionStorage.getItem('campusTradeToken')"));
        Number privateStatus = (Number) buyer.evaluate("async () => (await fetch('/api/products/me')).status");
        assertEquals(401, privateStatus.intValue());
        buyer.reload();
        assertThat(buyer.locator("#auth-view")).isVisible();
    }

    private void register(Page page, String email, String nickname) {
        page.navigate(baseUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
        page.locator("#show-register").click();
        page.locator("#register-form [name=nickname]").fill(nickname);
        page.locator("#register-form [name=email]").fill(email);
        Response sent = page.waitForResponse(response -> response.url().endsWith("/api/auth/verification-codes"),
                () -> page.locator("#send-code").click());
        assertEquals(202, sent.status());
        page.locator("#register-form [name=code]").fill(localMail.latestCodeForTest(email));
        page.locator("#register-form [name=password]").fill("password123");
        page.locator("#register-form button[type=submit]").click();
        assertThat(page.locator("#app-view")).isVisible();
        page.waitForFunction("() => document.querySelector('#result-label').textContent !== '正在加载商品…'");
    }

    private long publish(Page page, String title) {
        page.locator("#nav-publish").click();
        page.locator("#category-select").selectOption("1");
        page.locator("#publish-form [name=conditionLevel]").selectOption("GOOD");
        page.locator("#publish-form [name=title]").fill(title);
        page.locator("#publish-form [name=description]").fill("浏览器自动化发布，校内线下面交。");
        page.locator("#publish-form [name=price]").fill("22.50");
        Response created = page.waitForResponse(response ->
                        response.url().endsWith("/api/products") && response.request().method().equals("POST"),
                () -> page.locator("#publish-submit").click());
        assertEquals(201, created.status());
        long productId = objectMapper.readTree(created.text()).path("id").asLong();
        assertThat(page.locator("#home-view")).isVisible();
        assertThat(page.locator("#products")).containsText(title);
        return productId;
    }

    private void searchAndOpen(Page page, String title) {
        page.locator("#search-input").fill(title);
        page.locator("#search-form button[type=submit]").click();
        Locator card = page.locator("#products .product-card").filter(new Locator.FilterOptions().setHasText(title));
        assertThat(card).isVisible();
        card.click();
    }

    private Locator listing(Page page, String title) {
        return page.locator("#listings-pane .list-row").filter(new Locator.FilterOptions().setHasText(title));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
