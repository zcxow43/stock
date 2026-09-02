package com.stock.exception;

import java.math.BigDecimal;

/** Thrown when GET /api/momentum/gain's `minGain` param is outside -100 ~ 1000. */
public class InvalidMinGainException extends RuntimeException {

    public InvalidMinGainException(BigDecimal minGain) {
        super("Invalid minGain: " + minGain);
    }
}
