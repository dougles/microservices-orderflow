CREATE TABLE outbox_message (
  id               UUID PRIMARY KEY,
  destination_type VARCHAR(10)  NOT NULL CHECK (destination_type IN ('KAFKA','SQS')),
  destination      VARCHAR(200) NOT NULL,
  message_key      VARCHAR(200),
  message_type     VARCHAR(100) NOT NULL,
  payload          JSONB        NOT NULL,
  traceparent      VARCHAR(100),
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
  published_at     TIMESTAMPTZ,
  attempts         INT          NOT NULL DEFAULT 0,
  last_error       TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_message (created_at) WHERE published_at IS NULL;
