package com.stock.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/**
 * Accepts only the JSON literals {@code true}/{@code false} (or {@code null}) for a boolean field —
 * unlike Jackson's default {@code Boolean} deserializer, which lenently coerces the JSON string
 * {@code "false"} and the number {@code 0} into {@code false}. Used for
 * {@link StrategySelectionDto#setRequireVolume(Boolean)}, whose wire contract
 * (specs/backend/strategy-scan.md, "requireVolume 帶非布林值...不得被寬鬆解讀為 false") requires those forms to
 * be rejected with a 400, not silently accepted.
 */
public class StrictBooleanDeserializer extends JsonDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw InvalidFormatException.from(p,
                "Expected a JSON boolean literal (true/false), not a " + token, p.getText(), Boolean.class);
    }
}
