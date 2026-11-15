# CampusTrade 最终本机工程验收（2026-11-15）

| 项目 | 结果 | 实际证据与边界 |
|---|---|---|
| Java 17 构建与测试 | 通过 | 本机 Temurin 17.0.19 执行 `./mvnw.cmd -B test package`，BUILD SUCCESS；114 项中 84 通过、30 条件跳过、0 失败。日志在未跟踪的 `target/final-build-2026-11-15.log`。 |
| 正式 Dockerfile | 通过 | Maven Wrapper 产物作为唯一放行的 `target/` 文件进入 Docker 构建上下文；`docker build --pull=false -t campus-trade:java17-final .` 成功。镜像内 Temurin 17.0.21，UID/GID 999 `campus`。已移除基础 JRE 自带 `curl` 时多余的 `apt-get`。 |
| 干净环境交易闭环 | 通过 | 新 `campus-final` 项目及全新 MySQL/Redis/RabbitMQ/Elasticsearch/Kafka 卷；正式应用容器健康。两账号注册，商品 `id=1` 发布、ES 检索、热榜分数 2、卖家通知、订单 `id=1` 接受并完成，最终 `COMPLETED`/`SOLD`。隔离 Mailpit 是测试 SMTP，不能当真实外部邮箱联调。 |
| Prometheus 与监控安全 | 通过 | Prometheus Target `http://app:8080/actuator/prometheus` 为 `up`；14 条 Dashboard PromQL 查询均成功。隔离 Redis 故障恢复值 `1→0→1`；隔离 Kafka 故障时详情 HTTP 200、失败计数 1。Nginx `/actuator`、`/actuator/health`、`/actuator/prometheus` 均为 404，主页与商品接口为 200。应用和监控端口仅绑定 `127.0.0.1`；本轮指标未包含测试 JWT 密钥或 Bearer 凭据。 |
| Grafana 页面 | 未执行 | `grafana/grafana:11.6.1` 与官方 Enterprise 同版镜像均卡在剩余层；Grafana 官方 169.7 MB 二进制下载实测速率约 20 KB/s，预计数小时，未完成哈希校验和启动。查询语句有效不代表 Grafana 页面已验收。 |
| MySQL 备份恢复 | 通过（上一轮） | `campus-accept` 独立库中备份约 25 KB，插入临时分类后恢复；分类消失，原商品 `SOLD`、Flyway 7 条迁移保留。本轮未在用户业务库执行恢复。脚本见 `deploy/`。 |
| 性能数据 | 通过（有边界） | 1 万条合成商品，10 VU/30 秒；MySQL 关键词搜索 p95 71.71 ms→32.53 ms，原始 k6/EXPLAIN 见 `perf/results/2026-11-14/`。空关键词优化后运行有 10 次网络超时，不作为有效性能提升证据。 |
| GitHub Actions | 未执行 | `.github/workflows/ci.yml` 含 Java17 构建、容器与浏览器测试；本地默认 Maven 命令已通过。仓库目前没有 Git remote，且本轮未提交/推送，不能声称远端 CI 运行通过。 |

上述验收只证明本机隔离环境可运行；没有公网 HTTPS、外部 SMTP、真实用户流量或 Grafana 页面验收。`target/final.env` 为忽略的临时测试凭据，不得提交。停止隔离环境时使用 `docker compose -p campus-final --env-file target/final.env -f docker-compose.dev.yml --profile app --profile search --profile analytics --profile monitor --profile edge stop`，保留测试卷。
