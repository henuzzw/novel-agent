package com.novelagent.writing.domain;

public record ManuscriptLocalEditSelection(String text, int offset, int occurrence) {
    public static ManuscriptLocalEditSelection resolve(String body, String text, Integer offset, Integer occurrence) {
        if (body == null || text == null || text.isBlank() || text.length() > 12000
                || (offset == null && occurrence == null) || (offset != null && offset < 0)
                || (occurrence != null && occurrence < 1)) {
            throw new IllegalArgumentException("请选择精确原文并指定位置或第几处匹配");
        }
        int count = 0;
        for (int start = body.indexOf(text); start >= 0; start = body.indexOf(text, start + 1)) {
            count++;
            if ((offset == null || offset == start) && (occurrence == null || occurrence == count)) {
                int end = start + text.length();
                if (splitsSurrogate(body, start) || splitsSurrogate(body, end)) {
                    throw new IllegalArgumentException("选区不能拆开 Unicode 字符");
                }
                return new ManuscriptLocalEditSelection(text, start, count);
            }
        }
        throw new IllegalArgumentException("精确原文与指定选区不匹配");
    }

    public String replace(String body, String replacement) {
        if (replacement == null || replacement.length() > 24000 || replacement.equals(text)
                || offset < 0 || offset + text.length() > body.length() || !body.startsWith(text, offset)) {
            throw new IllegalArgumentException("替换文本无效或选区已变化");
        }
        String result = body.substring(0, offset) + replacement + body.substring(offset + text.length());
        if (result.isBlank()) throw new IllegalArgumentException("正文不能为空");
        return result;
    }

    private static boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }
}
