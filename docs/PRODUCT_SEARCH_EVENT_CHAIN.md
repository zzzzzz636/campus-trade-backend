# 商品搜索事件链路（V7）

原有 `GET /api/products` 继续使用 MySQL；启用 `CAMPUS_SEARCH_ENABLED=true` 后新增 `GET /api/products/search?q=keyboard&categoryId=1&minPrice=0&maxPrice=300&page=1&size=10`。新接口按相关性排序，再按创建时间和 ID 稳定排序。商品是否仍在售以返回时 MySQL 查询为准；索引短暂滞后时，本页数量可能少于索引报告的 `total`。

## 事务与投递

- Flyway `V7__product_search_outbox.sql` 建立独立的商品事件表；`(product_id, product_version)` 唯一。商品发布、编辑、上下架，以及订单预订、释放、售出都在原业务事务内写入事件。
- 发布器通过条件更新抢占 60 秒租约。租约和退避时间均由数据库时钟计算；RabbitMQ 确认成功后才标记 `PUBLISHED`。已发布但未消费的事件 60 秒后重新投递。
- 消费者从 MySQL 读取商品最新快照，按 `version + 1` 使用 Elasticsearch `external_gte` 写入同一文档。已下架、已预订和已售出状态保留为文档状态，搜索只筛选 `ON_SALE`。写索引成功后才写 `product_search_consumed`；重复和乱序消息不会把旧商品状态恢复到索引。
- 发布连续失败 5 次标记 `DEAD`，需人工排查后重新置为 `RETRY`。消费者失败会进入 RabbitMQ 死信队列，但数据库中未消费的 `PUBLISHED` 事件会定期重新投递；修复索引服务后可自动恢复。

## 本地启用和重建

在 `.env` 设置数据库、JWT、RabbitMQ 等原有变量，并设置 `CAMPUS_SEARCH_ENABLED=true`。开发环境使用 `docker compose --profile search -f docker-compose.dev.yml up -d` 启动 Elasticsearch；容器仅绑定本机 9200，开发配置关闭了 Elasticsearch 身份认证，不可直接用于公网。应用容器通过 `http://elasticsearch:9200` 连接；在 IDE 启动时将 `CAMPUS_SEARCH_URL` 设为 `http://127.0.0.1:9200`。

首次为已有商品建索引时，仅在一个应用实例设置 `CAMPUS_SEARCH_REBUILD=true` 并启动一次，完成后改回 `false`。重建创建新物理索引、扫描 MySQL、原子切换别名，再重放扫描开始后的商品事件。旧索引保留，待人工确认后再清理。不要在多个实例上同时启动重建。

## 验证

Java 17：

```powershell
.\mvnw.cmd test
.\mvnw.cmd '-Dcampus.rabbit.tests=true' '-Dtest=MySqlRabbitOutboxIntegrationTests' test
.\mvnw.cmd '-Dcampus.search.tests=true' '-Dtest=MySqlRabbitElasticsearchIntegrationTests' test
```

2026-11-13 本机实际结果：默认测试 108 个（82 执行通过、26 按容器开关跳过）；真实 MySQL + RabbitMQ 3 个通过；真实 MySQL + RabbitMQ + Elasticsearch 3 个通过；真实 MySQL 订单并发 6 个通过。另有搜索服务不可用时的 HTTP 降级用例通过。测试覆盖租约恢复、Broker 故障、重复消息、乱序商品事件、索引写失败后恢复、消费者重启、上下架和全量重建。

性能比较脚本为 `perf/k6-product-search-compare.js`。在同一批可记录数量的商品数据上，分别传入 `SEARCH_BACKEND=mysql` 和 `SEARCH_BACKEND=elastic` 执行，并记录 k6 原始输出、数据量、并发数、p95 与错误率。本轮本机没有 k6 命令，也没有准备具有代表性的性能数据集，因此**没有性能提升数据**。
