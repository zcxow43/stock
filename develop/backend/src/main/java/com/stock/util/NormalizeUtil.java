package com.stock.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes raw values returned by external data sources (TWSE / FinMind) into
 * clean {@link BigDecimal} / {@link LocalDate} values.
 *
 * External quirks handled:
 * - Thousands-separator formatted numeric strings, e.g. "1,234.00"
 * - Empty / "--" / "X" placeholders used by TWSE for no-trade rows -> absent
 * - ROC (Minguo) calendar dates, both compact "1150827" and slashed "115/08/27"
 * - Plain ISO dates "2026-08-27" and compact western dates "20260827"
 */
public final class NormalizeUtil {

    private static final Pattern ROC_COMPACT = Pattern.compile("^(\\d{2,3})(\\d{2})(\\d{2})$");
    private static final Pattern ROC_SLASH = Pattern.compile("^(\\d{2,3})/(\\d{1,2})/(\\d{1,2})$");
    private static final Pattern ISO_SLASH = Pattern.compile("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$");
    private static final DateTimeFormatter ISO_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private NormalizeUtil() {
    }

    /**
     * Parses a raw price/quantity string into a BigDecimal, stripping thousands separators.
     * Returns empty when the source indicates "no data" (blank, "--", "X", "N/A").
     */
    public static Optional<BigDecimal> toBigDecimal(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim().replace(",", "");
        if (trimmed.isEmpty() || trimmed.equals("--") || trimmed.equalsIgnoreCase("X") || trimmed.equalsIgnoreCase("N/A")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(trimmed));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<BigDecimal> toBigDecimal(Number raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Optional.of(new BigDecimal(raw.toString()));
    }

    /**
     * Parses a raw date string that may be in ROC (Minguo) or western calendar,
     * in compact ("1150827"/"20260827") or slashed ("115/08/27"/"2026/08/27") or ISO
     * ("2026-08-27") form, into a western {@link LocalDate}.
     */
    public static LocalDate toIsoDate(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("date string must not be null");
        }
        String trimmed = raw.trim();

        if (trimmed.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return LocalDate.parse(trimmed);
        }

        Matcher isoSlash = ISO_SLASH.matcher(trimmed);
        if (isoSlash.matches()) {
            return LocalDate.of(
                    Integer.parseInt(isoSlash.group(1)),
                    Integer.parseInt(isoSlash.group(2)),
                    Integer.parseInt(isoSlash.group(3)));
        }

        Matcher rocSlash = ROC_SLASH.matcher(trimmed);
        if (rocSlash.matches()) {
            return rocToLocalDate(rocSlash.group(1), rocSlash.group(2), rocSlash.group(3));
        }

        if (trimmed.matches("^\\d{8}$")) {
            // Compact 8-digit western date, e.g. 20260827
            return LocalDate.parse(trimmed, ISO_COMPACT);
        }

        Matcher rocCompact = ROC_COMPACT.matcher(trimmed);
        if (rocCompact.matches()) {
            // Compact ROC date, e.g. 1150827 (民國115年08月27日)
            return rocToLocalDate(rocCompact.group(1), rocCompact.group(2), rocCompact.group(3));
        }

        throw new IllegalArgumentException("Unrecognized date format: " + raw);
    }

    private static LocalDate rocToLocalDate(String rocYear, String month, String day) {
        int westernYear = Integer.parseInt(rocYear) + 1911;
        return LocalDate.of(westernYear, Integer.parseInt(month), Integer.parseInt(day));
    }
}
