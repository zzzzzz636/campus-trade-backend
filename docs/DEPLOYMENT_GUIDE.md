# CampusTrade 本地与部署指南

## 前提与配置

需要 Java 17、Docker Compose 与可用的 Docker Engine。复制 `.env.example` 为**不提交 Git** 的 `.env`，填写独立的 MySQL、RabbitMQ、Grafana 密码以及 Base64 编码的至少 32 字节 JWT 密钥。`CAMPUS_AI_ENABLED=false` 时无需模型密钥。真实邮件服务需自行配置 SMTP；Compose 中的 Mailpit 只用于本地测试。

Compose 文件为 [docker-compose.dev.yml](../docker-compose.dev.yml)。服务名用于容器内部 DNS；不再使用固定容器名，避免多个项目互相冲突。数据分别保存在 MySQL、Redis、RabbitMQ、Kafka、Elasticsearch、Mailpit、上传目录、Prometheus 和 Grafana 的命名卷中。不要执行 `down -v` 来停止有数据的环境。

## 分层启动

在仓库根目录执行，以下 `campustrade` 可替换为自定义 Compose 项目名：

```powershell
# 正式 Dockerfile 使用 Java 17 JRE，并复制本地 Maven Wrapper 产出的 JAR。
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\mvnw.cmd -B test package
docker compose -p campustrade --env-file .env -f docker-compose.dev.yml up -d mysql redis rabbit mailpit
docker compose -p campustrade --env-file .env -f docker-compose.dev.yml --profile app up -d --build app
docker compose -p campustrade --env-file .env -f docker-compose.dev.yml ps
```

`.dockerignore` 只允许构建产物 JAR 进入 Docker 上下文；`target/` 中其他日志、备份和本地凭据不会复制。每次源码变化后必须重新打包再构建镜像。Dockerfile 使用非 root 用户运行；基础 JRE 镜像已有 `curl`，无需联网安装。

核心环境只运行交易所需服务。需要全文搜索时先启动 `--profile search up -d elasticsearch`，把 `.env` 中 `CAMPUS_SEARCH_ENABLED=true` 后重启应用；已有商品初次接入索引时只执行一次 `CAMPUS_SEARCH_REBUILD=true`。需要行为热榜时先启动 `--profile analytics up -d kafka`，再设置 `CAMPUS_KAFKA_ENABLED=true` 重启应用。宿主机单独运行 Java 时 Kafka 使用 `127.0.0.1:9094`；Compose 内部应用使用 `kafka:9092`。

监控环境使用 `--profile app --profile monitor up -d prometheus grafana`，需先设置强 `CAMPUS_GRAFANA_ADMIN_PASSWORD`；Grafana 未设置密码会拒绝启动。Prometheus 本机 `9090`、Grafana 本机 `3000`。Nginx 可选：`--profile app --profile edge up -d nginx`，当前只在 `127.0.0.1:8088` 提供 HTTP 预览，阻断外部 `/actuator/`。Elasticsearch、Kafka、监控叠加会消耗明显内存；低资源电脑先运行核心环境，按需追加。

应用的 `/actuator/health` 是健康检查；MySQL/Redis/RabbitMQ 可用时应为 `UP`。Flyway 在 MySQL profile 启动时自动迁移；不要重跑旧 `schema.sql`。生产域名/证书尚未提供，当前**没有公网 HTTPS 部署**。正式上线应让 App、MySQL、Redis、消息系统保持私网，Nginx/Caddy 在 443 终止 TLS、自动续证、限制管理端点并配置备份保留与恢复演练。

## 备份与恢复

PowerShell 7 下运行；脚本通过容器内已有的数据库账号执行，不在命令行打印密码：

```powershell
pwsh -File deploy/backup-mysql.ps1 -Project campustrade -EnvFile .env -OutputFile backups/campus-2026-11-14.sql
pwsh -File deploy/restore-mysql.ps1 -Project campustrade -EnvFile .env -InputFile backups/campus-2026-11-14.sql -ConfirmDatabaseName campus_trade
```

恢复前停止应用写入并核对项目名、数据库名、文件路径；脚本要求明确输入目标库名。数据库备份不包含上传图片卷、Kafka 日志、Redis 热榜或 Elasticsearch 索引；图片需单独备份，索引和热榜可通过既有重建流程恢复。当前脚本未提供自动加密、异地备份或定期调度。

本轮在独立 `campus-accept` 库完成一次约 25 KB 的 SQL 备份，插入临时分类，再恢复；临时分类消失，商品 `id=2` 仍为 `SOLD`，Flyway 7 条迁移记录保留。应用重启后健康 `UP`、在售商品可读。**未对用户原有数据库执行恢复。**

## 构建与验收状态

本地 Maven 默认测试 114 个：84 通过、30 按环境开关跳过；Kafka + Redis Testcontainers 6/6 通过，MySQL 订单并发复测 6/6 通过，MySQL/RabbitMQ/Elasticsearch 组合测试另有 7 个通过。隔离环境六个中间件容器均健康，宿主机运行的 Spring Boot 应用完成两账号注册、商品发布、ES 索引、Kafka 热榜、Rabbit 通知、订单成交和服务重启验证。随后用**仅存在于忽略目录 `target/` 的 Compose 覆盖文件**，以本机已有的 Kafka 镜像临时提供 Java 运行时，将最新 JAR 作为应用容器启动；容器健康 `UP`，从容器端口 `8080` 再次完成两账号交易流程，商品 `id=3`、订单 `id=2` 最终为 `SOLD`/`COMPLETED`。这一临时运行时是 Java 21，不能代替正式 Java 17 Dockerfile 的镜像构建验收。

2026-11-15 最终验收：Java 17 Maven Wrapper 执行 `test package` 成功（114 项：84 通过、30 条件跳过、0 失败）；正式 Dockerfile 用 `eclipse-temurin:17-jre` 构建成功，容器内 `java -version` 为 17.0.21、运行用户 `campus`。全新 `campus-final` Compose 数据卷中应用健康 `UP`，两账号注册、发布、ES 搜索、Kafka 热榜、Rabbit 通知、订单接受与成交全部通过，订单/商品最终为 `COMPLETED`/`SOLD`。Nginx 的主页/商品接口返回 200，`/actuator` 及其子路径返回 404。Prometheus 真实抓取目标为 `up`，Redis/Kafka 故障指标已实际变化。Grafana 官方 OSS 和 Enterprise 镜像拉取均停滞，官方二进制下载速率仅约 20 KB/s，**Grafana 页面未实际启动/验收**；详情见 [监控文档](OBSERVABILITY.md)。GitHub Actions 工作流配置已检查；当前仓库没有 Git remote，本轮没有提交/推送，因此**远端 CI 未执行**。
