package com.stock.service;

import com.stock.dto.BackfillResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Internal pairing of a backfill batch's immediate response (the 202 shape returned by the
 * HTTP endpoint) with a future signaling when the batch has actually finished running.
 *
 * Never returned from a controller: {@link BackfillResponse} alone is the HTTP-facing shape,
 * and a CompletableFuture field must never be exposed to Jackson. This type exists purely so an
 * in-process caller — the startup catch-up runner — can chain work (the indicator rebuild) onto
 * the point the whole batch actually completes, not onto the point it was merely scheduled.
 */
public class BackfillOutcome {

    private final BackfillResponse response;
    private final CompletableFuture<Void> completion;

    public BackfillOutcome(BackfillResponse response, CompletableFuture<Void> completion) {
        this.response = response;
        this.completion = completion;
    }

    public BackfillResponse getResponse() {
        return response;
    }

    public CompletableFuture<Void> getCompletion() {
        return completion;
    }
}
