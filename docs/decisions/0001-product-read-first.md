# 0001: 商品读取接口先行

日期：2026-10-27

## 决定

- 保留现有单 Maven 模块、Spring Boot 4.1.2 和 Java 17 编译目标；暂不更换整个工程。新增代码按业务域组织在 `product` 包内。
- 固定 `GET /api/products?page=1&size=10` 与 `GET /api/products/{id}` 的请求和响应契约。Controller 处理 HTTP 参数，Service 负责读取用例，Mapper 执行 SQL。
- 商品读取已使用 MyBatis。默认 `local` 配置使用 H2 内存数据库及两条种子数据，便于零凭据运行；`mysql` 配置指向 MySQL 8，需要环境变量和预先建表。H2 数据会在应用退出后消失。
- 商品写接口需要卖家身份校验；在用户认证落地前只实现公开读取，避免形成无鉴权的发布与修改入口。

## 第一阶段数据模型草案

- `user`：`id`、`email`（唯一）、`password_hash`、`nickname`、`status`、`created_at`。存密码哈希，不存明文。
- `category`：`id`、`name`、`sort_order`。商品以 `category_id` 关联分类。
- `product`：`id`、`seller_id`、`category_id`、`title`、`description`、`price DECIMAL(10,2)`、`condition_level`、`status`、`view_count`、`version`、`created_at`、`updated_at`。金额不用浮点数；状态先考虑 `DRAFT`、`ON_SALE`、`OFF_SHELF`，交易阶段再补状态流转。
- `product_image`：`id`、`product_id`、`url`、`sort_order`。一件商品可对应多张图；初期存图片地址。

列表查询只返回 `ON_SALE` 商品，按 `created_at DESC, id DESC` 稳定排序。预计先建 `(status, created_at, id)` 复合索引；分类筛选和其他索引在真实 SQL、测试数据及 `EXPLAIN` 后确定。

## 后续验证

目前已用 H2 种子数据验证列表、详情、空列表、非法页码与 404。下一步使用 MySQL 验证同一 SQL 与中文字符；完成商品下架后，再验证其从公开列表消失。
