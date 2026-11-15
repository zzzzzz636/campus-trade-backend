# CampusTrade 搜索性能实测（2026-11-14）

## 环境与数据

- Windows 开发机：Intel Core i7-13650HX，14 核/20 逻辑处理器；Docker Desktop 分配 20 CPU、约 11.5 GiB 内存。
- Java 17、Spring Boot 4.1.2；MySQL 8.4、Elasticsearch 8.17.10，均为独立 `campus-perf` Compose 项目；应用在宿主机 `18080` 端口；k6 使用 `grafana/k6:1.8.3` 容器。没有把业务数据库用作测试库。
- [生成脚本](../perf/seed-search-10000.sql)在空库 Flyway 迁移完成后写入 **10,000 条合成在售商品**，其中关键词“教材”命中 2,500 条。Elasticsearch 重建后索引有 10,000 个文档。数据不是真实用户流量。
- 每组运行 10 个并发虚拟用户、30 秒、无思考时间，首页 `size=20`。k6 在 Docker 内访问宿主机应用。结果包含网络、Spring MVC、MyBatis/ES 和 JSON 序列化时间。

## k6 原始结果

| 场景 | 版本 | 请求数 | 吞吐/秒 | p50 ms | p95 ms | p99 ms | 失败 |
|---|---|---:|---:|---:|---:|---:|---:|
| MySQL `q=教材` | SQL 优化前 | 4,804 | 159.89 | 61.95 | 71.71 | 76.72 | 0 |
| Elasticsearch `q=教材` | 同一数据集 | 7,326 | 243.92 | 39.38 | 49.62 | 60.85 | 0 |
| MySQL `q=` | SQL 优化前 | 3,367 | 111.95 | 88.57 | 102.77 | 114.34 | 0 |
| Elasticsearch `q=` | 同一数据集 | 7,781 | 259.17 | 37.84 | 42.20 | 52.64 | 0 |
| MySQL `q=教材` | SQL 优化后 | 10,496 | 349.69 | 28.38 | 32.53 | 34.93 | 0 |
| MySQL `q=` | SQL 优化后 | 56,610 | 1,004.66* | 4.24* | 5.26* | 6.09* | 10 |

最后一行的 `*` **不是合格的可比较结果**：10 次 `dial: i/o timeout`，k6 因请求超时超出预设 30 秒后才结束；该运行已受客户端/宿主机网络饱和影响。保留原始数据以便复盘，不据此宣称空关键词接口的实际容量或提升比例。

原始 k6 summary JSON 与 MySQL EXPLAIN ANALYZE 输出在 [perf/results/2026-11-14](../perf/results/2026-11-14)；压测脚本为 [k6-product-search-compare.js](../perf/k6-product-search-compare.js)。

## SQL 诊断与修改

优化前，MySQL 的商品列表先连接卖家和分类，再对匹配结果排序和 `LIMIT 20`：关键词查询连接 2,500 行，`EXPLAIN ANALYZE` 列表耗时约 19.4 ms；空关键词连接 10,000 行，约 34.8 ms。优化后在 [ProductMapper](../src/main/java/com/campus/campus_trade_backend/product/mapper/ProductMapper.java)先对 `product` 按原有筛选/排序分页，再连接 20 行关联数据：相同两条查询的单次 `EXPLAIN ANALYZE` 分别约 0.289 ms、0.078 ms。单次 EXPLAIN 耗时只能说明执行计划变化，不能视为稳定延迟指标。

现有 `(status, created_at, id)` 索引用于最新商品的顺序扫描；未盲目增加索引。`LOWER(title/description) LIKE '%教材%'` 的完整计数仍扫描 10,000 行，单次计数约 10.8 ms，是后续数据规模扩大时需要再评估的瓶颈。

关键词场景 **不能直接把两种后端吞吐当作同功能胜负**：MySQL 按上架时间排序，Elasticsearch 按全文相关性排序，分词及匹配语义不同。空关键词场景两者总数均为 10,000，首页商品 ID 和顺序一致，但仍受单机资源共享、缓存预热和非固定请求速率影响。

## 复现边界

1. 在独立数据库中创建 `target/perf.env`，填入 `PERF_DB_PASSWORD` 和 `PERF_DB_ROOT_PASSWORD`，随后运行 `docker compose -p campus-perf --env-file target/perf.env -f perf/docker-compose.perf.yml up -d`。
2. 启动应用指向 `127.0.0.1:13307/campus_trade_perf`，开启搜索并执行一次索引重建；只在**全新空库**执行 `perf/seed-search-10000.sql`。不要在现有业务数据库运行脚本，也不要在同一库重复执行。
3. 在 k6 容器中设置 `BASE_URL=http://host.docker.internal:18080`、`DATASET_COUNT=10000`、`SEARCH_BACKEND=mysql|elastic`、`QUERY=教材|`、`VUS=10`、`DURATION=30s`，运行 [脚本](../perf/k6-product-search-compare.js)并保存 `--summary-export`。
4. 下一轮应采用固定到达率、独立服务资源与至少三次重复运行，排查空关键词压测超时，再下容量结论。订单接口性能基线本轮尚未完成。

## Kafka 行为链路小规模压测

- 同一台 Windows 开发机、独立 `campus-accept` MySQL/Redis/RabbitMQ/Elasticsearch/Kafka 容器与宿主机 Java 17 应用。Kafka 3.9.1 为单 Broker，Topic 6 分区、复制因子 1；事件 JSON 实测样本约 **150 UTF-8 字节**，生产者 `acks=all`。测试只浏览一件合成商品，因此实际写入集中在**一个分区**，不能据此推断六分区吞吐上限。
- [k6 脚本](../perf/k6-behavior.js)固定到达率 **50 次/秒、30 秒、预分配 20 VU**：1,500 次商品详情请求，全部 HTTP 200，实际 50.00 次/秒；HTTP p50 4.16 ms、p95 6.78 ms、p99 8.77 ms、错误率 0%。这测的是合成商品浏览请求，不是真实用户量。
- 压测完成后，应用指标显示生产成功 1,500 条，热榜和独立统计消费组分别处理 1,500 条。热榜组活动分区 Kafka CLI `CURRENT-OFFSET = LOG-END-OFFSET = 1502`、Lag 0；未写入过的分区 Offset/Lag 显示 `-`。不能将这些空分区的 `-` 视为故障或零流量吞吐。
- 消费端从事件 `occurredAt` 到 Redis 原子更新的直方图：1,500 条总时长 23.826 秒，平均约 **15.88 ms**；按 Prometheus 累积桶线性插值得到 p95 **约 23.4 ms**。这是直方图估值，不是逐事件原始测量；下次应通过 Prometheus `histogram_quantile` 在稳定窗口中复核。
- 真实 Kafka + Redis 测试中暂停热榜消费组、写入同一商品的 200 条事件，再恢复消费组，两次本机测试分别约 **1,007 ms、876 ms** 达到 Redis 计数 200。不能外推为生产环境积压恢复能力。
- 停止 Broker 后一次商品详情请求仍为 HTTP 200，约 209 ms，后台 `campus_kafka_producer_failed_total` 增至 1；Broker 恢复后新事件重新进入热榜。故障期间的行为事件按设计**会丢弃**，订单和商品数据不受影响。

原始数据与 Kafka Lag 输出位于 [同日结果目录](../perf/results/2026-11-14)。尚未进行多商品多分区、不同消息大小、多个实例和多轮重复测试，因此不宣称 Kafka 最大 TPS 或大规模可用性。
