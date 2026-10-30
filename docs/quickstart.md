# CampusTrade 后端快速运行

项目使用 Java 17 和 Maven Wrapper。默认 `local` 配置使用 H2 内存数据库，启动时自动建立用户、分类、商品、图片和订单表并插入两条演示商品；重启后数据恢复为种子状态。演示用户没有可登录密码，请自行注册。`local` 验证码只打印在后端控制台，MySQL 配置使用 Redis 与 SMTP。

在 `E:\campus-trade-backend` 的 PowerShell 终端中运行：

```powershell
$env:JAVA_HOME='C:\Users\zw\.jdks\ms-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

启动后访问 `http://localhost:8080/`。登录后进入商品广场，可搜索、发布多图商品、下架和选购；右上角进入个人中心，查看买入、卖出订单及自己发布的商品状态。Swagger UI 为 `http://localhost:8080/swagger-ui.html`。商品 API 为 `http://localhost:8080/api/products?page=1&size=10&q=教材`，`page` 从 1 开始，`size` 范围为 1–50，`q` 最长 100 字。

## 启动完整开发基础设施

Docker Desktop 运行后，先复制变量模板。`.env` 已被 Git 忽略，只供本机 Docker Compose 读取：

```powershell
Copy-Item .env.example .env
notepad .env
```

至少将 `CAMPUS_DB_PASSWORD`、`CAMPUS_DB_ROOT_PASSWORD` 和 `CAMPUS_JWT_SECRET` 的占位值改掉。然后启动 MySQL、Redis 和 Mailpit：

```powershell
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

Docker MySQL 默认位于 `127.0.0.1:3307`，避免与 Windows 本机 MySQL 的 3306 冲突；Redis 位于 `127.0.0.1:6379`；Mailpit SMTP 位于 `127.0.0.1:1025`，邮件界面为 `http://localhost:8025/`。三者都使用命名卷，重建普通容器不会删除数据。Mailpit 只用于本地验证真实 SMTP 调用，不会把邮件投递到互联网。

## 切换到 MySQL

V3 使用 Flyway 管理 MySQL 表结构：

- 对全新的空数据库，应用首次启动会自动执行 `V1__create_v2_baseline_schema.sql` 和 `V2__seed_categories.sql`。
- 对已经手工完成 V2 三份旧 SQL 的非空数据库，Flyway 会登记一个版本 2 基线，不会重建表或删除数据。首次接管前应备份，并确认 `app_user`、`category`、`product`、`product_image`、`trade_order` 五张表均存在。
- 接入 Flyway 后不要再重跑 `schema.sql`、`mysql-v1-extra.sql`、`mysql-v2-order.sql` 或 `mysql-v2-images.sql`。后续结构变化只增加新的版本迁移文件。

从新的 PowerShell 启动 Docker MySQL 配置时，先读取 `.env` 中你设置的值并在同一终端运行。以下密码和 JWT 示例必须替换为 `.env` 中的实际本机值：

```powershell
$env:JAVA_HOME='C:\Users\zw\.jdks\ms-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:CAMPUS_DB_URL='jdbc:mysql://127.0.0.1:3307/campus_trade?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:CAMPUS_DB_USER='campus_trade'
$env:CAMPUS_DB_PASSWORD='填写 .env 中的 CAMPUS_DB_PASSWORD'
$env:CAMPUS_JWT_SECRET='填写 .env 中的 Base64 密钥'
$env:CAMPUS_REDIS_URL='redis://127.0.0.1:6379'
$env:CAMPUS_SMTP_HOST='127.0.0.1'
$env:CAMPUS_SMTP_PORT='1025'
$env:CAMPUS_SMTP_AUTH='false'
$env:CAMPUS_SMTP_STARTTLS='false'
$env:CAMPUS_SMTP_FROM='noreply@campustrade.local'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=mysql'
```

`.env` 不会被 Spring Boot 或 IDEA 自动加载。IDEA Community 也可运行主类：JRE 设为 17，Program arguments 填 `--spring.profiles.active=mysql`，Environment variables 至少填写 `CAMPUS_DB_URL`、`CAMPUS_DB_USER`、`CAMPUS_DB_PASSWORD`、`CAMPUS_JWT_SECRET`，以及 Redis/SMTP 变量。正式部署需安全保存固定 JWT 密钥；重新生成密钥会使旧令牌失效。不要把密码或密钥写入仓库。

## 执行真实 MySQL 集成测试

Docker Desktop 运行时可执行：

```powershell
$env:JAVA_HOME='C:\Users\zw\.jdks\ms-17.0.19'
.\mvnw.cmd '-Dcampus.mysql.tests=true' '-Dtest=MySqlOrderConcurrencyIntegrationTests' test
```

测试会临时启动一个隔离的 MySQL 8.4 容器，验证 Flyway 建表和索引，并让 8 个线程同时购买同一商品，断言最多只有一笔有效订单。测试容器退出后自动清理，不使用或修改本机业务数据库。普通 `mvnw test` 不会启动该容器测试。

## 第一版接口

除公开查询和注册、登录外，请求需加 `Authorization: Bearer <accessToken>`。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/auth/verification-codes` | 发送六位注册验证码 |
| `POST /api/auth/register` | 注册；`email`、`password`、`nickname`、`code` |
| `POST /api/auth/login` | 登录；`email`、`password`；返回 `accessToken` |
| `GET /api/auth/me` | 查询当前用户 |
| `GET /api/categories` | 查询可选分类 |
| `GET /api/products?page=1&size=10&q=教材&categoryId=1&minPrice=10&maxPrice=100&sort=NEWEST` | 查询在售商品；支持关键词、分类、价格和排序 |
| `GET /api/products/{id}` | 商品详情 |
| `GET /api/products/me` | 我的发布，含在售、已预订和已下架状态；最近 100 件 |
| `POST /api/products` | 发布；`categoryId`、`title`、`description`、`price`、`conditionLevel` |
| `PUT /api/products/{id}` | 卖家编辑自己的在售商品；请求字段与发布一致 |
| `DELETE /api/products/{id}` | 卖家下架自己的商品；成功返回 204 |
| `POST /api/products/{id}/images` | 上传 1–6 张商品图片；multipart 字段 `files` |
| `DELETE /api/products/{id}/images/{imageId}` | 删除自己商品的图片 |
| `POST /api/orders` | 选购；`productId`、可选 `requestKey`；创建线下面交订单 |
| `GET /api/orders/me` | 我的买入和卖出订单，暂取最近 50 条 |
| `GET /api/orders/purchases` | 我的买入订单，含商品标题；最近 50 条 |
| `GET /api/orders/sales` | 卖家查看收到的订单 |
| `POST /api/orders/{id}/cancel` | 买家取消待面交订单 |
| `POST /api/orders/{id}/complete` | 买家确认线下成交 |
| `GET /api/orders/{id}` | 订单参与者查看详情 |

`conditionLevel` 可选 `NEW`、`LIKE_NEW`、`GOOD`、`FAIR`；`sort` 可选 `NEWEST`、`PRICE_ASC`、`PRICE_DESC`。选购后订单状态为 `PENDING_MEETUP`，商品变为 `RESERVED`，从公开列表消失。删除采用下架以保留交易记录；已预订、已售出或已下架的商品不能编辑。

V2 尚未实现收藏、聊天/联系方式治理、举报审核、密码找回、AI 功能和 Vue 前端；根路径提供可真实调用后端的原生前端体验页。
