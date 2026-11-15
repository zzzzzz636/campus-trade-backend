# CampusTrade 技术成果与面试证据

| 业务问题 | 已实现的方案与关键代码 | 实际证据 | 面试追问 |
|---|---|---|---|
| 多人抢购同一商品 | `OrderService` 事务内条件预订商品并创建订单，订单请求键限制重复提交；MySQL 最终约束状态 | `MySqlOrderConcurrencyIntegrationTests` 6/6、`MySqlOrderIdempotencyRaceTests` 1/1 实际运行通过；真实两账号成交至 `COMPLETED`/`SOLD` | 条件更新如何避免超卖？失败事务回滚什么？何时还需要唯一索引？ |
| 订单通知与索引同步可靠性 | `OrderOutboxPublisher`、`ProductSearchOutboxPublisher` 在 MySQL 中保存业务事件，RabbitMQ 发布确认、重试/死信，消费端去重；ES 消费读取最新商品状态 | MySQL + RabbitMQ Outbox 3/3、MySQL + RabbitMQ + ES 3/3 容器测试通过；两账号流程中通知与索引实际出现 | 数据库提交后 Broker 故障怎么办？为什么发布确认仍需消费幂等？ |
| 商品搜索性能与功能差异 | `ProductMapper.listOnSale` 先筛选排序分页商品，再连接卖家/分类；ES 用全文检索与相关性排序，返回前仍用 MySQL 核实可售状态 | 独立 1 万条合成数据；MySQL 关键词 p95 从 71.71 ms 降至 32.53 ms，10 VU/30 秒、零错误；详细条件见 [性能报告](PERFORMANCE_REPORT.md) | `%关键词%` 计数为何仍扫描？ES 与 MySQL 何处不能公平比较？ |
| 非关键行为实时分析 | `BehaviorRecorder` 后台有界队列发送版本化 Kafka 事件；`BehaviorConsumers` 两独立组处理热榜/统计；`BehaviorRedisStore` Lua 原子去重、24 小时窗口与代际双写；`BehaviorRebuildService` 从 Kafka 重放并原子切换 | Kafka + Redis 容器测试 6/6，含坏消息重试与死信；实际浏览/收藏热榜分数 2；50 次/秒×30 秒合成压测 1,500 HTTP 成功，两个消费组各处理 1,500；Broker 故障 HTTP 200、失败指标增加 | 为何 Kafka 不替代订单 RabbitMQ？为何 Redis 与 offset 之间仍可能重投递？重建期间如何避免漏计？ |
| 运行状态与恢复 | `Dockerfile` Java 17 JRE 非 root 镜像，`config/InfrastructureMetrics` 与 `observability/prometheus.yml`，隔离 Compose 卷，`deploy/backup-mysql.ps1` 与 `deploy/restore-mysql.ps1` | 2026-11-15 正式镜像构建成功、容器内 Java 17.0.21，`campus-final` 两账号交易通过；Prometheus Target `up`，Redis 故障值 `1→0→1`、Kafka 生产失败值 1；上一轮隔离 MySQL 备份恢复后商品状态及 Flyway 历史保留 | 为什么先用 Maven Wrapper 打包？恢复 MySQL 后图片、ES 和热榜怎样恢复？ |

**简历表述可使用：**

> 在 Java 17 / Spring Boot 模块化单体校园二手平台中，使用 MySQL 事务与条件更新实现并发下单控制；以 RabbitMQ + transactional outbox 保障订单通知和商品索引同步；引入按商品分区的 Kafka 行为事件流和 Redis Lua 去重热榜，支持 24 小时窗口及在线重建。基于 1 万条合成商品数据，通过 MySQL `EXPLAIN ANALYZE` 将列表查询改为先分页再关联，并以 k6 记录优化前后 HTTP 延迟；使用 Testcontainers 验证 MySQL、RabbitMQ、Elasticsearch、Kafka 和 Redis 关键链路。

这段话只描述仓库代码与实际验证。不要写“支撑万级真实用户”“Kafka 生产 TPS”“全站生产上线”或未经重复验证的性能百分比。2026-11-15 已验证正式 Java 17 Docker 应用镜像与 Prometheus 抓取；Grafana 页面、远端 CI 和公网部署**仍未验收**，详见 [部署指南](DEPLOYMENT_GUIDE.md)。
