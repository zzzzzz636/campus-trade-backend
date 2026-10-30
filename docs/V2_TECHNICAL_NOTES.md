# CampusTrade V2 技术说明

## 1. 架构与边界

项目保持模块化单体，不引入微服务。代码按 `auth`、`product`、`order`、`category` 分包；每个业务模块内保留 controller、service、mapper、entity、dto。跨模块只通过服务或明确的 Mapper 协作，没有把业务状态流转写进 Controller。

运行配置分为：

- `local`：H2 内存数据库、本地控制台验证码，便于无外部依赖启动和测试。
- `mysql`：MySQL + Redis + SMTP，所有密码和密钥来自环境变量。
- `docker-compose.dev.yml`：只提供本地 Redis 与 Mailpit，不把应用强行容器化。

## 2. 订单状态机

商品状态：`ON_SALE -> RESERVED -> SOLD`，取消订单时 `RESERVED -> ON_SALE`；卖家可将 `ON_SALE -> OFF_SHELF`。

订单状态：`PENDING_MEETUP -> COMPLETED` 或 `PENDING_MEETUP -> CANCELLED`。

关键代码：

- `order/service/OrderService.java`：创建、取消和完成事务。
- `product/mapper/ProductMapper.java`：带状态条件的商品更新。
- `order/mapper/OrderMapper.java`：只允许待面交订单进行条件更新。

创建订单时先执行：

```sql
UPDATE product
SET status = 'RESERVED', updated_at = CURRENT_TIMESTAMP
WHERE id = ? AND status = 'ON_SALE' AND seller_id <> ?;
```

只有一个并发请求能把同一商品从 `ON_SALE` 改为 `RESERVED`。影响行数为 0 的其他请求返回 409，因而最多创建一个有效订单。订单插入和商品更新位于同一个 `@Transactional` 方法；任一数据库操作失败，两者一起回滚。

客户端可提交 8–64 位 `requestKey`。数据库唯一约束 `(buyer_id, request_key)` 防止同一买家因网络重试重复下单。取消和完成使用 `WHERE status = 'PENDING_MEETUP'`，重复请求不会再次改变状态。

## 3. 邮箱验证码

流程：请求验证码 -> 生成 6 位随机码 -> Redis 保存 HMAC 摘要 -> SMTP 发送 -> 注册时原子校验并删除。

- 有效期 5 分钟。
- 同邮箱 1 分钟发送冷却。
- 最多 5 次错误尝试，达到上限删除验证码。
- Redis 中的邮箱键和验证码都经过 HMAC，不存六位明文。
- Lua 脚本原子完成比较、错误计数和成功删除，防止并发重复使用。
- SMTP 失败会清除本次验证码，接口返回 503，不会假提示成功。

关键代码：`auth/verification/RedisVerificationCodeStore.java`、`VerificationService.java`、`SmtpVerificationMailSender.java`。

本地 Mailpit 验证了真实 SMTP 调用链，但它不是正式邮箱服务。上线时必须提供真实 `CAMPUS_SMTP_*` 环境变量和授权码。

## 4. JWT 与账号状态

登录成功后使用 HS256 签发 JWT，包含用户 ID、issuer 和有效期。密钥要求 Base64 解码后不少于 32 字节。受保护请求由 Spring Security Resource Server 验证签名、issuer 和过期时间。

`SecurityConfig.jwtDecoder` 还会查询用户状态。用户被标记为非 `ACTIVE` 后，即使 JWT 尚未过期也会返回 401，避免禁用账号继续访问。

浏览器当前将令牌放在 `sessionStorage` 并通过 `Authorization: Bearer` 发送。生产环境仍应配合 HTTPS、CSP 和更完整的 XSS 防护。

## 5. 商品图片

数据库 `product_image` 保存商品 ID、随机存储键、原文件名、实际 MIME、大小和顺序；文件本身存储在 `CAMPUS_UPLOAD_DIR`。

