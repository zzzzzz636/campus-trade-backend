INSERT INTO app_user (id, email, password_hash, nickname) VALUES
    (1, 'demo@example.invalid', 'LOCAL_DEMO_ONLY', '演示卖家');
INSERT INTO category (id, name, sort_order) VALUES
    (1, '教材', 1), (2, '数码与电器', 2);
INSERT INTO product (id, seller_id, category_id, title, description, price, condition_level, status, created_at, updated_at) VALUES
    (1, 1, 1, '高等数学教材', '书页完整，适合课程学习。', 18.00, 'LIKE_NEW', 'ON_SALE', '2026-10-26 10:00:00', '2026-10-26 10:00:00'),
    (2, 1, 2, '二手台灯', '可正常使用，校内面交。', 35.00, 'GOOD', 'ON_SALE', '2026-10-27 10:00:00', '2026-10-27 10:00:00');
