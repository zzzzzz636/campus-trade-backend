CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    token_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(80) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE product (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    seller_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    condition_level VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    view_count BIGINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_seller FOREIGN KEY (seller_id) REFERENCES app_user(id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT chk_product_price CHECK (price >= 0)
);

CREATE INDEX idx_product_status_created_id ON product (status, created_at, id);

CREATE TABLE product_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE CASCADE,
    CONSTRAINT uq_product_image_order UNIQUE (product_id, sort_order)
);

CREATE INDEX idx_product_image_product ON product_image (product_id, sort_order, id);

CREATE TABLE trade_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    seller_id BIGINT NOT NULL,
    product_title_snapshot VARCHAR(120) NOT NULL,
    product_description_snapshot TEXT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    status VARCHAR(30) NOT NULL,
    fulfillment_type VARCHAR(30) NOT NULL,
    buyer_note VARCHAR(500),
    handoff_location VARCHAR(200),
    recipient_name VARCHAR(80),
    recipient_phone VARCHAR(30),
    shipping_address VARCHAR(500),
    shipping_carrier VARCHAR(80),
    tracking_number VARCHAR(100),
    seller_decision_note VARCHAR(300),
    request_key VARCHAR(64),
    expires_at TIMESTAMP,
    accepted_at TIMESTAMP,
    rejected_at TIMESTAMP,
    shipped_at TIMESTAMP,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT fk_order_buyer FOREIGN KEY (buyer_id) REFERENCES app_user(id),
    CONSTRAINT fk_order_seller FOREIGN KEY (seller_id) REFERENCES app_user(id)
);

CREATE INDEX idx_order_buyer_created ON trade_order (buyer_id, created_at, id);
CREATE INDEX idx_order_seller_created ON trade_order (seller_id, created_at, id);
CREATE INDEX idx_order_status_expires ON trade_order (status, expires_at, id);
CREATE UNIQUE INDEX uq_order_buyer_request ON trade_order (buyer_id, request_key);

CREATE TABLE order_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    actor_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    note VARCHAR(300),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_order_event_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_order_event_actor FOREIGN KEY (actor_id) REFERENCES app_user(id)
);

CREATE INDEX idx_order_event_order ON order_event (order_id, created_at, id);

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
    CONSTRAINT uq_conversation_context UNIQUE (context_type, context_id, participant_low_id, participant_high_id)
);

CREATE INDEX idx_conversation_low_updated ON conversation (participant_low_id, updated_at, id);
CREATE INDEX idx_conversation_high_updated ON conversation (participant_high_id, updated_at, id);

CREATE TABLE private_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    content VARCHAR(1000) NOT NULL,
    sent_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP,
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT fk_message_sender FOREIGN KEY (sender_id) REFERENCES app_user(id)
);

CREATE INDEX idx_message_conversation_id ON private_message (conversation_id, id);
CREATE INDEX idx_message_unread ON private_message (conversation_id, read_at, id);

CREATE TABLE product_favorite (
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_favorite_product FOREIGN KEY (product_id) REFERENCES product(id)
);

CREATE INDEX idx_favorite_product ON product_favorite (product_id, created_at);
CREATE INDEX idx_favorite_user_created ON product_favorite (user_id, created_at, product_id);

CREATE TABLE app_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    reference_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP,
    CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

CREATE INDEX idx_notification_user_read ON app_notification (user_id, read_at, created_at, id);

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
    published_at TIMESTAMP,
    CONSTRAINT fk_outbox_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_outbox_recipient FOREIGN KEY (recipient_id) REFERENCES app_user(id)
);
CREATE INDEX idx_outbox_due ON order_notification_outbox (status, next_attempt_at, id);
CREATE TABLE order_notification_consumed (
    event_id BIGINT PRIMARY KEY,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_consumed_event FOREIGN KEY (event_id) REFERENCES order_notification_outbox(id)
);

CREATE TABLE order_cancel_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(300) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    CONSTRAINT fk_cancel_request_order FOREIGN KEY (order_id) REFERENCES trade_order(id),
    CONSTRAINT fk_cancel_request_user FOREIGN KEY (requester_id) REFERENCES app_user(id)
);
CREATE INDEX idx_cancel_request_order_status ON order_cancel_request (order_id, status, id);

CREATE TABLE product_search_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    product_version INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    CONSTRAINT fk_product_search_event_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uq_product_search_version UNIQUE (product_id, product_version)
);
CREATE INDEX idx_product_search_due ON product_search_outbox (status, next_attempt_at, id);
CREATE TABLE product_search_consumed (
    event_id BIGINT PRIMARY KEY,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_search_consumed_event FOREIGN KEY (event_id) REFERENCES product_search_outbox(id)
);
