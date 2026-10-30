# CampusTrade V3 第一阶段记录：可复现 MySQL 环境

完成日期：2026-10-30
分支：`feature/v3-resume-readiness`

## 1. 本阶段完成

- 初始化 Git，提交未改动的 V2 基线 `fee5d48`，从 `main` 创建 V3 功能分支。
- 在开发 Compose 中加入 MySQL 8.4，使用 3307 主机端口、`utf8mb4`、健康检查和持久化命名卷。
- 引入 Spring Boot Flyway starter 与 MySQL 数据库支持。
- 将当前五张业务表和五条分类整理为 V1、V2 版本迁移。
- `local` profile 继续使用 H2/`schema.sql`，Flyway 明确关闭，不破坏快速学习环境。
- `mysql` profile 关闭 Spring SQL 初始化并启用 Flyway；支持空库自动建立，也支持已完成 V2 的旧库登记基线。
- 增加基于 Testcontainers 和真实 MySQL 8.4 的 schema、索引、订单并发与事务回滚测试。

## 2. 为什么这样设计

H2 适合快速反馈，但不能证明 MySQL InnoDB 的锁和唯一索引语义。V3 保留两层测试：普通测试快速覆盖功能，显式 MySQL 测试覆盖数据库差异和关键并发路径。

订单创建仍采用“条件更新 + 事务 + 唯一约束”三层保护：

1. `UPDATE product ... WHERE status = 'ON_SALE'` 只有一个并发事务能把商品改为 `RESERVED`。
2. `@Transactional` 让商品预订和订单插入共同提交或共同回滚。
3. `(buyer_id, request_key)` 唯一约束阻止同一买家的同一个请求键创建第二笔订单。

MySQL 测试还覆盖了一个容易遗漏的顺序：第二件商品先成功预订、随后订单插入因重复请求键失败时，事务必须把第二件商品恢复为 `ON_SALE`。

## 3. 数据库迁移策略

- 空数据库：Flyway 执行 V1 建表，再执行 V2 分类种子。
- 已有 V2 非空数据库：`baseline-on-migrate=true` 和 `baseline-version=2` 只登记当前版本，不改动历史数据。
- 该基线策略假设旧库已经完整执行三份 V2 手工 SQL。首次接管前必须备份并检查五张表；Flyway 不会替你判断旧表是否缺字段。
- 从 V3 后不再修改或重复执行旧 SQL。每次数据库变化新增 `V3__...sql`、`V4__...sql` 等迁移。

## 4. 实际测试结果

执行命令：

```powershell
.\mvnw.cmd '-Dcampus.mysql.tests=true' '-Dtest=MySqlOrderConcurrencyIntegrationTests' test
```

环境与结果：

- Docker Desktop 29.9.2；Testcontainers 2.0.5；MySQL 8.4。
- Flyway 在空库中校验并执行 2 个迁移，最终 schema 版本为 2。
- 断言分类数据和商品、订单、图片关键索引存在。
- 8 个买家线程同时购买同一商品：只有 1 个创建成功，数据库只有 1 条待面交订单，商品为 `RESERVED`。
- 重复请求键触发冲突后，第二件商品仍为 `ON_SALE` 且没有残留订单。
- 测试汇总：2 项，0 失败，0 错误，0 跳过，`BUILD SUCCESS`。

随后执行普通 `mvnw test` 回归：共发现 22 项，其中 20 项执行并通过，MySQL 专项的 2 项因未传启用开关而按设计跳过；0 失败、0 错误，`BUILD SUCCESS`。Redis 与 Mailpit 正在运行，因此邮件/验证码外部组件测试本次没有跳过。

## 5. 主要文件

- `pom.xml`：Flyway、Testcontainers 和 Spring Boot service connection 测试依赖。
- `docker-compose.dev.yml`、`.env.example`：可复现的 MySQL/Redis/Mailpit 开发环境。
- `src/main/resources/db/migration/mysql/`：版本化 MySQL schema。
- `src/main/resources/application-mysql.properties`：Flyway 与 MySQL profile。
- `src/test/java/com/campus/campus_trade_backend/MySqlOrderConcurrencyIntegrationTests.java`：真实数据库关键路径测试。

## 6. 已知限制与下一步

- 本阶段证明了空库迁移和订单关键并发路径，尚未自动检查用户原有数据库的五张表是否完整。
- Compose 命名卷提供重启持久化配置，但尚未把用户原有 3306 数据自动搬到 Docker；两套数据库应保持明确分离。
- Windows 上 Testcontainers 测试全部断言通过后，Surefire 仍可能等待其 fork JVM 最多 30 秒再强制结束；最终退出码和构建结果均为成功，但会增加测试耗时，后续可继续定位残留线程。
- 真实外部邮箱投递仍缺服务商授权码；Mailpit 只验证本地 SMTP 链路。
- 下一阶段实现商品分类、价格、排序、时间字段、编辑和前端交互，再进行 MySQL 查询计划验证。
