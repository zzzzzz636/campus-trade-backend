# 生产工程升级：可复现状态（2026-11-09）

## 已落地的交易与消息路径

- 创建订单先锁商品行，再用 `SELECT ... FOR UPDATE` 当前读检查请求键；同商品预订与订单写入在一个事务中。唯一索引 `uq_order_buyer_request` 保留为最后约束。
- 开启 `CAMPUS_RABBIT_ENABLED=true` 后，新购买申请不直接写卖家通知，而是在**同一订单事务**写 `order_notification_outbox`。消费者收到事件后，以 `order_notification_consumed.event_id` 主键去重，再写 `app_notification`；两步处于同一数据库事务。关闭 Rabbit 时沿用原同步通知路径。
- 发布器用条件更新领取待发事件，等待 publisher confirm 并检查 mandatory return 后标记 `PUBLISHED`。失败后退避重试，五次失败进入数据库 `DEAD`；处理中进程崩溃时，租约到期后重新领取。消费者最多重试三次，失败消息进入 Rabbit 死信队列。消息可能重复投递，站内通知由数据库去重。
- 关键代码：`OrderService.notifyUser`、`OrderOutboxMapper`、`OrderOutboxPublisher`、`OrderNotificationConsumer`、`OrderRabbitConfig`；DDL：`V6__order_notification_outbox.sql`。

## 本地部署与核查

1. 复制 `.env.example` 为未跟踪的 `.env`，填写数据库密码、JWT 密钥和 `CAMPUS_RABBIT_PASSWORD`，不要提交该文件。
2. Docker Engine 可用后运行 `docker compose -f docker-compose.dev.yml --profile app up --build -d`。MySQL、Redis、RabbitMQ 和 Mailpit 由 Compose 启动；应用的 `mysql` profile 运行 Flyway V1–V6。
3. 用两个账号完成发布、申请购买；核对卖家通知、`order_notification_outbox.status='PUBLISHED'`，重复投递后通知仍只有一条。Rabbit 管理界面只绑定 `127.0.0.1:15672`。
4. 失败事件需检查 `status='DEAD'`、`attempts` 和 Rabbit 死信队列。确认根因已修复后，运维人员可在测试或维护窗口将该行改为 `RETRY` 并将 `next_attempt_at` 设为当前时间；不要直接删除事件。

## 性能与验证边界

- 全套 Java 17 测试、H2 outbox 原子性与重复消费、模拟发布失败已运行。MySQL 与真实 RabbitMQ 容器测试因本机 Docker Engine 不可连接而跳过；未完成真实消息链路和全新环境部署验收。
- `perf/k6-trading-read-baseline.js` 是只读商品搜索与购买订单列表脚本。准备隔离 MySQL 测试库、记录三张表的行数和代表性用户后，传入 `BASE_URL`、`BUYER_TOKEN`，运行 `k6 run --summary-export=baseline.json perf/k6-trading-read-baseline.js`。同时保存 `EXPLAIN ANALYZE`、并发数、吞吐量、p95 和错误率。**本机没有 k6 或 MySQL 测量结果，尚无优化前后数据。**

## 已知限制

- 默认关闭 Rabbit，避免原有 H2 开发环境依赖消息代理。Compose 应用显式开启。
- outbox 是至少一次投递；死信和数据库 `DEAD` 均需要人工告警与重放流程。当前没有自动告警或管理后台。
- 本地文件图片存储仍是单机方案；Compose 通过卷持久化，尚未验证备份恢复。
