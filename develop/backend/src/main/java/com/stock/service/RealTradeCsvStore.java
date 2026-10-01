package com.stock.service;

import com.stock.domain.RealTrade;
import com.stock.dto.SkippedLineDto;
import com.stock.exception.MalformedTradeFileException;
import com.stock.exception.RealTradeNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes {@code data/real-trades.csv} at the project root (specs/backend/real-trade.md,
 * "儲存：版控中的 CSV"). The file is hand-edited, so parsing reports bad rows individually instead of
 * failing the list, and every write is a whole-file rewrite that keeps the header, comments, blank
 * lines and unparseable rows exactly where they were, via a temp file plus atomic rename.
 *
 * <p>Location: {@code app.real-trade.csv-path} when set, otherwise {@code data/real-trades.csv}
 * under the nearest ancestor of the working directory that contains {@code develop/backend/pom.xml}
 * (the working directory itself depends on how the app was launched, e.g. {@code mvn -f
 * develop/backend/pom.xml spring-boot:run} runs inside {@code develop/backend}).
 *
 * <p>All access is serialized: a read-modify-write must not interleave with another one.
 */
@Component
public class RealTradeCsvStore {

    public static final String HEADER = "stockId,buyDate,buyPrice,shares";

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern PRICE = Pattern.compile("\\d+(\\.\\d{1,2})?");
    private static final Pattern SHARES = Pattern.compile("\\d+");

    private final Path path;

    public RealTradeCsvStore(@Value("${app.real-trade.csv-path:}") String configuredPath) {
        this.path = (configuredPath == null || configuredPath.trim().isEmpty())
                ? locateProjectRoot().resolve("data").resolve("real-trades.csv")
                : Paths.get(configuredPath.trim()).toAbsolutePath();
    }

    /** The valid positions (with their ids) and the rows that were skipped as malformed. */
    public static final class Snapshot {
        private final List<RealTrade> trades;
        private final List<SkippedLineDto> skippedLines;

        Snapshot(List<RealTrade> trades, List<SkippedLineDto> skippedLines) {
            this.trades = trades;
            this.skippedLines = skippedLines;
        }

        public List<RealTrade> getTrades() {
            return trades;
        }

        public List<SkippedLineDto> getSkippedLines() {
            return skippedLines;
        }
    }

    public Path getPath() {
        return path;
    }

    public synchronized Snapshot read() {
        return parse(readLines());
    }

    /** Appends one data row to the end of the file (creating it with its header if absent) and returns it as parsed. */
    public synchronized RealTrade append(String stockId, LocalDate buyDate, BigDecimal buyPrice, int shares) {
        List<String> lines = readLines();
        int idForNewRow = parse(lines).getTrades().size() + 1;
        BigDecimal price = buyPrice.setScale(2);
        lines.add(stockId + "," + buyDate + "," + price.toPlainString() + "," + shares);
        writeLines(lines);
        return new RealTrade(idForNewRow, lines.size() - 1, stockId, buyDate, price, shares);
    }

    /** Removes the data row whose id is {@code id}; every other line is written back untouched. */
    public synchronized void delete(Long id) {
        List<String> lines = readLines();
        Snapshot snapshot = parse(lines);
        if (id == null || id < 1 || id > snapshot.getTrades().size()) {
            throw new RealTradeNotFoundException(id);
        }
        RealTrade target = snapshot.getTrades().get((int) (id - 1));
        lines.remove(target.getLineIndex());
        writeLines(lines);
    }

    /** All raw lines, header first; a missing file counts as header-only. Header must match exactly. */
    private List<String> readLines() {
        List<String> lines;
        if (!Files.exists(path)) {
            lines = new ArrayList<>();
            lines.add(HEADER);
            return lines;
        }
        try {
            lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (CharacterCodingException e) {
            throw new MalformedTradeFileException("real-trades.csv is not valid UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        if (lines.isEmpty() || !HEADER.equals(lines.get(0).trim())) {
            throw new MalformedTradeFileException("real-trades.csv header must be exactly: " + HEADER);
        }
        return lines;
    }

    private Snapshot parse(List<String> lines) {
        LocalDate today = LocalDate.now(TAIPEI);
        List<RealTrade> trades = new ArrayList<>();
        List<SkippedLineDto> skipped = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            RealTrade trade = parseRow(line, trades.size() + 1, i, today);
            if (trade == null) {
                skipped.add(new SkippedLineDto(i + 1, raw));
            } else {
                trades.add(trade);
            }
        }
        return new Snapshot(trades, skipped);
    }

    /** Returns the position, or {@code null} when the row breaks any rule in the spec's table. */
    private RealTrade parseRow(String line, int id, int lineIndex, LocalDate today) {
        String[] cells = line.split(",", -1);
        if (cells.length != 4) {
            return null;
        }
        String stockId = cells[0].trim();
        String date = cells[1].trim();
        String price = cells[2].trim();
        String shares = cells[3].trim();
        if (stockId.isEmpty() || !PRICE.matcher(price).matches() || !SHARES.matcher(shares).matches()) {
            return null;
        }
        LocalDate buyDate;
        try {
            buyDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return null;
        }
        BigDecimal buyPrice = new BigDecimal(price);
        int shareCount;
        try {
            shareCount = Integer.parseInt(shares);
        } catch (NumberFormatException e) {
            return null;
        }
        if (buyDate.isAfter(today) || buyPrice.signum() <= 0 || shareCount <= 0) {
            return null;
        }
        return new RealTrade(id, lineIndex, stockId, buyDate, buyPrice.setScale(2), shareCount);
    }

    /** Whole-file rewrite: UTF-8 (no BOM), LF, via a sibling temp file renamed over the target. */
    private void writeLines(List<String> lines) {
        StringBuilder content = new StringBuilder();
        for (String line : lines) {
            content.append(line).append('\n');
        }
        Path dir = path.getParent();
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, ".real-trades-", ".tmp");
            Files.write(tmp, content.toString().getBytes(StandardCharsets.UTF_8));
            keepPermissions(tmp);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + path, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort: the move already consumed the temp file on success
                }
            }
        }
    }

    /** createTempFile makes a 0600 file; a tracked data file should keep its mode (default 0644 when new). */
    private void keepPermissions(Path tmp) throws IOException {
        try {
            Set<PosixFilePermission> perms = Files.exists(path)
                    ? Files.getPosixFilePermissions(path) : PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(tmp, perms);
        } catch (UnsupportedOperationException e) {
            // non-POSIX file system (e.g. Windows): nothing to preserve
        }
    }

    private static Path locateProjectRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("develop").resolve("backend").resolve("pom.xml"))) {
                return dir;
            }
        }
        return start;
    }
}
