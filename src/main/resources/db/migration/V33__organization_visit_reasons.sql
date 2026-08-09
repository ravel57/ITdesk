CREATE TABLE IF NOT EXISTS organization_visit_reason
(
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    urgent       BOOLEAN      NOT NULL DEFAULT FALSE,
    order_number INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_organization_visit_reason_name UNIQUE (name)
);

INSERT INTO organization_visit_reason (name, urgent, order_number)
VALUES ('Плановый выезд', FALSE, 0),
       ('Срочный выезд', TRUE, 1),
       ('Регламентные работы', FALSE, 2),
       ('Аварийный выезд', TRUE, 3)
ON CONFLICT (name) DO NOTHING;
