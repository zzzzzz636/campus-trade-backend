CREATE TABLE order_notification_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_key VARCHAR(120) NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    CONSTRAINT fk_outbox_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_outbox_recipient FOREIGN KEY (recipient_id) REFERENCES app_user(id),
    INDEX idx_outbox_due (status, next_attempt_at, id)
);

CREATE TABLE order_notification_consumed (
    event_id BIGINT PRIMARY KEY,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_consumed_event FOREIGN KEY (event_id) REFERENCES order_notification_outbox(id)
);
