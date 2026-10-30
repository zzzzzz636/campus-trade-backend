-- 在已有 V1 数据库上只执行一次；先备份数据，不要重跑 schema.sql。
USE campus_trade;

ALTER TABLE trade_order ADD COLUMN request_key VARCHAR(64) NULL;
CREATE UNIQUE INDEX uq_order_buyer_request ON trade_order (buyer_id, request_key);

-- 旧订单的 request_key 为 NULL，MySQL 允许唯一索引中存在多条 NULL。
-- 新客户端为每次创建订单提供唯一请求键；旧客户端不传时保持兼容。
