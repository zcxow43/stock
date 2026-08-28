package com.stock.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizeUtilTest {

    @Test
    void parsesThousandsSeparatedPrice() {
        Optional<BigDecimal> value = NormalizeUtil.toBigDecimal("2,410.00");
        assertTrue(value.isPresent());
        assertEquals(0, new BigDecimal("2410.00").compareTo(value.get()));
    }

    @Test
    void parsesPlainPrice() {
        Optional<BigDecimal> value = NormalizeUtil.toBigDecimal("88.50");
        assertTrue(value.isPresent());
        assertEquals(0, new BigDecimal("88.50").compareTo(value.get()));
    }

    @Test
    void emptyOrPlaceholderPriceStringsAreAbsent() {
        assertFalse(NormalizeUtil.toBigDecimal("").isPresent());
        assertFalse(NormalizeUtil.toBigDecimal("  ").isPresent());
        assertFalse(NormalizeUtil.toBigDecimal("--").isPresent());
        assertFalse(NormalizeUtil.toBigDecimal((String) null).isPresent());
    }

    @Test
    void garbageStringIsAbsentNotException() {
        assertFalse(NormalizeUtil.toBigDecimal("N/A").isPresent());
    }

    @Test
    void parsesRocCompactDate() {
        // 民國115年08月27日 -> 2026-08-27
        assertEquals(LocalDate.of(2026, 8, 27), NormalizeUtil.toIsoDate("1150827"));
    }

    @Test
    void parsesRocSlashDate() {
        assertEquals(LocalDate.of(2026, 8, 27), NormalizeUtil.toIsoDate("115/08/27"));
    }

    @Test
    void parsesIsoDate() {
        assertEquals(LocalDate.of(2026, 8, 27), NormalizeUtil.toIsoDate("2026-08-27"));
    }

    @Test
    void parsesWesternCompactDate() {
        assertEquals(LocalDate.of(2026, 8, 27), NormalizeUtil.toIsoDate("20260827"));
    }

    @Test
    void parsesIsoSlashDate() {
        assertEquals(LocalDate.of(2026, 8, 27), NormalizeUtil.toIsoDate("2026/08/27"));
    }

    @Test
    void unrecognizedDateThrows() {
        assertThrows(IllegalArgumentException.class, () -> NormalizeUtil.toIsoDate("not-a-date"));
    }
}
