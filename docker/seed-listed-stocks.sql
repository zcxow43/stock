-- Development seed data for the stock search page.
-- Safe to run repeatedly: existing rows are refreshed instead of duplicated.
SET NAMES utf8mb4;

INSERT INTO stock (stock_id, stock_name, market, is_active) VALUES
    ('1101', '台泥',       'TSE', 1),
    ('1102', '亞泥',       'TSE', 1),
    ('1216', '統一',       'TSE', 1),
    ('1301', '台塑',       'TSE', 1),
    ('1303', '南亞',       'TSE', 1),
    ('2002', '中鋼',       'TSE', 1),
    ('2207', '和泰車',     'TSE', 1),
    ('2303', '聯電',       'TSE', 1),
    ('2308', '台達電',     'TSE', 1),
    ('2317', '鴻海',       'TSE', 1),
    ('2327', '國巨',       'TSE', 1),
    ('2330', '台積電',     'TSE', 1),
    ('2345', '智邦',       'TSE', 1),
    ('2357', '華碩',       'TSE', 1),
    ('2379', '瑞昱',       'TSE', 1),
    ('2382', '廣達',       'TSE', 1),
    ('2395', '研華',       'TSE', 1),
    ('2412', '中華電',     'TSE', 1),
    ('2454', '聯發科',     'TSE', 1),
    ('2603', '長榮',       'TSE', 1),
    ('2609', '陽明',       'TSE', 1),
    ('2615', '萬海',       'TSE', 1),
    ('2881', '富邦金',     'TSE', 1),
    ('2882', '國泰金',     'TSE', 1),
    ('2886', '兆豐金',     'TSE', 1),
    ('2891', '中信金',     'TSE', 1),
    ('3008', '大立光',     'TSE', 1),
    ('3034', '聯詠',       'TSE', 1),
    ('3231', '緯創',       'TSE', 1),
    ('3661', '世芯-KY',    'TSE', 1),
    ('3711', '日月光投控', 'TSE', 1),
    ('4904', '遠傳',       'TSE', 1),
    ('6505', '台塑化',     'TSE', 1),
    ('6669', '緯穎',       'TSE', 1)
ON DUPLICATE KEY UPDATE
    stock_name = VALUES(stock_name),
    market = VALUES(market),
    is_active = VALUES(is_active);
