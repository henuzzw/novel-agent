package com.novelagent.writing.domain;

import java.util.List;

public record ManuscriptContent(String title, String body, String summary, List<String> continuityNotes) {
}
