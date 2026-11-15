CREATE TABLE product_search_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    product_version INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    CONSTRAINT fk_product_search_event_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uq_product_search_version UNIQUE (product_id, product_version),
    INDEX idx_product_search_due (status, next_attempt_at, id)
);

CREATE TABLE product_search_consumed (
    event_id BIGINT PRIMARY KEY,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_search_consumed_event FOREIGN KEY (event_id) REFERENCES product_search_outbox(id)
);
