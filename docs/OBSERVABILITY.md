# CampusTrade 可观测性

## 已接入

Spring Boot Actuator + Micrometer Prometheus Registry 提供 `/actuator/health` 与 `/actuator/prometheus`。Compose 应用端口只绑定本机；Nginx 配置阻断外部 `/actuator/`，Prometheus 通过内部 Compose 网络采集。不要将应用 8080 或 Prometheus 9090 直接暴露公网。指标标签只使用模块、结果、消费组和固定队列名，不包含 token、邮箱、用户 ID 或事件 ID。

| 目标 | 指标或检查 |
|---|---|
| HTTP 请求率/错误率/p95 | `http_server_requests_seconds_count`、`http_server_requests_seconds_bucket` |
| JVM、GC、CPU、线程 | `jvm_memory_used_bytes`、`jvm_gc_pause_seconds_*`、`process_cpu_usage`、`jvm_threads_live_threads` |
| 数据库连接池 | `hikaricp_connections_active`、`hikaricp_connections_pending` |
| MySQL Outbox 积压/重试/死事件 | `campus_outbox_events{kind="order|product",state="pending|retry|dead"}`；数据库不可用返回 `-1` |
| RabbitMQ 主队列及死信队列深度 | `campus_rabbit_queue_messages{queue="..."}`；队列不可读返回 `-1` |
| Elasticsearch 调用延迟/失败 | `campus_search_index_latency_seconds_*{result="success|failure"}`；商品同步失败还可通过 product Outbox 死事件及 Rabbit 死信队列观察 |
| Kafka 生产 | `campus_kafka_producer_sent_total`、`campus_kafka_producer_failed_total`、`campus_kafka_producer_dropped_total`、`campus_kafka_producer_queue` |
| Kafka 消费/失败/死信/延迟 | `campus_kafka_consumer_processed_total{group="heat|stats"}`、`campus_kafka_consumer_failed_total`、`campus_kafka_consumer_dead_lettered_total`、`campus_kafka_consumer_end_to_end_seconds_bucket` |
| Kafka Lag | Kafka 客户端 `kafka_consumer_fetch_manager_records_lag_max`；以 `kafka-consumer-groups.sh --describe` 复核组 offset |
| Redis | `campus_redis_up`，1 为 `PING` 成功，0 为不可用 |

可导入的 [Grafana Dashboard](../observability/grafana/dashboards/campus-trade.json)和 [Prometheus 抓取配置](../observability/prometheus.yml)已加入仓库。Dashboard 内含 HTTP、JVM、连接池、Outbox、搜索、Kafka 和 Redis 面板。

## 已执行的检查

- 宿主机应用连接隔离的 MySQL、Redis、RabbitMQ、Elasticsearch、Kafka 后，`/actuator/health` 返回 `UP`，`campus_redis_up` 为 1；关闭 Redis 的本地环境返回健康 `DOWN`、该指标为 0。
- 对商品详情发送 1,500 次固定到达率请求，HTTP、Kafka 生产、两个消费组计数均增加 1,500；消费延迟直方图出现实际桶值。Kafka CLI 活动分区 Lag 为 0。
- 停止 Kafka Broker 后商品详情仍 HTTP 200，`campus_kafka_producer_failed_total` 增至 1；恢复后新事件继续更新热榜。
- 隔离 Compose 应用容器完成实际交易后，`/actuator/prometheus` 出现 HTTP 200 计数、Kafka 两组各处理 2 条、生产者浏览/收藏各发送 1 条、Redis `up=1`、订单与商品 Outbox 待发送/重试/死事件为 0、Rabbit 主/死信队列深度为 0。指标确由本轮请求触发。
- Prometheus 容器在隔离 Compose 中实际抓取 `http://app:8080/actuator/prometheus`，Target 为 `up`。PromQL 查询 `campus_redis_up=1`、两个 Kafka 组累计处理 4 条、Outbox 待处理数 0。短暂停止**隔离环境** Redis 后，下一次抓取的 `campus_redis_up` 变为 0；启动后恢复为 1。
- 短暂停止隔离 Kafka Broker 后，商品详情仍返回 HTTP 200（本次 302 ms），Prometheus 中 `sum(campus_kafka_producer_failed_total)` 为 1。Broker 恢复健康后再次浏览，热榜分数从 1503 增至 1504；故障期间的非关键事件允许丢失。
- Docker Compose 语法检查、Grafana JSON 解析通过，14 条面板 PromQL 在真实 Prometheus API 中均返回 `success`。**Grafana 页面与面板渲染尚未验证**：官方镜像拉取到最后几层持续停滞，已停止本次下载；不能将查询解析通过视为页面验收通过。
- 2026-11-15 使用正式 Java 17 Docker 应用和全新 `campus-final` 环境复测：Prometheus Target `up`；停止 Kafka 后在售商品详情 HTTP 200（79 ms）、生产失败指标为 1；停止并恢复 Redis 时 `campus_redis_up` 为 `1 → 0 → 1`。14 条 Dashboard PromQL 全部查询成功，其中 1 条故障率指标暂无当前序列。指标正文未包含本轮 JWT 测试密钥或 `Bearer `；Nginx 对 `/actuator`、`/actuator/health`、`/actuator/prometheus` 实测均返回 404，公开主页/商品接口仍为 200。

## 排障和告警建议

1. 先看健康状态、HTTP 5xx 与 p95，再看 Hikari pending、MySQL Outbox `pending`/`dead`、Rabbit 主/死信队列。
2. 搜索异常时区分 ES 请求失败和商品索引积压；交易写入以 MySQL 为准。
3. Kafka `failed`、`dead_lettered`、`dropped` 连续增长应告警；Lag 指标中未消费过的空分区可能显示 `NaN`，应结合 Kafka CLI 中实际有数据分区的 offset 差值判断积压。
4. Redis `up=0` 时热榜和验证码等功能受影响；不要把采集链路失败升级为商品浏览失败。
5. Grafana 告警阈值需要在稳定真实环境中依据基线设定；目前未编造具体 SLO 阈值。
