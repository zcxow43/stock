package com.stock.exception;

/**
 * RISING_SUPPORT's `confirmBars` is not an integer, or is neither `1` nor `2` — see
 * specs/backend/strategy-scan.md, "上漲支撐的確認長度可選（confirmBars）".
 */
public class InvalidConfirmBarsException extends RuntimeException {

    private final String strategy;

    public InvalidConfirmBarsException(String strategy) {
        super("Invalid confirmBars for strategy: " + strategy);
        this.strategy = strategy;
    }

    public String getStrategy() {
        return strategy;
    }
}
