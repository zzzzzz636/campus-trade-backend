CREATE TABLE order_cancel_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    CONSTRAINT fk_cancel_request_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_cancel_request_user FOREIGN KEY (requester_id) REFERENCES app_user(id),
    INDEX idx_cancel_request_order_status (order_id, status, id)
);
