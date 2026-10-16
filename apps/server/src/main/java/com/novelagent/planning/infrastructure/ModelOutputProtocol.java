package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/** One contract controls instructions, provider parameters, decoding and request observability. */
interface ModelOutputProtocol {
    String instructions();
    String decode(String raw);
    default JsonNode wireSchema() { return NullNode.getInstance(); }
    default String snapshotName(String schemaName) { return "PLAIN_TEXT"; }
    default String preparePrompt(String prompt) { return PlainTextOutputProtocol.withoutLegacyProtocol(prompt); }
}
