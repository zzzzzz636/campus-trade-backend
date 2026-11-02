# CampusTrade V6 测试报告（2026-11-02）

## 本轮实际执行

| 检查 | 命令 / 环境 | 结果 |
|---|---|---|
| 完整 Maven 套件 | Java 17.0.19；`./mvnw.cmd -q test` | 退出码 0。Surefire XML 合计 **55 个测试、0 失败、0 错误、8 跳过**；实际执行 47 个。 |
| 双账号浏览器 | 完整套件中的 `V4BrowserFlowTests`，Windows Edge | 2 个测试实际执行并通过。含 V5.1 私信布局/自动刷新与 V6 协商取消、昵称和改密界面。 |
| 订单/账号/通知 | H2 + Spring 集成测试 | 截止前后与等于时刻、到期任务竞争、分页、通知隔离、协商取消权限/重放/回滚、改密和重置旧 JWT 失效测试通过。 |
| Redis 限流包装 | `RedisWindowRateLimiterTests` | 允许、超限与 Redis 空结果失败策略的单元测试通过。 |
| JavaScript 语法 | `node --check` 对 `orders-v5.js`、`auth.js`、`profile.js` | 退出码 0。 |
| Compose 语法 | `docker compose -f docker-compose.dev.yml config --quiet` | 退出码 0；因未配置 `.env`，显示缺少本地密钥变量警告。 |

## 未执行或未验证

- `MySqlOrderConcurrencyIntegrationTests` **6 个跳过**：需 `-Dcampus.mysql.tests=true` 且 Docker 引擎可连接。本机 `docker info` 报 `dockerDesktopLinuxEngine` 管道不存在；本轮尝试启动已安装的 Docker Desktop 后仍无后台引擎。迁移 V4/V5、InnoDB 订单竞争、协商取消竞争未在本轮 MySQL 运行。
- `DockerMailRedisIntegrationTests` **2 个跳过**：本机 Redis 6379、Mailpit 1025/8025 不可用。真实 Redis Lua、验证码存储与 SMTP 发送尚未重验。
- 没有 `CAMPUS_AI_API_KEY`，仅模拟 HTTP 与无密钥降级用例通过，**未调用真实模型**。
- 当前终端没有 MySQL 连接凭据，未执行 `EXPLAIN ANALYZE`；见 [`V6_PERFORMANCE.md`](V6_PERFORMANCE.md)。
- GitHub Actions 工作流和 Docker 镜像已编写，但本轮没有远端 CI 运行结果，也没有完成镜像启动、数据重启持久性、备份恢复演练。

## 下一次真实验收步骤

1. Docker Desktop 恢复后启动 `docker compose -f docker-compose.dev.yml up -d`，确认 `docker info`、`docker compose ps` 均正常。
2. 运行 `./mvnw.cmd '-Dcampus.mysql.tests=true' '-Dtest=MySqlOrderConcurrencyIntegrationTests,DockerMailRedisIntegrationTests' test`；记录 MySQL/Redis/SMTP 每项结果。
3. 用隔离 MySQL 测试库执行 [`sql/v6_explain.sql`](sql/v6_explain.sql)，记录数据规模、执行计划与真实耗时。不要在业务库生成大量测试数据。
4. 用两名真实邮箱用户联调 SMTP、商品图片和协商取消；重启应用后确认订单、通知、私信和图片仍存在。

测试命令通过只能证明其覆盖范围内的行为。H2、模拟 HTTP 和浏览器端对 H2 的测试不能代替 MySQL、Redis、SMTP、外部模型或部署验收。
