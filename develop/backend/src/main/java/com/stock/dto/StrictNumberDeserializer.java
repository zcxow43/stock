package com.stock.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Accepts only a JSON number (or {@code null}) for a numeric field — unlike Jackson's default
 * {@code BigDecimal} deserializer, which lenently coerces the string {@code "1000"}. Used by
 * {@link CreateRealTradeRequest}, whose wire contract requires a wrongly-typed value (e.g. a string
 * {@code shares}) to be 400 {@code INVALID_REQUEST_BODY}, while a well-typed but out-of-range one
 * (0, -1, 1.5) is the field-specific error. Exact decimal parsing, never {@code double}.
 */
public class StrictNumberDeserializer extends JsonDeserializer<BigDecimal> {

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getDecimalValue();
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw InvalidFormatException.from(p, "Expected a JSON number, not a " + token, p.getText(), BigDecimal.class);
    }
}
