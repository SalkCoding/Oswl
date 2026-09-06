# OsWL ドキュメント

**OsWL**（Open-source Software Watchlist）のドキュメントハブへようこそ。

OsWL は、単一のマイクロサービスから製品ポートフォリオ全体まで、すべての OSS 依存関係の CVE 脆弱性とライセンスコンプライアンスをチームが一箇所で追跡できる社内向け **SCA（ソフトウェア構成分析）** プラットフォームです。

---

## 日本語ドキュメント

| ページ | 内容 |
|---|---|
| [v1.0.4 の新機能](Whats-New-v1.0.4.md) | SBOM / VEX / SARIF エクスポート、CI ゲート、継続的モニタリング、組織ダッシュボード、閉域網モード |
| [はじめに](Getting-Started.md) | システム要件、インストール、セットアップウィザード、初回ログイン |
| [ユーザーガイド](User-Guide.md) | プロジェクトダッシュボード、スキャンカード、ゴミ箱、フィルター |
| [Quick Import](Quick-Import.md) | GitHub / GitLab / Bitbucket の接続、ブランチのインポート |
| [CLI 連携](CLI-Integration.md) | API キー、スキャンペイロード形式、パイプライン連携 |
| [スキャン履歴](Scan-History.md) | プロジェクトごとのスキャン一覧 — ステータス、コンポーネント数、送信者 |
| [内蔵 AI](Embedded-AI.md) | ローカル Qwen3 LLM サイドカー（llama.cpp）— クラウドアカウント不要 |
| [セキュリティセンター](Security-Center.md) | CVE 一覧、深刻度の順位付け、状態更新、一括操作 |
| [ライセンス分析](License-Analysis.md) | SPDX 検出、ポリシー項目、リスクバッジ |
| [リスクトレンド](Risk-Trend.md) | 履歴グラフ、AI インサイト、スキャン上限 |
| [バージョン比較](Version-Diff.md) | 2 回のスキャンを並べて比較 |
| [管理](Administration.md) | ユーザー管理、ロール、監査ログ、セキュリティ・SMTP 設定 |
| [権限レイヤー](Authorization-Layers.md) | ロールテンプレート vs プロジェクトメンバーシップ |
| [プロジェクトアクセス制御](Project-Access-Control.md) | ACL の技術参考資料 |
| [運用デプロイチェックリスト](Production-Deployment-Checklist.md) | `prod` プロファイルでの公開前チェック |
| [データベーススキーマ](Database-Schema.md) | `ddl-auto` の方針と `db/` マイグレーション |
| [スキャン API セキュリティ](Scan-Api-Security.md) | CLI スキャン送信の保護概要 |
| [API リファレンス](API-Reference.md) | REST エンドポイントの全一覧 |
| [用語集](Glossary.md) | OsWL のすべての用語の定義 |

製品 UI 自体は日本語に完全対応しています（上部バーの言語切り替え、または `?lang=ja`）。他言語のドキュメントは[英語](../en/Home.md)と[韓国語](../ko/Home.md)でも利用できます。

---

## 主要な概念

```
┌──────────────────────────────────────────────────────────────┐
│  Project (プロジェクト)                                       │
│   ├─ ProjectVersion  (ブランチのスナップショット)              │
│   └─ ScanResult      (CLI / Quick Import の 1 回のスキャン)    │
│        └─ ScanComponent → Library → CVE / License            │
└──────────────────────────────────────────────────────────────┘
```

* **Project** は最上位の単位で、通常はリポジトリ 1 つに対応します。
* **Scan** はある時点の依存関係ツリー全体を記録します。
* **Library** は全体で共有されるレコード（名前 + バージョン + エコシステム）で、CVE 情報は一度拡充されてすべてのプロジェクトから再利用されます。
* **CVE** は deps.dev と OSV から取得し（OSV が提供する場合は CWE も含む）、ライセンス情報は deps.dev から取得します。閉域網モードでは、インポートした脆弱性スナップショットから同じデータを取得します。

---

## 基本的なワークフロー

1. **プロジェクトを登録** → Quick Import（VCS）または CLI からのプッシュ
2. **スキャンを実行** → インポート時に自動、または `POST /api/scan`
3. **確認** → CVE はセキュリティセンター、ポリシー違反はライセンスタブ
4. **推移を追跡** → AI 要約付きのリスク傾向グラフ
5. **運用** → CVE ステータスの更新、ライセンスポリシーの調整、レポートのエクスポート

---

## サポート

* **Swagger UI**（local プロファイルのみ）: `http://localhost:8080/swagger-ui.html`
* **H2 コンソール**（local プロファイルのみ）: `http://localhost:8080/h2-console`
* **課題報告**: [GitHub Issues](https://github.com/SalkCoding/Oswl/issues)

本番環境では API ドキュメントと H2 コンソールは無効化されます。このドキュメントと[API リファレンス](API-Reference.md)を利用してください。
