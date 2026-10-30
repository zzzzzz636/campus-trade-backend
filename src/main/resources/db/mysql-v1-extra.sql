USE campus_trade;

INSERT IGNORE INTO category (id, name, sort_order) VALUES
    (1, '教材', 1), (2, '数码与电器', 2);

CREATE TABLE IF NOT EXISTS trade_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT fk_order_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
    CONSTRAINT fk_order_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
    INDEX idx_order_buyer_created (buyer_id, created_at, id),
    INDEX idx_order_seller_created (seller_id, created_at, id)
);
