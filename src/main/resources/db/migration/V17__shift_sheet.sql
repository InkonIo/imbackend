-- один лист на точку на день (утро + вечер)
CREATE TABLE shift_sheet (
    id         BIGSERIAL PRIMARY KEY,
    outlet_id  BIGINT      NOT NULL REFERENCES outlet (id),
    sheet_date DATE        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (outlet_id, sheet_date)
);

-- каждая клетка листа = отдельная строка (утро и вечер не затирают друг друга)
CREATE TABLE shift_sheet_value (
    id         BIGSERIAL PRIMARY KEY,
    sheet_id   BIGINT        NOT NULL REFERENCES shift_sheet (id) ON DELETE CASCADE,
    field_key  VARCHAR(100)  NOT NULL,
    value      VARCHAR(2000),
    updated_by BIGINT,
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (sheet_id, field_key)
);