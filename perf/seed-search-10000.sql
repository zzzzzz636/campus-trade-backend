-- ONLY run against an isolated, disposable performance database after Flyway migrations.
-- Generates exactly 10,000 synthetic ON_SALE products; never run on the user's business database.
CREATE TEMPORARY TABLE perf_digit0 (d INT PRIMARY KEY);
INSERT INTO perf_digit0 (d) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TEMPORARY TABLE perf_digit1 AS SELECT d FROM perf_digit0;
CREATE TEMPORARY TABLE perf_digit2 AS SELECT d FROM perf_digit0;
CREATE TEMPORARY TABLE perf_digit3 AS SELECT d FROM perf_digit0;

INSERT INTO app_user (email, password_hash, nickname, status, created_at)
VALUES ('campus-perf-seller@example.invalid', 'synthetic-no-login', '性能测试卖家', 'ACTIVE', CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id);
SET @perf_seller_id = LAST_INSERT_ID();

INSERT INTO product (seller_id, category_id, title, description, price, condition_level, status,
                     created_at, updated_at)
SELECT @perf_seller_id, 1 + MOD(n, 5),
       CONCAT(CASE WHEN MOD(n, 4) = 0 THEN '计算机教材 ' ELSE '校园闲置 ' END, 'PERF-', LPAD(n, 5, '0')),
       CONCAT('仅用于隔离环境搜索性能测试的合成商品，编号 ', n,
              CASE WHEN MOD(n, 4) = 0 THEN '，教材与课堂笔记。' ELSE '，宿舍生活用品。' END),
       10 + MOD(n * 13, 490), 'GOOD', 'ON_SALE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (
    SELECT d0.d + d1.d * 10 + d2.d * 100 + d3.d * 1000 AS n
    FROM perf_digit0 d0 CROSS JOIN perf_digit1 d1 CROSS JOIN perf_digit2 d2 CROSS JOIN perf_digit3 d3
) numbers;

SELECT COUNT(*) AS synthetic_products FROM product WHERE seller_id = @perf_seller_id;
DROP TEMPORARY TABLE perf_digit0;
DROP TEMPORARY TABLE perf_digit1;
DROP TEMPORARY TABLE perf_digit2;
DROP TEMPORARY TABLE perf_digit3;
