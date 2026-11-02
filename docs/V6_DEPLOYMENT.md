# V6 本地部署与运维边界

## 环境要求

源码目标 Java 17，Spring Boot 4.1.2；MySQL 8.4、Redis 7、SMTP 服务。开发用 `docker-compose.dev.yml` 的 Mailpit 只模拟 SMTP 收件，不向真实邮箱投递。公网环境必须配置真实 SMTP 与固定随机 JWT 密钥，不能使用 `.env.example` 的占位值。`CAMPUS_DB_PASSWORD`、`CAMPUS_JWT_SECRET`、`CAMPUS_SMTP_PASSWORD`、`CAMPUS_AI_API_KEY` 必须从部署平台密钥管理注入。没有 AI Key 时保持 `CAMPUS_AI_ENABLED=false`，手动发布可用。

## 本地 Docker 启动

1. `Copy-Item .env.example .env`，填好数据库密码、root 密码和 32 字节以上随机密钥的 Base64 字符串。`.env` 被 Git 忽略。
2. 仅启动依赖：`docker compose -f docker-compose.dev.yml up -d`。此时可用 IDEA 运行主类并设置 `--spring.profiles.active=mysql` 与环境变量。
3. 连应用一起启动：先停止占用 8080 的 IDEA 进程，再运行 `docker compose -f docker-compose.dev.yml --profile app up -d --build`。
4. 检查 `docker compose -f docker-compose.dev.yml --profile app ps`、`docker compose -f docker-compose.dev.yml logs app`。访问 `http://localhost:8080/api/categories` 检查 HTTP 与数据库访问；访问首页并完成两账号交易流程才算业务验收。

`Dockerfile` 使用 Maven/Temurin 17 构建，运行阶段使用非 root 用户。MySQL、Redis、Mailpit、商品图片各有命名卷。图片目录容器内为 `/data/uploads`，生产部署必须挂载持久化卷并设置读写权限。Compose 的 `app` profile 仅供本地开发，不等于公网部署方案。

## 数据库迁移与备份

空库由 Flyway V1–V5 顺序初始化。旧 V2 非空库配置 `baseline-on-migrate=true` 且 baseline version 2；首次接管前应先备份、核对 V2 五张基础表，不要重放 `schema.sql`。V6 迁移增加 `order_cancel_request` 与 `app_user.token_version`。已经由 Flyway 管理的库不要改历史迁移文件；新变更另建 V6 脚本。启动日志应看到迁移成功，没有成功前不要接入流量。

备份示例（请在本机终端输入密码，避免在命令行写明文）：

```powershell
mysqldump --host=127.0.0.1 --port=3307 --user=campus_trade --password --single-transaction --routines --triggers campus_trade > campus_trade_backup.sql
```

恢复必须先在隔离测试库演练，确认迁移历史与图片目录匹配，再考虑生产恢复。商品图片不在数据库备份中，需要同时备份上传卷。恢复操作可能覆盖现有数据，本轮没有执行。Redis 验证码和限流状态无需永久恢复；恢复后短时间内验证码会失效。

## 运行观察与已知限制

日志带请求 ID，订单状态转换有结构化参数；不要记录密码、验证码、JWT、SMTP 凭据。当前 `server.error.include-message=always` 适合开发，正式环境需评估关闭详细错误。健康检查目前可用公开 `GET /api/categories` 作为简单读库探针，尚无独立 readiness/liveness 端点。私信与通知通过轮询更新；多实例部署时 Redis 限流统一计数，但本地图片卷必须共享或迁移至对象存储。当前没有执行公网部署、生产备份恢复演练或镜像启动验收。
