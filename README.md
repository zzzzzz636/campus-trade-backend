# CampusTrade

CampusTrade 是一个面向高校学生的模块化单体二手交易平台。当前 UI 改造分支为 `feature/ui-product-redesign`，基于 V6 代码，使用 Java 17、Spring Boot 4.1、Spring Security、JWT、MyBatis、MySQL/H2、Redis、SMTP、Docker Compose、Flyway 和原生 HTML/CSS/JavaScript。`main` 尚未合并该分支。

## 当前已实现

- 邮箱验证码注册、登录、JWT 鉴权和禁用账号令牌即时失效。
- 商品发布、关键词/分类/价格筛选、分页、三种白名单排序、详情、在售编辑、个人发布列表和下架。
- 单商品最多 6 张 JPEG/PNG/WebP 图片；真实文件头、大小和权限校验；本地存储可替换。
- 买家申请购买、卖家接受/拒绝、超时释放、发货、确认成交，以及未发货订单的双方协商取消；订单分页与状态筛选。
- 订单通知中心、私信与未读状态、商品收藏；个人中心可修改昵称、密码及通过邮箱验证码重置密码。
- 条件更新防止并发重复购买，请求标识防止重复下单。
- Redis 验证码 TTL、发送冷却、错误次数限制和成功后防重放。
- Mailpit 本地 SMTP 联调、统一异常响应、Swagger UI 和自动化测试。
- 可选的 AI 辅助发布：根据原始描述建议标题、规范描述和已有分类；结构化输出二次校验，超时、瞬时重试、按用户限流且失败不阻塞手动发布。

## 快速入口

- 运行说明：[`docs/quickstart.md`](docs/quickstart.md)；V6 容器部署与备份：[`docs/V6_DEPLOYMENT.md`](docs/V6_DEPLOYMENT.md)
- V6 架构审计：[`docs/V6_ARCHITECTURE_AUDIT.md`](docs/V6_ARCHITECTURE_AUDIT.md)；[架构图与 ER 图](docs/V6_ARCHITECTURE.md)
- [V6 API](docs/V6_API.md) · [测试记录](docs/V6_TEST_REPORT.md) · [性能记录](docs/V6_PERFORMANCE.md) · [简历证据](docs/V6_RESUME_EVIDENCE.md)
- [全站 UI 改造记录与新旧截图](docs/UI_PRODUCT_REDESIGN.md)
- V3 现状审计：[`docs/V3_AUDIT.md`](docs/V3_AUDIT.md)
- V3 第一阶段记录：[`docs/V3_PHASE1.md`](docs/V3_PHASE1.md)
- V3 第二阶段记录：[`docs/V3_PHASE2.md`](docs/V3_PHASE2.md)
- V3 第三阶段记录：[`docs/V3_PHASE3.md`](docs/V3_PHASE3.md)
- V2 技术设计：[`docs/V2_TECHNICAL_NOTES.md`](docs/V2_TECHNICAL_NOTES.md)
- 页面：`http://localhost:8080/`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- Mailpit：`http://localhost:8025/`

## 测试

```powershell
$env:JAVA_HOME='C:\Users\zw\.jdks\ms-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
```

普通测试使用独立 H2 数据库。Windows Edge 浏览器测试会运行双账号流程；非 Windows 平台会跳过。Docker Redis/Mailpit 可用时，`DockerMailRedisIntegrationTests` 执行真实 Redis + SMTP 流程；服务未运行时明确跳过。显式加入 `-Dcampus.mysql.tests=true` 可执行 Testcontainers MySQL 8.4 迁移与订单并发测试。当前本机 Docker 引擎不可连接，因此 V6 的 MySQL、Redis、SMTP 和容器镜像仍待真实联调；模拟测试通过不代表它们已通过。

> 不要将数据库密码、JWT 密钥、SMTP 授权码或 AI API Key 提交到仓库。`.env` 已被忽略，`.env.example` 只提供变量名称。
