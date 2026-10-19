-- 发布失败的事件按退避时间重试，避免持续失败的事件占满批次、饿死后续事件。
ALTER TABLE outbox_event ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN last_error VARCHAR(500);
