# CampusTrade V5 测试报告

执行日期：2026-10-31。环境：Windows、Java 25 运行 Maven（项目编译目标 Java 17）、Spring Boot 4.1.2、Docker Desktop、MySQL 8.4 Testcontainers、本机 Microsoft Edge。

## 实际执行结果

### 完整回归

```powershell
.\mvnw.cmd test
```

结果：`Tests run: 42, Failures: 0, Errors: 0, Skipped: 4`，`BUILD SUCCESS`，总耗时 45.106 秒。

4 个跳过用例来自 `MySqlOrderConcurrencyIntegrationTests`，因为完整回归没有传入显式开关 `-Dcampus.mysql.tests=true`。它们随后在独立 MySQL 命令中实际执行并通过，不属于“未验证”。

完整回归包含：

- 注册验证码、登录、无效及过期 JWT。
- 商品发布、编辑、图片、搜索和多用户所有权。
- AI 结构化输出与限流回归。
- V5 订单面交、快递、非法状态、事务回滚和 H2 并发。
- 私信参与者权限、持久化、分页、未读数。
- 收藏幂等、用户隔离、下架后保留。
- Docker Redis + SMTP 测试服务集成。
- Playwright + Edge 双浏览器上下文端到端流程。

### MySQL 8.4 / InnoDB / Flyway

```powershell
.\mvnw.cmd '-Dcampus.mysql.tests=true' '-Dtest=MySqlOrderConcurrencyIntegrationTests' test
```

结果：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`。

验证内容：

- 空 MySQL 数据库连续执行 Flyway V1、V2、V3 成功。
- V5 订单、私信、收藏索引实际存在。
- 8 个线程并发申请同一商品时只有 1 个订单成功，商品最终为 `RESERVED`。
- 卖家接受与买家取消并发发生时只有 1 个操作成功，最终只可能是 `WAITING_HANDOFF + RESERVED` 或 `CANCELLED + ON_SALE`。
- 重复请求键用于另一商品时，第二件商品仍为 `ON_SALE` 且不会留下订单。

第一次执行该测试时发现 `idx_favorite_user_created` 未写入迁移脚本；测试失败后补齐索引并重新执行通过。这是实际发现和修复的问题，不是跳过断言。

### V5 定向测试

| 命令/测试类 | 结果 |
|---|---|
| `OrderV2IntegrationTests` | 8/8 通过 |
| `MessagingIntegrationTests` | 2/2 通过 |
| `FavoriteIntegrationTests` | 1/1 通过 |
| `V4BrowserFlowTests`（已升级为 V5 流程） | 1/1 通过 |
| `node --check`：`app.js`、`orders-v5.js`、`messaging.js`、`favorites.js`、`profile.js` | 无语法错误 |

## 浏览器验收覆盖

两个隔离的 Edge BrowserContext 分别代表卖家 A 和买家 B：

1. A 注册并发布商品。
2. B 注册、搜索商品并验证无法越权修改。
3. B 收藏商品，向 A 发送持久化私信。
4. B 选择面交并提交购买申请。
5. A 在“我的卖出”看到订单，在“私信”看到消息并接受申请。
6. B 刷新页面后看到 `WAITING_HANDOFF`，确认面交完成。
7. A 的商品显示 `SOLD`；B 的收藏列表仍保留商品并显示“已售出”。
8. B 退出后访问私有接口得到 401，刷新后不会恢复登录态。

## 已知测试边界

- SMTP 正式邮箱凭据没有写入仓库；自动化使用项目已有测试邮件环境或 local sender。
- 当前消息是 HTTP 持久化方案，没有验证 WebSocket 实时推送，因为 V5 未引入 WebSocket。
- 没有支付与真实物流轨迹测试；产品明确不虚构付款或物流状态。
- 消息限流是单实例内存窗口；分布式部署前需迁移到 Redis 原子计数。
