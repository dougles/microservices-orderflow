CREATE TABLE inbox_message (
  message_id   UUID         NOT NULL,
  consumer     VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (message_id, consumer)
);
