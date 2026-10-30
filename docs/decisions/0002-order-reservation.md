# 0002：第一版选购与商品占用

日期：2026-10-27

买家通过 `POST /api/orders` 选择在售商品。Service 在同一个数据库事务中先读取商品，再执行 `UPDATE product SET status='RESERVED' WHERE id=? AND status='ON_SALE' AND seller_id<>?`。只有更新行数为 1 的请求才能插入 `trade_order`；插入失败时事务回滚，商品恢复原状态。订单保存选购时的价格快照。

卖家删除操作把 `ON_SALE` 改为 `OFF_SHELF`，保留商品及关联历史；买家不能选购自己的商品。第一版订单状态只有 `PENDING_MEETUP`。取消、完成确认、重复请求返回原订单的幂等语义以及订单状态审计会在后续交易阶段设计与测试。

候选索引包括商品公开列表 `(status, created_at, id)` 和订单 `(buyer_id, created_at, id)`、`(seller_id, created_at, id)`。数据量与查询场景形成后使用 `EXPLAIN` 验证。
