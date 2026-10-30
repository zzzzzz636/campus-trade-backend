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

## 启动 Redis 与本地邮件服务

Docker Desktop 运行后，在项目目录执行：

```powershell
docker compose -f docker-compose.dev.yml up -d
docker compose -f docker-compose.dev.yml ps
```

Redis 位于 `127.0.0.1:6379`；Mailpit SMTP 位于 `127.0.0.1:1025`，邮件界面为 `http://localhost:8025/`。Mailpit 只用于本地验证真实 SMTP 调用，不会把邮件投递到互联网。

## 切换到 MySQL

已执行过原始 `schema.sql` 时不要重跑。按顺序确认已执行 `src/main/resources/db/mysql-v1-extra.sql`、`mysql-v2-order.sql`，再执行一次 `mysql-v2-images.sql`。项目账号 `campus_trade` 需要 `SELECT`、`INSERT`、`UPDATE`、`DELETE` 权限。

从新的 PowerShell 启动 MySQL 配置时，先进入 `E:\campus-trade-backend` 并在同一终端运行：

```powershell
$env:JAVA_HOME='C:\Users\zw\.jdks\ms-17.0.19'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:CAMPUS_DB_USER='campus_trade'
$secret = Read-Host '请输入 campus_trade 数据库密码' -AsSecureString
$env:CAMPUS_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $secret).Password
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$keyBytes = New-Object byte[] 32
$generator.GetBytes($keyBytes)
$generator.Dispose()
$env:CAMPUS_JWT_SECRET = [Convert]::ToBase64String($keyBytes)
$env:CAMPUS_REDIS_URL='redis://127.0.0.1:6379'
$env:CAMPUS_SMTP_HOST='127.0.0.1'
$env:CAMPUS_SMTP_PORT='1025'
$env:CAMPUS_SMTP_AUTH='false'
$env:CAMPUS_SMTP_STARTTLS='false'
$env:CAMPUS_SMTP_FROM='noreply@campustrade.local'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=mysql'
```

此处 JWT 密钥只在当前进程中保存；重新生成后旧令牌失效。正式部署需用安全保存的固定密钥。不要把密码或密钥写入仓库。IDEA Community 也可运行主类：JRE 设为 17，Program arguments 填 `--spring.profiles.active=mysql`，Environment variables 填 `CAMPUS_DB_USER`、`CAMPUS_DB_PASSWORD`、`CAMPUS_JWT_SECRET`。

## 第一版接口

除公开查询和注册、登录外，请求需加 `Authorization: Bearer <accessToken>`。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/auth/verification-codes` | 发送六位注册验证码 |
| `POST /api/auth/register` | 注册；`email`、`password`、`nickname`、`code` |
| `POST /api/auth/login` | 登录；`email`、`password`；返回 `accessToken` |
| `GET /api/auth/me` | 查询当前用户 |
| `GET /api/categories` | 查询可选分类 |
| `GET /api/products?page=1&size=10&q=教材` | 查询和搜索在售商品；结果含发布者昵称 |
| `GET /api/products/{id}` | 商品详情 |
| `GET /api/products/me` | 我的发布，含在售、已预订和已下架状态；最近 100 件 |
| `POST /api/products` | 发布；`categoryId`、`title`、`description`、`price`、`conditionLevel` |
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

`conditionLevel` 可选 `NEW`、`LIKE_NEW`、`GOOD`、`FAIR`。选购后订单状态为 `PENDING_MEETUP`，商品变为 `RESERVED`，从公开列表消失。删除采用下架以保留交易记录。

V2 尚未实现收藏、聊天/联系方式治理、举报审核、密码找回、AI 功能和 Vue 前端；根路径提供可真实调用后端的原生前端体验页。
