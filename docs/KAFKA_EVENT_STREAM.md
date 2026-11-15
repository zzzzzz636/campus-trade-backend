# Kafka 用户行为事件流

## 业务边界

订单通知和商品搜索索引同步继续使用 MySQL transactional outbox + RabbitMQ。Kafka 只处理**非关键**商品详情浏览、首次收藏行为；Kafka 不可用时商品浏览、收藏及交易正常执行，相关分析事件可能丢失。不要把本模块称为交易 Outbox 或零丢失采集。

`ProductController.detail()` 对成功展示的在售商品记录 `PRODUCT_VIEW`；`FavoriteService.add()` 仅在首次收藏事务**提交后**记录 `PRODUCT_FAVORITE`。同一商品的 `productId` 是 Kafka key，保证单个分区日志中的追加顺序；不同商品、不同分区没有全局顺序保证。事件不包含邮箱、昵称、JWT 或用户 ID。

事件 JSON v1 包含 `schemaVersion`、随机 `eventId`、`type`、`productId`、`occurredAt`。Topic `campus.behavior.v1` 为 6 分区、本地单 Broker 副本数 1，保留 72 小时。上线多 Broker 时应把副本数、min ISR 和认证配置按实际集群调整。生产者采用 `acks=all`，但是独立有界后台队列满、Kafka 元数据不可用或投递失败时会丢弃非关键事件，并增加指标。

## 消费与热榜

- `campus-heat-v1` 消费组以 Redis Lua 对每个商品的 24 小时事件 ZSet 做 `ZADD NX`、过期清理和排行分数更新；事件 ID 是去重成员。同一 Lua 脚本完成去重及计数，避免成功更新 Redis 后 offset 尚未提交导致的重试重复计数。
- 独立的 `campus-behavior-stats-v1` 消费组以事件 ID `SET NX` + 当日类型 `HINCRBY` 统计，证明消费组可独立消费同一 Topic。统计键 4 天过期，去重键 3 天过期；当前仅供内部分析，尚未对外暴露统计 API。
- 消费成功后 `MANUAL_IMMEDIATE` 确认 offset。失败最多额外重试两次，然后投递 `.DLT` 并提交原消息 offset；DLT 保留 7 天，**需人工排障与重放**，没有自动保证最终写入热榜。Redis 长时间不可用时不能把 DLT 看成已完成处理。
- `/api/products/hot?size=10` 读取当前排行榜，并用 MySQL 再检查商品仍在售；售出/下架商品不会展示。当前精确窗口查询会清理所有参与排行的商品，成本随活跃商品数增长，后续应按实际规模改为分桶或后台刷新。

## 历史重放和在线切换

设置 `CAMPUS_KAFKA_REBUILD=true` 后启动**一个**应用实例，`BehaviorRebuildService` 使用独立临时消费组从 Topic 最早可用 offset 读取至启动时捕获的各分区末尾。Redis `rebuild` 租约防止两个重建任务并行；在线消费 Lua 在重建期间原子双写当前代际和新代际，历史回放按事件 ID 去重写新代际。捕获末尾之后的新事件已由在线消费双写。全部分区追上捕获末尾后，Lua 原子切换 `active` 指针；之后新事件只写新代际。失败不会切换指针，租约约 10 分钟到期，运行中持续续租。单次重建安全上限为 10 分钟、200 万条事件；超限需调整方案，不能自动跳过数据。

重建仅覆盖 Kafka 保留期内的事件；由于热度窗口只有最近 24 小时且 Topic 保留 72 小时，正常保留策略下足以重建当前热榜。首次重建后将 `CAMPUS_KAFKA_REBUILD` 改回 `false`，避免每次重启重复生成代际。独立统计消费组不随热榜重建重置。

## 实际验证

- `KafkaBehaviorIntegrationTests` 使用真实 Testcontainers Kafka + Redis：重复投递不重计、两个独立消费组、消费者重启后的 offset 恢复、历史重放及重建中在线双写、200 条积压恢复，以及坏消息经有限重试进入死信主题。最近一次 6/6 通过；本机 200 条恢复分别约 1,007 ms、876 ms、744 ms，见 [测试结果](../perf/results/2026-11-14/kafka-integration-summary.txt)。
- 隔离 `campus-accept` 环境中真实两账号流程：一次商品浏览 + 一次首次收藏，热榜分数为 2。应用重启设置重建开关后，日志显示回放 4 条事件，Redis 活跃代际切换、健康状态 `UP`。
- 50 次/秒、30 秒的固定到达率压测见 [性能报告](PERFORMANCE_REPORT.md)。Kafka 断开时商品详情仍 HTTP 200，生产失败指标增加；故障期间事件丢弃是明确降级策略。

## 尚未完成

- 没有生产多 Broker、高可用副本、SASL/TLS、跨机网络故障测试，也没有 DLT 自动修复工具。
- 生产者为尽力而为，不保证每次浏览都被采集；实时热榜不应成为交易状态或风控结论来源。
- 暂无搜索关键词事件、用户画像或推荐算法；这些需要单独的隐私与数据质量设计。
