CREATE TABLE inventory_product (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL UNIQUE,
    pack_text  VARCHAR(200),
    case_qty   DOUBLE PRECISION,
    sleeve_qty DOUBLE PRECISION,
    unit       VARCHAR(5)   NOT NULL DEFAULT 'шт',
    active     BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE inventory_list (
    id       BIGSERIAL PRIMARY KEY,
    code     VARCHAR(20)  NOT NULL UNIQUE,
    title    VARCHAR(150) NOT NULL,
    weekdays VARCHAR(20)  NOT NULL,          -- '1,2,3,4,5,6' (1 = пн … 7 = вс)
    active   BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE inventory_list_item (
    id         BIGSERIAL PRIMARY KEY,
    list_id    BIGINT       NOT NULL REFERENCES inventory_list (id) ON DELETE CASCADE,
    product_id BIGINT       NOT NULL REFERENCES inventory_product (id),
    zone       VARCHAR(150) NOT NULL,
    sort_order INTEGER      NOT NULL
);

CREATE TABLE inventory_count (
    id           BIGSERIAL PRIMARY KEY,
    list_id      BIGINT      NOT NULL REFERENCES inventory_list (id),
    outlet_id    BIGINT      NOT NULL REFERENCES outlet (id),
    count_date   DATE        NOT NULL,
    user_id      BIGINT      NOT NULL REFERENCES app_user (id),
    shift_id     BIGINT      REFERENCES shift_session (id) ON DELETE SET NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at TIMESTAMPTZ,
    UNIQUE (outlet_id, list_id, count_date)
);
CREATE INDEX ix_inv_count_outlet_date ON inventory_count (outlet_id, count_date DESC);

CREATE TABLE inventory_line (
    id         BIGSERIAL PRIMARY KEY,
    count_id   BIGINT       NOT NULL REFERENCES inventory_count (id) ON DELETE CASCADE,
    product_id BIGINT       NOT NULL REFERENCES inventory_product (id),
    zone       VARCHAR(150) NOT NULL,
    sort_order INTEGER      NOT NULL,
    cs         DOUBLE PRECISION,
    slv        DOUBLE PRECISION,
    ea         DOUBLE PRECISION,
    none_flag  BOOLEAN      NOT NULL DEFAULT FALSE,
    total      DOUBLE PRECISION,
    prev_cs    DOUBLE PRECISION,
    prev_slv   DOUBLE PRECISION,
    prev_ea    DOUBLE PRECISION,
    prev_total DOUBLE PRECISION,
    prev_date  DATE,
    confirmed  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ
);
CREATE INDEX ix_inv_line_count ON inventory_line (count_id);

-- пункт маршрута, который нельзя закрыть без сданной инвентаризации
ALTER TABLE checklist_item     ADD COLUMN action VARCHAR(30);
ALTER TABLE checklist_run_item ADD COLUMN action VARCHAR(30);
UPDATE checklist_item SET action = 'INVENTORY' WHERE title = 'Ежедневная инвентаризация';