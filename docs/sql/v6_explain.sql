-- 在有代表性测试数据的 MySQL 8.4 环境执行；先替换下面的用户与会话 ID。
USE campus_trade;
SELECT COUNT(*) AS product_rows FROM product;
SELECT COUNT(*) AS order_rows FROM trade_order;
SELECT COUNT(*) AS message_rows FROM private_message;

SET @buyer_id = 1;
SET @conversation_id = 1;

EXPLAIN ANALYZE
SELECT p.id, p.title, p.price, p.created_at
FROM product p
WHERE p.status = 'ON_SALE'
ORDER BY p.created_at DESC, p.id DESC
LIMIT 20 OFFSET 0;

EXPLAIN ANALYZE
SELECT p.id, p.title, p.price
FROM product p
WHERE p.status = 'ON_SALE'
  AND (LOWER(p.title) LIKE '%教材%' OR LOWER(p.description) LIKE '%教材%')
ORDER BY p.created_at DESC, p.id DESC
LIMIT 20 OFFSET 0;

EXPLAIN ANALYZE
SELECT id, status, created_at FROM trade_order
WHERE buyer_id = @buyer_id AND status = 'COMPLETED'
ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0;

EXPLAIN ANALYZE
SELECT id, sender_id, content FROM private_message
WHERE conversation_id = @conversation_id AND id < 1000000
ORDER BY id DESC LIMIT 30;
