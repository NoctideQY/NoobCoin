# NoobCoin（菜币）

NoobCoin 是一个面向加密货币新手的本地 Android 资产助手。应用中文名为“菜币”，英文产品名和 GitHub 仓库名为 `NoobCoin`。

## 当前能力

- Binance 公共行情与真实 K 线
- 只读 Binance 账户资产同步，不提供交易、提现或转账
- 自选币与本地研究笔记
- CoinGecko 币种资料缓存
- DeepSeek / Kimi 持仓分析
- 本地保存设置与 API 凭据（使用 Android Keystore 加密）

## 安全说明

请勿把 Binance Secret、AI API Key、签名文件或本地配置提交到仓库。应用只需要 Binance 只读权限，不会执行交易。

## 构建

使用 Android Studio 打开项目，或运行：

```powershell
.\gradlew.bat :app:assembleDebug --offline --console=plain
```