上传限制：每件商品最多 6 张、每张最多 5 MB，只接受 JPEG/PNG/WebP。后端读取魔数判断真实类型，不信任扩展名或请求头。存储键使用 UUID，路径经过 normalize 和根目录边界检查。

`FileStorage` 是存储抽象，当前实现为 `LocalFileStorage`；未来替换 MinIO/OSS 时，Controller 和商品业务不需要重写。数据库事务回滚时会清理已写入文件，删除图片在事务提交后清理文件。预订或售出商品禁止修改图片。

关键表：

```sql
CREATE TABLE product_image (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  storage_key VARCHAR(255) NOT NULL UNIQUE,
  original_name VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  file_size BIGINT NOT NULL,
  sort_order INT NOT NULL,
  UNIQUE (product_id, sort_order),
  FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE
);
```

## 6. V2 接口

除注册、登录、验证码、分类、公开商品和图片读取外，均需 Bearer JWT。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/verification-codes` | 发送注册验证码 |
| POST | `/api/auth/register` | 使用验证码注册 |
| POST | `/api/auth/login` | 登录并获取 JWT |
| GET | `/api/auth/me` | 当前用户 |
| GET | `/api/categories` | 分类列表 |
| GET | `/api/products?page=1&size=12&q=教材` | 搜索和分页 |
| GET | `/api/products/{id}` | 商品详情及图片 |
| POST | `/api/products` | 发布商品 |
| GET | `/api/products/me` | 我的商品及状态 |
| DELETE | `/api/products/{id}` | 下架自己的在售商品 |
| POST | `/api/products/{id}/images` | multipart 多图上传，字段名 `files` |
| DELETE | `/api/products/{id}/images/{imageId}` | 删除自己的商品图片 |
| GET | `/api/product-images/{imageId}/content` | 读取图片内容 |
| POST | `/api/orders` | 创建线下面交订单 |
| GET | `/api/orders/purchases` | 买家购买记录 |
| GET | `/api/orders/sales` | 卖家收到的订单 |
| POST | `/api/orders/{id}/cancel` | 买家取消待面交订单 |
| POST | `/api/orders/{id}/complete` | 买家确认成交 |

发送验证码：

```json
{"email":"student@example.edu"}
```

注册：

```json
{"email":"student@example.edu","password":"password123","nickname":"小林","code":"123456"}
```

创建订单：

```json
{"productId":12,"requestKey":"550e8400-e29b-41d4-a716-446655440000"}
```

错误使用 RFC 9457 风格 `ProblemDetail`，包含 HTTP 状态、`title`、`detail` 和请求路径。常见状态：400 参数错误、401 未登录/令牌失效、403 越权、404 不存在、409 非法状态或重复请求、413 上传过大、415 文件类型不支持、429 频率/尝试次数超限、503 Redis/SMTP 不可用。

## 7. 数据库迁移

已有 MySQL 数据库不要重跑 `schema.sql`：

1. V1 基础：`db/mysql-v1-extra.sql`。
2. V2 订单：`db/mysql-v2-order.sql`。
3. V2 图片：`db/mysql-v2-images.sql`。

商品列表索引为 `(status, created_at, id)`；买家和卖家订单分别使用 `(buyer_id, created_at, id)`、`(seller_id, created_at, id)`；图片按 `(product_id, sort_order, id)` 查询。

## 8. 测试证据与限制

自动化测试覆盖：验证码错误/过期/重放、发送频控、真实 Redis + Mailpit SMTP、未登录和越权、图片上传与伪装文件、订单取消/完成/重复操作、事务回滚、禁用账号 JWT、同商品 6 线程并发下单最多成功 1 个。

当前并发测试使用真实 H2 数据库事务，不是 Mock；MySQL 建表迁移已提供，但还应在最终部署环境补一次 MySQL 并发压测和 `EXPLAIN ANALYZE` 记录。当前本地文件存储适合单机开发，不适合多实例部署；多实例需要对象存储。正式 SMTP 尚需真实服务商授权码。
