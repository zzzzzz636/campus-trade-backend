# V6 简历证据与学习清单

以下只记录源码和已执行验证，不能直接把“实现”写成“线上效果”。

| 业务难点 | 实际方案与关键源码 | 验证证据 | 自己需要掌握 |
|---|---|---|---|
| 同一商品并发申请 | `OrderService.create` 锁商品行，`ProductMapper.reserveForOrder` 条件更新；订单和商品同事务 | `OrderV2IntegrationTests.concurrentBuyersCanCreateOnlyOneOrder` 的 H2 并发；MySQL 用例在 Docker 可用后复验 | InnoDB 行锁、事务隔离、条件更新、唯一约束、幂等请求键 |
| 卖家抢在定时任务前接受过期申请 | `OrderService.accept` 检查截止时间，`OrderMapper.acceptPending` SQL 同时检查捕获时间与数据库 `CURRENT_TIMESTAMP` | 截止前/后/等于截止时刻及 H2 任务竞争测试；MySQL 竞争尚需运行 | 定时任务不是实时约束、边界条件、数据库时间与应用时钟 |
| 双方协商取消 | `OrderService.proposeCancellation/resolveCancellation` 持有订单行锁；V4 迁移的 `order_cancel_request`；事件与通知同事务 | H2 权限、重放、回滚测试；双账号 Edge UI 测试；MySQL 并发用例待 Docker | 状态机、不变式、事务回滚、并发串行化 |
| 私信与通知隐私 | Mapper 按参与者/收件人过滤，通知在订单事务写入；`NotificationService` 按 JWT 用户读/已读 | H2 隔离测试与 Edge 双账号流程 | 资源所有权校验、轮询、分页、已读幂等 |
| 密码更改后的令牌失效 | `app_user.token_version` + `JwtService.issue` 声明 + `SecurityConfig` 解码校验 | H2 改密、错误原密码、重置防重放、旧 JWT 401 | JWT 无状态与主动失效权衡、BCrypt、验证码 TTL |
| 多实例限流 | `RedisWindowRateLimiter` 使用 Redis TIME + Lua + ZSET 60 秒滑动窗口；Redis 故障返回 503 | 包装层单元测试通过；真实 Redis 用例因 Docker 不可用跳过 | Redis 原子脚本、TTL、固定/滑动窗口、故障策略 |
| AI 草稿可选 | `OpenAiProductDraftClient` 结构化输出校验、超时；`AiRequestRateLimiter` 限流 | 模拟 HTTP 测试通过；无真实 Key，未完成真实模型联调 | API 超时、JSON Schema、降级、费用控制 |

可复现的 MySQL 性能数据：**暂无**。执行 [`V6_PERFORMANCE.md`](V6_PERFORMANCE.md) 的脚本并保存数据规模、执行计划和测量过程之后，才可在简历中填写数字。当前项目作为实习作品可强调事务、权限、状态机、测试设计；不要写未经验证的 QPS、优化比例或生产用户量。
