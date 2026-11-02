# CampusTrade V5.1 私信模块测试报告

执行日期：2026-11-02。环境：Windows、Java 25 运行 Maven（项目编译目标 Java 17）、Spring Boot 4.1.2、本机 Microsoft Edge、H2 MySQL 兼容模式。

## 定向测试

```powershell
node --check src/main/resources/static/js/messaging.js
node --check src/main/resources/static/app.js
.\mvnw.cmd '-Dtest=MessagingIntegrationTests' test
.\mvnw.cmd '-Dtest=V4BrowserFlowTests' test
```

实际结果：

- JavaScript 语法检查：通过。
- `MessagingIntegrationTests`：2/2 通过。
- `V4BrowserFlowTests`：1/1 通过。

## 完整回归

```powershell
.\mvnw.cmd test
```

最终结果：`Tests run: 43, Failures: 0, Errors: 0, Skipped: 6`，`BUILD SUCCESS`，总耗时 32.638 秒。其中 5 个跳过项是需要显式开关和 Docker 的 MySQL 专项用例，1 个是 Docker 邮件/Redis 环境用例。V5.1 修改后的业务、权限、订单、收藏、图片、AI、注册和浏览器测试均无失败。

新增真实 MySQL 私信游标用例后执行：

```powershell
.\mvnw.cmd '-Dcampus.mysql.tests=true' '-Dtest=MySqlOrderConcurrencyIntegrationTests' test
```

本次运行结果为 `Tests run: 5, Skipped: 5`。Testcontainers 明确报告 Docker 引擎不可用；Docker Desktop 随后又因本机 `docker-secrets-engine/engine.sock` 被占用而启动失败。因此这些 MySQL 用例没有在本轮被虚构为通过。V5 之前已有 MySQL 8.4 结果仍保留在 `docs/V5_TEST_REPORT.md`，但新增 `afterId` SQL 的本轮验证环境是 H2 MySQL 兼容模式。待 Docker 恢复后应重新执行上面的命令。

## 后端验证内容

- 原 `beforeId` 历史分页继续工作。
- 新 `afterId` 增量查询按消息 ID 升序返回。
- `beforeId` 和 `afterId` 同时传入返回 400。
- 第三方用户仍不能读取会话。
- 消息仍写入原 `private_message` 表，没有新增迁移或改变订单、收藏表。

## 双账号浏览器验收

两个隔离的 Edge BrowserContext 分别代表卖家 A 和买家 B，实际完成：

1. A、B 分别注册登录，A 发布商品。
2. B 从商品详情打开聊天，顶部正确显示 A 的昵称和关联商品。
3. 中文输入法组合期间按 Enter 不发送；Shift+Enter 正常换行；Enter 正常发送。
4. A 在个人中心看到未读数和 B 的消息，打开会话并回复。
5. B 不刷新页面，在轮询周期内自动看到 A 的回复。
6. 模拟“发送成功但会话列表刷新失败”，页面明确显示消息已发送。
7. 模拟发送请求失败，页面显示发送失败且保留原输入内容。
8. 写入 32 条历史消息，关闭再打开后出现“加载更早消息”；加载后当前可见消息的屏幕位置误差不超过 2px。
9. 关闭聊天后等待 3 秒，消息 GET 请求计数不再增加。
10. 点击聊天顶部关联商品后正确打开商品详情。
11. 随后的购买申请、卖家接受、买家完成、收藏及退出登录回归继续通过。

## 视觉布局断言

Playwright 在 390×844、428×926、768×1024、1280×720 四种视口中检查：

- 消息区高度大于视口高度的 38%。
- 输入表单底边贴合聊天抽屉底部。
- 发送按钮高度在 36–56px、宽度在 60–130px。
- 抽屉没有水平或垂直溢出。
- 每种宽度均输出实际截图到 `target/v5.1-screenshots/`。

## 实际发现并修复的问题

首次浏览器测试发现：使用 `scrollHeight` 差值保持历史消息位置时，最后一页加载后按钮隐藏会改变消息视口几何尺寸，断言失败。实现改为“可见消息锚点”后重新执行测试通过。这一失败记录说明滚动修复经过了真实浏览器验证，而不是只验证接口状态。

## 已知边界

- 轮询间隔固定为 2.5 秒，属于可靠的近实时体验，不是 WebSocket 即时推送。
- 页面在后台时暂停轮询，恢复可见后补拉；浏览器或网络长时间离线时仍依靠下一次 HTTP 请求恢复。
- 本轮没有改动数据库结构，因此无需 Flyway 迁移。
- MySQL 消息表、参与者权限、消息限流及订单关联沿用 V5 设计。
