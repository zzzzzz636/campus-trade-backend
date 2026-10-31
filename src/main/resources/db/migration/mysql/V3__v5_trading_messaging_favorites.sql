ALTER TABLE trade_order
    ADD COLUMN product_title_snapshot VARCHAR(120) NULL AFTER seller_id,
    ADD COLUMN product_description_snapshot TEXT NULL AFTER product_title_snapshot,
    ADD COLUMN fulfillment_type VARCHAR(30) NULL AFTER status,
    ADD COLUMN buyer_note VARCHAR(500) NULL AFTER fulfillment_type,
    ADD COLUMN handoff_location VARCHAR(200) NULL AFTER buyer_note,
    ADD COLUMN recipient_name VARCHAR(80) NULL AFTER handoff_location,
    ADD COLUMN recipient_phone VARCHAR(30) NULL AFTER recipient_name,
    ADD COLUMN shipping_address VARCHAR(500) NULL AFTER recipient_phone,
    ADD COLUMN shipping_carrier VARCHAR(80) NULL AFTER shipping_address,
    ADD COLUMN tracking_number VARCHAR(100) NULL AFTER shipping_carrier,
    ADD COLUMN seller_decision_note VARCHAR(300) NULL AFTER tracking_number,
    ADD COLUMN expires_at TIMESTAMP NULL AFTER request_key,
    ADD COLUMN accepted_at TIMESTAMP NULL AFTER expires_at,
    ADD COLUMN rejected_at TIMESTAMP NULL AFTER accepted_at,
    ADD COLUMN shipped_at TIMESTAMP NULL AFTER rejected_at,
    ADD COLUMN completed_at TIMESTAMP NULL AFTER shipped_at,
    ADD COLUMN cancelled_at TIMESTAMP NULL AFTER completed_at,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER created_at;

UPDATE trade_order o
JOIN product p ON p.id = o.product_id
SET o.product_title_snapshot = p.title,
    o.product_description_snapshot = p.description,
    o.fulfillment_type = 'CAMPUS_HANDOFF',
    o.accepted_at = CASE WHEN o.status = 'PENDING_MEETUP' THEN o.created_at ELSE o.accepted_at END,
    o.completed_at = CASE WHEN o.status = 'COMPLETED' THEN o.created_at ELSE o.completed_at END,
    o.cancelled_at = CASE WHEN o.status = 'CANCELLED' THEN o.created_at ELSE o.cancelled_at END,
    o.status = CASE WHEN o.status = 'PENDING_MEETUP' THEN 'WAITING_HANDOFF' ELSE o.status END;

ALTER TABLE trade_order
    MODIFY COLUMN product_title_snapshot VARCHAR(120) NOT NULL,
    MODIFY COLUMN product_description_snapshot TEXT NOT NULL,
    MODIFY COLUMN fulfillment_type VARCHAR(30) NOT NULL;

CREATE INDEX idx_order_status_expires ON trade_order (status, expires_at, id);

CREATE TABLE order_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NOT NULL,
    note VARCHAR(300) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_event_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_order_event_actor FOREIGN KEY (actor_id) REFERENCES app_user(id),
    INDEX idx_order_event_order (order_id, created_at, id)
);

INSERT INTO order_event (order_id, actor_id, event_type, from_status, to_status, note, created_at)
SELECT id, NULL, 'V5_MIGRATED', NULL, status, '从 V4 历史订单迁移', created_at
FROM trade_order;

CREATE TABLE conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    context_type VARCHAR(20) NOT NULL,
    context_id BIGINT NOT NULL,
    participant_low_id BIGINT NOT NULL,
    participant_high_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_conversation_low FOREIGN KEY (participant_low_id) REFERENCES app_user(id),
    CONSTRAINT fk_conversation_high FOREIGN KEY (participant_high_id) REFERENCES app_user(id),
    CONSTRAINT uq_conversation_context UNIQUE (context_type, context_id, participant_low_id, participant_high_id),
    INDEX idx_conversation_low_updated (participant_low_id, updated_at, id),
    INDEX idx_conversation_high_updated (participant_high_id, updated_at, id)
);

CREATE TABLE private_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP NULL,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES app_user(id),
    INDEX idx_message_conversation_id (conversation_id, id),
    INDEX idx_message_unread (conversation_id, read_at, id)
);

CREATE TABLE product_favorite (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_favorite_product FOREIGN KEY (product_id) REFERENCES product(id),
    INDEX idx_favorite_product (product_id, created_at),
    INDEX idx_favorite_user_created (user_id, created_at, product_id)
);

CREATE TABLE app_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    reference_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP NULL,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    INDEX idx_notification_user_read (user_id, read_at, created_at, id)
);
