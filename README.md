# 時間軸助手（免 root）

一鍵開啟陸版／各家 Android 手機被藏起來的 **Google 地圖時間軸**。免 root，整合三種情境與雙執行模式於一支 App：

- **Shizuku 模式（推薦）**：用 adb shell 權限直接 `wm density` 改最小寬度、`am force-stop` 停止 Google 地圖與 Play 服務。**免無障礙，手機鎖著、熄屏也能執行。**
- **無障礙模式**：不需 Shizuku，App 自動操作設定頁完成同樣流程。**需螢幕解鎖、亮著才能跑。**
- **小米 HyperOS**：自動處理最小寬度輸入框（無障礙模式）。
- **開機保活**：用「寫入安全設定」每 15 分鐘檢查，OPPO／vivo 重開機後無障礙被系統關掉時自動重新開啟。
- **自動執行**：每次開機後、或每日定時自動跑一次。

## 原理

把顯示最小寬度暫時改成約 640 dp（平板版面）→ 強制停止 Google 地圖與 Google Play 服務 → 還原原本寬度。Play 服務以平板身分重啟後，地圖就會出現時間軸入口。

## 權限

- **Shizuku**（Shizuku 模式）：安裝 Shizuku App 並啟動服務，App 內授權一次。
- **寫入安全設定 WRITE_SECURE_SETTINGS**（無障礙模式的保活、免每次授權）：用 Shizuku 一鍵授予，或用電腦 `adb shell pm grant tw.local.timeline android.permission.WRITE_SECURE_SETTINGS`。
- **無障礙服務**（無障礙模式）。

## 編譯

推到 `main` 後 GitHub Actions 自動編譯，APK 在 **Releases**。簽章固定，可直接覆蓋更新。

## 授權

GPLv3（見 `LICENSE`）。© 2026 steve19860004。本程式不提供任何擔保，使用風險自負，請僅用於自己的裝置。
