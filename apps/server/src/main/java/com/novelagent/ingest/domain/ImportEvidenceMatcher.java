package com.novelagent.ingest.domain;

import java.util.regex.Pattern;

/** Resolves presentation-only omissions back to a unique, contiguous raw-source quote. */
final class ImportEvidenceMatcher {
    private static final Pattern EMPHASIS_LINE = Pattern.compile("^( {0,3})(\\*{1,2}|_{1,2})(\\S(?:.*\\S)?)\\2([ \\t]*)$");

    private ImportEvidenceMatcher() { }

    record Match(String quote, int start) { }

    static Match resolve(ImportAnalysis.Evidence evidence, String fragment) {
        int first = fragment.indexOf(evidence.quote());
        if (first >= 0) {
            int start = fragment.indexOf(evidence.quote(), first + 1) < 0
                    ? first : ImportAnalysis.locate(evidence, fragment);
            return new Match(evidence.quote(), start);
        }
        var visible = project(fragment);
        String quote = evidence.quote().replace("\r\n", "\n");
        first = visible.text().indexOf(quote);
        if (first < 0) throw new IllegalArgumentException("原文证据不存在，必须使用本段连续逐字引文；保留段内Markdown标记，不改字、漏句或拼接");
        if (visible.text().indexOf(quote, first + 1) >= 0) {
            throw new IllegalArgumentException("排版差异对应多处原文，不能自动确定证据；请使用更长的连续逐字引文");
        }
        int start = visible.offsets()[first];
        int end = visible.offsets()[first + quote.length() - 1] + 1;
        return new Match(fragment.substring(start, end), start);
    }

    private record Projection(String text, int[] offsets) { }

    private static Projection project(String source) {
        boolean[] omitted = new boolean[source.length()];
        char fence = 0;
        int fenceLength = 0;
        for (int start = 0; start < source.length();) {
            int end = source.indexOf('\n', start);
            if (end < 0) end = source.length();
            int contentEnd = end > start && source.charAt(end - 1) == '\r' ? end - 1 : end;
            String line = source.substring(start, contentEnd);
            String trimmed = line.stripLeading();
            int run = 0;
            if (!trimmed.isEmpty() && (trimmed.charAt(0) == '`' || trimmed.charAt(0) == '~')) {
                while (run < trimmed.length() && trimmed.charAt(run) == trimmed.charAt(0)) run++;
            }
            if (run >= 3) {
                if (fence == 0) { fence = trimmed.charAt(0); fenceLength = run; }
                else if (fence == trimmed.charAt(0) && run >= fenceLength && trimmed.substring(run).isBlank()) fence = 0;
            } else if (fence == 0) {
                var match = EMPHASIS_LINE.matcher(line);
                if (match.matches() && !match.group(3).contains("*") && !match.group(3).contains("_")
                        && !match.group(3).contains("`")) {
                    // Only whole-line paired emphasis is supported; never strip arbitrary punctuation or prose.
                    for (int i = match.start(2); i < match.end(2); i++) omitted[start + i] = true;
                    for (int i = match.end(3); i < match.end(3) + match.group(2).length(); i++) omitted[start + i] = true;
                }
            }
            start = end + 1;
        }
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[source.length()];
        for (int i = 0; i < source.length(); i++) {
            if (omitted[i] || (source.charAt(i) == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n')) continue;
            offsets[text.length()] = i;
            text.append(source.charAt(i));
        }
        return new Projection(text.toString(), offsets);
    }
}
