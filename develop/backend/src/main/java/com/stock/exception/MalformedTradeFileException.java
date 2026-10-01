package com.stock.exception;

/**
 * data/real-trades.csv's header row is not exactly {@code stockId,buyDate,buyPrice,shares} (or the
 * file is not valid UTF-8) — specs/backend/real-trade.md, "格式錯誤的行". A server-side data problem,
 * not a caller mistake, so it maps to 500 and no write is ever attempted.
 */
public class MalformedTradeFileException extends RuntimeException {

    public MalformedTradeFileException(String message) {
        super(message);
    }
}
