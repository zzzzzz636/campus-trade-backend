# CampusTrade V3 第二阶段记录：商品检索与编辑

完成日期：2026-10-30

## 1. 本阶段完成

- 商品广场支持关键词、分类、最低价、最高价组合筛选。
- 支持最新发布、价格升序、价格降序三种排序。
- 商品列表、详情和个人发布响应增加分类名称及创建/更新时间。
- 卖家可以编辑自己的在售商品；未登录、其他用户及非在售状态均不能编辑。
- 前端增加筛选控件、发布时间展示，并复用发布表单完成编辑交互。
- 商品详情和个人中心均提供编辑入口。
- OpenAPI 版本更新为 V3，异常响应补充查询参数类型和非法 JSON 处理。

## 2. 接口契约

查询示例：

```http
GET /api/products?page=1&size=12&q=教材&categoryId=1&minPrice=10&maxPrice=100&sort=PRICE_ASC
```

`categoryId`、`minPrice`、`maxPrice` 均可省略。`sort` 只接受：

- `NEWEST`：发布时间倒序，ID 作为稳定的第二排序条件。
- `PRICE_ASC`：价格升序，同价时按发布时间和 ID 倒序。
- `PRICE_DESC`：价格降序，同价时按发布时间和 ID 倒序。

列表项新增字段示例：

```json
{
  "id": 12,
  "categoryId": 2,
  "categoryName": "数码与电器",
  "title": "宿舍台灯",
  "price": 20.00,
  "condition": "GOOD",
  "sellerNickname": "校园同学",
  "coverImageUrl": null,
  "createdAt": "2026-10-30T15:20:00"
}
```

编辑商品：

```http
PUT /api/products/12
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "categoryId": 2,
  "title": "宿舍台灯，亮度可调",
  "description": "功能正常，东区宿舍线下面交",
  "price": 18.50,
  "conditionLevel": "GOOD"
}
```

成功返回更新后的商品详情。主要错误：未登录 `401`、不是卖家 `403`、商品不存在 `404`、商品已预订/售出/下架 `409`、字段或筛选参数错误 `400`。

## 3. 关键技术设计

排序没有使用 `${sort}` 将请求值直接拼进 SQL。Controller 将字符串交给 `ProductSort` 白名单解析，Mapper 再使用固定 `<choose>` 分支选择 `ORDER BY`，从源头避免排序字段 SQL 注入。

编辑 SQL 同时检查 `id`、`seller_id` 和 `status='ON_SALE'`。业务层先给出清晰的 404/403/409，再以条件更新处理检查后状态发生变化的竞态。

新增编辑后，订单创建改为先用 `SELECT ... FOR UPDATE` 锁定商品行，再读取标题和价格并预订。这样编辑与下单不会交错产生“订单保存旧价格、商品已经是新价格”的不一致快照。锁只覆盖被购买的单件商品，不会锁整张表。

现阶段保留已有 `(status, created_at, id)` 索引，它直接服务默认最新列表。分类与价格的复合索引必须结合代表性数据和 `EXPLAIN ANALYZE` 决定；本阶段没有为了字段数量机械增加索引。

## 4. 自动化验证

- `ProductV3IntegrationTests`：组合筛选、价格排序、分类与时间字段、未登录编辑、越权编辑、正常编辑、预订后禁止编辑和非法参数。
- `MySqlOrderConcurrencyIntegrationTests`：在 MySQL 8.4 中验证动态查询 SQL、商品更新、订单读取更新后价格，以及原有并发购买和事务回滚。
- `node --check src/main/resources/static/app.js`：前端脚本语法检查。

实际结果：

- 普通 `mvnw test`：发现 26 项，23 项执行并通过，3 项 MySQL 专项因未传开关而按设计跳过；0 失败、0 错误，`BUILD SUCCESS`。
- MySQL 8.4 专项：3 项执行并通过，0 失败、0 错误、0 跳过，`BUILD SUCCESS`。
- JavaScript：`node --check` 通过。
- Windows 上 Testcontainers 退出后仍可能等待约 30 秒再由 Surefire 结束 fork JVM；这会增加耗时，但本次退出码与构建结果均成功。

## 5. 已知限制与下一步

- 暂未做浏览器端自动化测试，当前前端由脚本语法检查、接口集成测试和人工页面验收组合保证。
- `%关键词%` 搜索仍不能有效利用普通 B-Tree 索引，暂不引入 Elasticsearch。
- 浏览量、收藏和重新上架尚未实现。
- 下一阶段进入 AI 辅助发布：独立 `ai` 模块、结构化响应、超时/限流和失败降级；没有 API Key 时手工发布必须继续可用。
