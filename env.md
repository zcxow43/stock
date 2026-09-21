# Develop

## Frontend
- Framework: React
- Language: TypeScript
- Build: Vite

## Backend
- Language: Java
- Framework: Spring Boot
- Build: Maven

# Container

## Database
- Use Env: true
- Engine: MySQL 8.x
- Host: 127.0.0.1
- Port: 3306
- Database: stock
- Username: app
- Password: 1234

`Use Env: true` 表示**實際連線改用專案外層 env 檔裡的 `db *` 設定**（遠端），上面的 Host／Port／Database／Username／Password 只作為預設與文件保留，本檔不因此被改寫。改成 `false` 或移除該行，就完全回到上面這組本機連線。外層檔的格式、key 名稱與安全規則見 `.claude/rules/external-env.md`。

# External

## Fugle API
- Key: 標記於此，**值不寫在本檔**——由專案外層的 env 檔以 `fugle api key` 提供（見 `.claude/rules/external-env.md`）
- Used by: backend 分 K（30 天以前的分 K 需要它；未設定時該範圍回「未設定富果 API Key」）
- Injected as: `FUGLE_API_KEY`，由 `/start` 在啟動當下注入該行程的環境變數
