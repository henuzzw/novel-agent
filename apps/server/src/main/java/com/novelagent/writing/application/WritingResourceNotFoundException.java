package com.novelagent.writing.application;

import java.util.UUID;

public class WritingResourceNotFoundException extends RuntimeException {
    public WritingResourceNotFoundException(String type, UUID id) { super(type + "不存在：" + id); }
}
