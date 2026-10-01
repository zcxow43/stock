package com.stock.dto;

/** A malformed data row of data/real-trades.csv that was skipped; {@code lineNumber} counts the header as line 1. */
public class SkippedLineDto {

    private final int lineNumber;
    private final String content;

    public SkippedLineDto(int lineNumber, String content) {
        this.lineNumber = lineNumber;
        this.content = content;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getContent() {
        return content;
    }
}
