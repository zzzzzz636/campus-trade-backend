# CampusTrade V6 架构审计（2026-11-02）

审计基线：`feature/v6-production-readiness` 的 `61118c3`，即 V5 的 `744b973` 加 V5.1 私信修复。`main` 尚未包含 V5/V5.1。用户原有 `README.md` 未提交修改独立保留。仓库是 Spring Boot 4.1.2、Java 17 编译目标、Maven、MyBatis 的模块化单体。

## 1. 已实现且有自动化验证

| 领域 | 现状与证据 |
|---|---|
| 认证与隔离 | JWT Resource Server、BCrypt、每次解码检查账号状态；`OrderV2IntegrationTests` 与 `V4MultiUserSecurityIntegrationTests` 验证身份与越权。 |
| 商品 | Controller → Service → Mapper，图片使用 `FileStorage` 封装；商品查询/发布/图片有 H2 集成测试。 |
| 订单 | `OrderService` 的申请、接受、拒绝、取消、发货、完成、到期任务有事务与状态条件更新；已有 H2 及历史 MySQL 8.4 并发测试。 |
| 私信 V5.1 | MySQL 表、参与者校验、`beforeId` / `afterId`、未读与 2.5 秒前端轮询；双账号 Playwright 在四种宽度验收。见 `docs/V5_1_TEST_REPORT.md`。 |
| 收藏 | `product_favorite` 组合主键、幂等与隔离测试。 |
| 验证码 | local 与 Redis 两种存储；Redis Lua 做消费原子校验，邮件发送按 profile 切换。 |
| 迁移 | MySQL Flyway V1–V3，包括订单、私信、收藏、通知；旧 V2 库使用 baseline 2。 |

## 2. 有代码但尚未完成真实联调

| 领域 | 实际边界 |
|---|---|
| AI | `OpenAiProductDraftClient` 包含 Responses API 请求、JSON Schema、超时与降级提示；测试使用模拟 HTTP。未提供真实 API Key，因此没有证明真实模型调用成功。 |
| Redis / SMTP | Redis 验证码和 SMTP 适配器有代码；本轮 Docker Desktop 无法启动，真实 Redis/SMTP 联调没有重新验证。 |
| MySQL V5.1 `afterId` | H2 集成测试通过；新增 MySQL Testcontainers 用例因 Docker 引擎不可用而跳过。 |
| 部署 | 有 `docker-compose.dev.yml`、环境变量示例与 Flyway；未验证生产镜像、外部上传卷、备份恢复演练。 |

## 3. 明确缺陷与本版优先级

1. `OrderService.accept` 只检查状态，尚未检查 `expires_at`，定时任务尚未处理的过期订单仍可被卖家接受。优先修复，数据库条件更新也必须限定截止时间。
2. `OrderMapper` 的买入、卖出、全部订单都固定 `LIMIT 50`，个人中心无法看到更早订单；增加分页、状态筛选和稳定排序。
3. `app_notification` 仅有插入 Mapper，缺少读取、已读、权限隔离和前端通知中心。
4. 协商取消没有数据库状态、事务或 UI。
5. 用户仅能注册/登录/查看自己，没有昵称编辑、密码修改和忘记密码流程。
6. 私信和 AI 限流使用进程内 Map，跨实例计数不共享；正式多实例部署需 Redis 原子窗口及故障策略。
7. `orders-v5.js#transition` 在订单 POST 成功后把后续刷新错误仍报为“操作失败”，与 V5.1 修正过的私信问题同类。
8. `app.js`、`styles.css` 较集中；仅在具体改动能降低耦合时渐进拆分。`loadProfile` 一次加载所有数据，分页落地时需要逐列表请求。

## 4. 可延后优化

- 商品搜索目前使用 MySQL 条件查询与现有索引；缺真实数据量和 EXPLAIN 证据前不新增搜索引擎或盲目加索引。
- Offset 分页足以覆盖校园平台的常见个人订单列表；深页或大规模后再考虑游标。
- 私信 2.5 秒轮询适合当前规模；没有实时性指标支持 WebSocket / 消息队列。
- 不缓存所有商品；读写频繁且一致性要求高的订单继续以数据库为准。

## 5. 模块依赖和关键调用链

`Controller` 从 JWT subject 获取当前用户 → `Service` 校验角色/状态并开启事务 → `Mapper` 执行条件 SQL → MySQL/Flyway 表。订单事件与通知在同一业务事务写入；异常时随订单状态一起回滚。前端 `app.js` 负责页面导航，`orders-v5.js`、`messaging.js`、`profile.js` 各自调用 `api.js`。`api.js` 负责 Authorization、请求 ID、统一 401 提示。

## 6. 验证环境说明

此前 V5 完整测试报告为 42 个测试、4 个 MySQL 用例单独在 Docker/MySQL 通过。V5.1 完整测试为 43 个、0 失败、6 跳过；其中新增 MySQL 私信用例未执行。V6 每阶段结果以 `docs/V6_TEST_REPORT.md` 为准，不沿用旧结果冒充本版验证。
