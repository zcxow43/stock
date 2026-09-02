package com.stock.service.external;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps TWSE's official listed-company industry classification code to its Chinese industry name.
 *
 * specs/backend/stock-universe-import.md describes the industry source's `產業別` field as already
 * being a Chinese name string (e.g. "半導體業"). In practice, TWSE's `t187ap03_L` opendata endpoint
 * returns a two-digit numeric classification code in that field instead (e.g. "24" for 2330/台積電,
 * confirmed against the live endpoint) — the field label is Chinese, its value is not. This table is
 * the standard, publicly documented TWSE 上市公司產業別 classification (stable for years; the same
 * codes are used by TWSE's own market-observation site and every major stock-data provider) and
 * exists solely to bridge that gap, so `industry.industry_name` ends up holding the Chinese name the
 * spec — and its acceptance criteria ("非亂碼、非數字代碼") — actually require, not a bare digit
 * string. See {@link com.stock.service.StockUniverseImportService#resolveIndustryName} for where a
 * non-numeric field value (should the source ever send the name directly, matching the spec's
 * original assumption) is trusted as-is instead of looked up here.
 */
public final class TwseIndustryCodeCatalog {

    private static final Map<String, String> CODE_TO_NAME = createMap();

    private static Map<String, String> createMap() {
        Map<String, String> map = new HashMap<>();
        map.put("01", "水泥工業");
        map.put("02", "食品工業");
        map.put("03", "塑膠工業");
        map.put("04", "紡織纖維");
        map.put("05", "電機機械");
        map.put("06", "電器電纜");
        map.put("08", "玻璃陶瓷");
        map.put("09", "造紙工業");
        map.put("10", "鋼鐵工業");
        map.put("11", "橡膠工業");
        map.put("12", "汽車工業");
        map.put("14", "建材營造業");
        map.put("15", "航運業");
        map.put("16", "觀光事業");
        map.put("17", "金融保險業");
        map.put("18", "貿易百貨業");
        map.put("19", "綜合");
        map.put("20", "其他業");
        map.put("21", "化學工業");
        map.put("22", "生技醫療業");
        map.put("23", "油電燃氣業");
        map.put("24", "半導體業");
        map.put("25", "電腦及週邊設備業");
        map.put("26", "光電業");
        map.put("27", "通信網路業");
        map.put("28", "電子零組件業");
        map.put("29", "電子通路業");
        map.put("30", "資訊服務業");
        map.put("31", "其他電子業");
        map.put("32", "文化創意業");
        map.put("33", "農業科技業");
        map.put("34", "電子商務");
        map.put("35", "綠能環保");
        map.put("36", "數位雲端");
        map.put("37", "運動休閒");
        map.put("38", "居家生活");
        map.put("80", "封閉式基金");
        map.put("91", "存託憑證");
        return Collections.unmodifiableMap(map);
    }

    private TwseIndustryCodeCatalog() {
    }

    /** Returns the Chinese industry name for a classification code, or {@code null} if unrecognized. */
    public static String nameOf(String code) {
        return CODE_TO_NAME.get(code);
    }
}
