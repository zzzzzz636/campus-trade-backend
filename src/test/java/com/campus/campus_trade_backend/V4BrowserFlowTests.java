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
import com.microsoft.playwright.options.BoundingBox;
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
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_v4_browser;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.storage.upload-dir=${java.io.tmpdir}/campus-trade-v4-browser-images",
        "campus.messaging.max-per-minute=100"
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
    void twoBrowserUsersApplyMessageFavoriteAcceptCompleteRefreshAndLogout() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String title = "V4 浏览器教材 " + marker;
        Page seller = sellerContext.newPage();
        Page buyer = buyerContext.newPage();

        seller.navigate(baseUrl(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
        captureUi(seller, "auth");

        register(seller, "seller-" + marker + "@example.invalid", "浏览器卖家 A");
        long productId = publish(seller, title);
        captureUi(seller, "plaza");
        assertEquals(0, seller.locator("#products .sample").count(), "商品广场不应混入演示卡片");
        seller.locator(".quick-prices button[data-max='50']").click();
        assertThat(seller.locator("#products .product-card").filter(new Locator.FilterOptions().setHasText(title))).isVisible();
        seller.locator("#clear-search").click();
        seller.locator("#search-input").fill("不存在的商品-" + marker);
        seller.locator("#search-form button[type=submit]").click();
        assertThat(seller.locator("#products .marketplace-empty")).containsText("没有找到符合条件的商品");
        assertEquals(0, seller.locator("#products .product-card").count());
        seller.locator("#clear-search").click();

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
        captureUi(seller, "profile");

        seller.reload();
        assertThat(seller.locator("#app-view")).isVisible();
        assertThat(seller.locator("#auth-view")).isHidden();
        assertThat(seller.locator("#header-avatar")).hasText("浏");

        register(buyer, "buyer-" + marker + "@example.invalid", "浏览器买家 B");
        searchAndOpen(buyer, title);
        captureUi(buyer, "detail");
        assertThat(buyer.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("提交购买申请"))).isVisible();
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

        buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("♡ 收藏商品")).click();
        assertThat(buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("♥ 已收藏"))).isVisible();

        buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("联系卖家")).click();
        assertThat(buyer.locator("#chat-overlay")).isVisible();
        assertThat(buyer.locator("#chat-title")).hasText("浏览器卖家 A");
        assertThat(buyer.locator("#chat-context-title")).hasText(title);
        captureUi(buyer, "chat");
        Locator buyerComposer = buyer.locator("#message-content");
        buyerComposer.dispatchEvent("compositionstart");
        buyerComposer.fill("中文输入组合中");
        buyerComposer.press("Enter");
        assertTrue(buyerComposer.inputValue().contains("中文输入组合中"), "中文输入法组合期间不得误发送");
        buyerComposer.dispatchEvent("compositionend");
        buyerComposer.fill("第一行");
        buyerComposer.press("Shift+Enter");
        buyerComposer.type("第二行");
        assertTrue(buyerComposer.inputValue().contains("\n"), "Shift+Enter 应插入换行");
        AtomicInteger buyerMessagePolls = new AtomicInteger();
        buyer.onRequest(request -> {
            if (request.method().equals("GET") && request.url().contains("/api/conversations/")
                    && request.url().contains("/messages")) buyerMessagePolls.incrementAndGet();
        });
        Response message = buyer.waitForResponse(response -> response.url().contains("/api/conversations/")
                        && response.url().endsWith("/messages") && response.request().method().equals("POST"),
                () -> {
                    buyer.locator("#message-form textarea").fill("请问明天下午可以面交吗？");
                    buyer.locator("#message-form textarea").press("Enter");
                });
        assertEquals(201, message.status());
        long conversationId = Long.parseLong(message.url().replaceAll(".*/conversations/(\\d+)/messages.*", "$1"));
        assertThat(buyer.locator("#message-send-status")).containsText("已发送");
        verifyChatLayouts(buyer);

        seller.locator("#nav-profile").click();
        seller.locator("#tab-notifications").click();
        assertThat(seller.locator("#notification-count")).hasText("0");
        seller.locator("#tab-messages").click();
        assertThat(seller.locator("#message-count")).hasText("1");
        Locator sellerConversation = seller.locator("#messages-pane .conversation-row")
                .filter(new Locator.FilterOptions().setHasText(title));
        assertThat(sellerConversation).containsText("请问明天下午可以面交吗？");
        sellerConversation.click();
        Response reply = seller.waitForResponse(response -> response.url().endsWith("/messages")
                        && response.request().method().equals("POST"),
                () -> {
                    seller.locator("#message-content").fill("可以，下午三点见。");
                    seller.locator("#message-content").press("Enter");
                });
        assertEquals(201, reply.status());
        assertThat(buyer.locator("#chat-messages")).containsText("可以，下午三点见。");
        captureUi(buyer, "chat-filled");
        seller.locator("#close-chat").click();

        String conversationListPattern = "**/api/conversations?page=1&size=50";
        AtomicBoolean failListRefresh = new AtomicBoolean(true);
        buyer.route(conversationListPattern, route -> {
            if (failListRefresh.compareAndSet(true, false)) route.abort();
            else route.resume();
        });
        Response sentButRefreshFailed = buyer.waitForResponse(response -> response.url().endsWith("/messages")
                        && response.request().method().equals("POST"),
                () -> {
                    buyerComposer.fill("消息成功但列表刷新失败测试");
                    buyerComposer.press("Enter");
                });
        assertEquals(201, sentButRefreshFailed.status());
        assertThat(buyer.locator("#message-send-status")).containsText("消息已发送");
        buyer.unroute(conversationListPattern);

        String messagePattern = "**/api/conversations/*/messages";
        AtomicBoolean failSend = new AtomicBoolean(true);
        buyer.route(messagePattern, route -> {
            if (route.request().method().equals("POST") && failSend.compareAndSet(true, false)) route.abort();
            else route.resume();
        });
        buyerComposer.fill("发送失败后应保留的内容");
        buyerComposer.press("Enter");
        assertThat(buyer.locator("#message-send-status")).containsText("发送失败");
        assertEquals("发送失败后应保留的内容", buyerComposer.inputValue());
        buyer.unroute(messagePattern);
        buyerComposer.fill("");

        String sellerToken = (String) seller.evaluate("sessionStorage.getItem('campusTradeToken')");
        assertNotNull(sellerToken);
        for (int index = 1; index <= 32; index++) {
            APIResponse seeded = sellerContext.request().post(baseUrl() + "/api/conversations/" + conversationId + "/messages",
                    RequestOptions.create().setHeader("Authorization", "Bearer " + sellerToken)
                            .setData(Map.of("content", "历史消息 " + index)));
            assertEquals(201, seeded.status());
            seeded.dispose();
        }

        buyer.locator("#close-chat").click();
        int pollCountAfterClose = buyerMessagePolls.get();
        buyer.waitForTimeout(3000);
        assertEquals(pollCountAfterClose, buyerMessagePolls.get(), "关闭聊天后不应继续轮询");
        buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("联系卖家")).click();
        assertThat(buyer.locator("#load-older-messages")).isVisible();
        String readingAnchorId = (String) buyer.locator("#chat-messages").evaluate("""
                host => [...host.querySelectorAll('.message-bubble')]
                  .find(node => node.getBoundingClientRect().bottom > host.getBoundingClientRect().top + 1)?.dataset.messageId
                """);
        assertNotNull(readingAnchorId);
        Locator readingAnchor = buyer.locator("[data-message-id='" + readingAnchorId + "']");
        Number oldAnchorTop = (Number) readingAnchor.evaluate("node => node.getBoundingClientRect().top");
        Response older = buyer.waitForResponse(response -> response.url().contains("beforeId=")
                        && response.request().method().equals("GET"),
                () -> buyer.locator("#load-older-messages").click());
        assertEquals(200, older.status());
        Number newAnchorTop = (Number) readingAnchor.evaluate("node => node.getBoundingClientRect().top");
        double preservedOffsetError = Math.abs(newAnchorTop.doubleValue() - oldAnchorTop.doubleValue());
        assertTrue(preservedOffsetError <= 2.0, "加载历史消息后阅读位置不应跳动");
        buyer.locator("#chat-context").click();
        assertThat(buyer.locator("#chat-overlay")).isHidden();
        assertThat(buyer.locator("#detail-overlay")).isVisible();

        buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交购买申请")).click();
        assertThat(buyer.locator("#purchase-dialog")).isVisible();
        buyer.locator("#purchase-form [name=handoffLocation]").fill("东区食堂门口");
        buyer.locator("#purchase-form [name=buyerNote]").fill("希望明天下午交易");
        Response createOrder = buyer.waitForResponse(response ->
                        response.url().endsWith("/api/orders") && response.request().method().equals("POST"),
                () -> buyer.locator("#purchase-form button[type=submit]").click());
        assertEquals(201, createOrder.status());
        long orderId = objectMapper.readTree(createOrder.text()).path("id").asLong();
        assertThat(buyer.locator("#order-overlay")).isVisible();
        assertThat(buyer.locator("#order-detail-content")).containsText("等待卖家确认");
        captureUi(buyer, "order");
        buyer.locator("#close-order-detail").click();

        buyer.locator("#nav-profile").click();
        assertThat(buyer.locator("#purchases-pane").getByText(title)).isVisible();
        assertThat(buyer.locator("#purchases-pane").getByText("订单 #" + orderId, new Locator.GetByTextOptions().setExact(false))).isVisible();
        captureUi(buyer, "purchases");

        seller.reload();
        assertThat(seller.locator("#app-view")).isVisible();
        seller.locator("#nav-profile").click();
        seller.locator("#tab-notifications").click();
        assertThat(seller.locator("#notification-count")).hasText("1");
        captureUi(seller, "notifications");
        seller.locator("#notifications-pane .notification-row").first().click();
        assertThat(seller.locator("#order-overlay")).isVisible();
        assertThat(seller.locator("#notification-count")).hasText("0");
        seller.locator("#close-order-detail").click();
        seller.locator("#tab-sales").click();
        assertThat(seller.locator("#sales-pane").getByText(title)).isVisible();
        captureUi(seller, "sales");
        seller.locator("#tab-messages").click();
        assertThat(seller.locator("#messages-pane")).containsText("历史消息 32");
        captureUi(seller, "messages");
        seller.locator("#tab-sales").click();
        seller.locator("#sales-pane .order-row").filter(new Locator.FilterOptions().setHasText(title)).click();
        assertThat(seller.locator("#order-detail-content")).containsText("等待卖家确认");
        seller.onDialog(dialog -> dialog.accept());
        Response accept = seller.waitForResponse(response -> response.url().endsWith("/api/orders/" + orderId + "/accept"),
                () -> seller.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("接受购买申请")).click());
        assertEquals(200, accept.status());
        assertThat(seller.locator("#order-detail-content")).containsText("等待面交");

        buyer.reload();
        buyer.locator("#nav-profile").click();
        buyer.locator("#purchases-pane .order-row").filter(new Locator.FilterOptions().setHasText(title)).click();
        buyer.onceDialog(dialog -> dialog.accept());
        Response complete = buyer.waitForResponse(response ->
                        response.url().endsWith("/api/orders/" + orderId + "/complete"),
                () -> buyer.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("确认已面交并完成交易")).click());
        assertEquals(200, complete.status());
        assertThat(buyer.locator("#order-detail-content")).containsText("已完成");
        buyer.locator("#close-order-detail").click();
        buyer.locator("#tab-favorites").click();
        assertThat(buyer.locator("#favorites-pane").filter(new Locator.FilterOptions().setHasText(title))).containsText("已售出");
        captureUi(buyer, "favorites");

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

    @Test
    @Timeout(90)
    void negotiatedCancellationAndAccountSettingsWorkInBrowser() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String title = "V6 协商教材 " + marker;
        Page seller = sellerContext.newPage();
        Page buyer = buyerContext.newPage();
        register(seller, "v6-seller-" + marker + "@example.invalid", "V6 卖家");
        publish(seller, title);
        register(buyer, "v6-buyer-" + marker + "@example.invalid", "V6 买家");
        searchAndOpen(buyer, title);
        buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("提交购买申请")).click();
        Response created = buyer.waitForResponse(response -> response.url().endsWith("/api/orders")
                        && response.request().method().equals("POST"),
                () -> buyer.locator("#purchase-form button[type=submit]").click());
        assertEquals(201, created.status());
        long orderId = objectMapper.readTree(created.text()).path("id").asLong();

        seller.reload(); seller.locator("#nav-profile").click(); seller.locator("#tab-sales").click();
        seller.locator("#sales-pane .order-row").filter(new Locator.FilterOptions().setHasText(title)).click();
        seller.onDialog(dialog -> dialog.accept());
        Response accepted = seller.waitForResponse(response -> response.url().endsWith("/api/orders/" + orderId + "/accept"),
                () -> seller.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("接受购买申请")).click());
        assertEquals(200, accepted.status());

        buyer.reload(); buyer.locator("#nav-profile").click();
        buyer.locator("#purchases-pane .order-row").filter(new Locator.FilterOptions().setHasText(title)).click();
        buyer.onceDialog(dialog -> dialog.accept("时间冲突"));
        Response proposed = buyer.waitForResponse(response -> response.url().endsWith("/cancel-request")
                        && response.request().method().equals("POST"),
                () -> buyer.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("申请协商取消")).click());
        assertEquals(201, proposed.status());
        assertThat(buyer.locator("#order-detail-content")).containsText("等待对方处理");

        seller.reload(); seller.locator("#nav-profile").click(); seller.locator("#tab-sales").click();
        seller.locator("#sales-pane .order-row").filter(new Locator.FilterOptions().setHasText(title)).click();
        assertThat(seller.locator("#order-detail-content")).containsText("时间冲突");
        Response resolved = seller.waitForResponse(response -> response.url().endsWith("/cancel-request/accept"),
                () -> seller.getByRole(AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName("同意取消并恢复商品在售")).click());
        assertEquals(200, resolved.status());
        assertThat(seller.locator("#order-detail-content")).containsText("已取消");
        seller.locator("#close-order-detail").click();
        seller.locator("#tab-listings").click();
        assertThat(listing(seller, title)).containsText("在售");

        seller.locator("#tab-account").click();
        seller.locator("#nickname-form [name=nickname]").fill("V6 新昵称");
        seller.locator("#nickname-form button[type=submit]").click();
        assertThat(seller.locator("#profile-name")).hasText("V6 新昵称");
        seller.locator("#password-form [name=currentPassword]").fill("password123");
        seller.locator("#password-form [name=newPassword]").fill("newpassword123");
        seller.locator("#password-form button[type=submit]").click();
        assertThat(seller.locator("#auth-view")).isVisible();
        assertNull(seller.evaluate("sessionStorage.getItem('campusTradeToken')"));
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
        captureUi(page, "publish");
        page.locator("#publish-preview").click();
        assertThat(page.locator("#publish-preview-content")).containsText(title);
        assertThat(page.locator("#publish-preview-content")).containsText("¥22.50");
        page.locator("#close-publish-preview").click();
        Response created = page.waitForResponse(response ->
                        response.url().endsWith("/api/products") && response.request().method().equals("POST"),
                () -> page.locator("#publish-submit").click());
        assertEquals(201, created.status());
        long productId = objectMapper.readTree(created.text()).path("id").asLong();
        assertThat(page.locator("#home-view")).isVisible();
        assertThat(page.locator("#products")).containsText(title);
        return productId;
    }

    private void captureUi(Page page, String name) {
        String phase = System.getProperty("campus.ui.capture");
        if (phase == null || !phase.matches("before|after|final")) return;
        String only = System.getProperty("campus.ui.only");
        if (only != null && !java.util.Arrays.asList(only.split(",")).contains(name)) return;
        Path directory = Path.of("docs", "ui-screenshots", phase);
        try { Files.createDirectories(directory); }
        catch (Exception error) { throw new IllegalStateException("无法创建 UI 截图目录", error); }
        int[][] viewports = "before".equals(phase)
                ? new int[][]{{390, 844}, {1280, 800}}
                : new int[][]{{390, 844}, {768, 1024}, {1280, 800}, {1440, 900}};
        for (int[] viewport : viewports) {
            page.setViewportSize(viewport[0], viewport[1]);
            if (!"before".equals(phase)) page.locator("#toast").evaluate("node => node.hidden = true");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(directory.resolve(name + "-" + viewport[0] + ".png"))
                    .setFullPage(!name.startsWith("chat") && !"detail".equals(name) && !"order".equals(name)));
            if (!"before".equals(phase)) {
                Number overflow = (Number) page.evaluate("Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth");
                assertTrue(overflow.doubleValue() <= 1, name + " 在 " + viewport[0] + "px 有横向溢出 " + overflow);
                if ("plaza".equals(name)) {
                    Number cardAspect = (Number) page.locator("#products .product-card").first().evaluate(
                            "node => node.getBoundingClientRect().height / node.getBoundingClientRect().width");
                    assertTrue(cardAspect.doubleValue() < 2.1, "商品卡片不应被异常拉伸");
                    assertThat(page.locator("#products .product-cover .photo-empty").first()).isVisible();
                }
                if (name.startsWith("chat")) {
                    Number messagesHeight = (Number) page.locator("#chat-messages").evaluate("node => node.getBoundingClientRect().height");
                    Number composerHeight = (Number) page.locator("#message-form").evaluate("node => node.getBoundingClientRect().height");
                    Number sendWidth = (Number) page.locator("#send-message").evaluate("node => node.getBoundingClientRect().width");
                    assertTrue(messagesHeight.doubleValue() > viewport[1] * .35, "私信消息区域高度不足");
                    assertTrue(composerHeight.doubleValue() < viewport[1] * .3, "私信输入区异常拉伸");
                    assertTrue(sendWidth.doubleValue() < 140, "私信发送按钮异常拉伸");
                }
            }
        }
        page.setViewportSize(1280, 800);
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

    private void verifyChatLayouts(Page page) {
        Path screenshots = Path.of("target", "v5.1-screenshots");
        screenshots.toFile().mkdirs();
        int[][] viewports = {{390, 844}, {428, 926}, {768, 1024}, {1280, 720}};
        for (int[] viewport : viewports) {
            page.setViewportSize(viewport[0], viewport[1]);
            BoundingBox drawer = page.locator(".chat-drawer").boundingBox();
            BoundingBox messages = page.locator("#chat-messages").boundingBox();
            BoundingBox form = page.locator("#message-form").boundingBox();
            BoundingBox send = page.locator("#send-message").boundingBox();
            assertNotNull(drawer);
            assertNotNull(messages);
            assertNotNull(form);
            assertNotNull(send);
            assertTrue(messages.height > viewport[1] * 0.38, "聊天记录应占据主要空间");
            assertTrue(form.y + form.height >= viewport[1] - 2, "输入区应固定在抽屉底部");
            assertTrue(send.height >= 36 && send.height <= 56, "发送按钮高度不得被拉伸");
            assertTrue(send.width >= 60 && send.width <= 130, "发送按钮宽度应保持可点击但不过宽");
            assertTrue(drawer.height <= viewport[1] + 1, "聊天抽屉不得垂直溢出");
            Boolean noHorizontalOverflow = (Boolean) page.evaluate("""
                    () => document.documentElement.scrollWidth <= window.innerWidth
                      && document.querySelector('.chat-drawer').scrollWidth <= document.querySelector('.chat-drawer').clientWidth
                    """);
            assertTrue(noHorizontalOverflow, "聊天抽屉不得水平溢出");
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(screenshots.resolve("chat-after-" + viewport[0] + ".png")));
        }
        page.setViewportSize(1280, 720);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }
}
