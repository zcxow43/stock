package com.stock.service.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.Locale;

/**
 * Shared helper for classifying a block-class HTTP error response (spec: 到期時間的取得 /
 * 封鎖類回應的判定), reused by every {@link PriceHistorySource} so the parsing logic — and the
 * decision of what counts as "the response indicates a block" — lives in exactly one place.
 */
public final class RetryAfterExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RetryAfterExtractor() {
    }

    /**
     * Extracts the block's expiry in seconds: the standard {@code Retry-After} HTTP header first,
     * then a {@code retry_after} field in a JSON error body (FinMind's block response shape, e.g.
     * {@code {"msg":"ip banned","status":403,"retry_after":505,"token_tail":""}}). Returns null
     * when neither is present — the caller then applies the configured default cooldown; this
     * value is never hardcoded here (spec: 兩者皆不寫死在程式中).
     */
    public static Long extract(HttpStatusCodeException e) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers != null) {
            String headerValue = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (headerValue != null) {
                try {
                    return Long.parseLong(headerValue.trim());
                } catch (NumberFormatException ignored) {
                    // Retry-After may also be an HTTP-date form; not seen from either source in
                    // practice, so falling through to the body/default is acceptable here.
                }
            }
        }
        String body = safeBody(e);
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            JsonNode retryAfter = node.get("retry_after");
            if (retryAfter != null && retryAfter.isNumber()) {
                return retryAfter.asLong();
            }
        } catch (Exception ignored) {
            // Body isn't the expected JSON shape; no retry_after to extract.
        }
        return null;
    }

    /**
     * Whether the response body indicates a quota/ban condition even when the HTTP status alone
     * wouldn't be classified as blocking (spec: 或回應內容指出配額／封鎖，defensive fallback beyond the
     * documented HTTP 403/429 case).
     */
    public static boolean bodyIndicatesBlock(HttpStatusCodeException e) {
        String body = safeBody(e);
        if (body == null) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("ip banned") || lower.contains("ip_banned") || lower.contains("banned")
                || lower.contains("quota");
    }

    /**
     * The full block-class-response judgment shared by every {@link PriceHistorySource} (spec:
     * 封鎖類回應的判定): HTTP 403/429, or any other status whose body indicates quota/ban. Kept in one
     * place so the two sources can never silently drift on what counts as "blocked".
     */
    public static boolean isBlockedResponse(HttpStatusCodeException e) {
        return e.getStatusCode() == HttpStatus.FORBIDDEN
                || e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                || bodyIndicatesBlock(e);
    }

    private static String safeBody(HttpStatusCodeException e) {
        try {
            return e.getResponseBodyAsString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
