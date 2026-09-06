<div align="center">

# 🦉 OsWL

**オープンソース・ソフトウェア・ウォッチリスト — SCA プラットフォーム**

すべてのソフトウェアコンポーネントの CVE 脆弱性とライセンスリスクを一元的に追跡。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-supported-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org/)

[English](README.md) | [한국어](README.ko.md) | **日本語**

</div>

---

## OsWL とは？

**OsWL**（Open-source Software Watchlist）は、OSS 依存関係のセキュリティ脆弱性（CVE）とライセンスリスクを追跡・管理する社内向け **SCA（Software Composition Analysis）** プラットフォームです。

ソフトウェアポートフォリオ全体を単一のダッシュボードで管理できます。Git リポジトリを連携して自動インポートするか、CLI 経由でスキャン結果を送信すれば、CVSS スコア順の脆弱性リスト、ライセンスコンプライアンス状況、時系列のリスク傾向、AI 生成インサイトをすぐに確認できます。

### 主な機能

| 機能 | 説明 |
|---|---|
| **セキュリティセンター** | CVSS スコア、深刻度ランキング、ステータス管理（Open / Suppressed / False Positive）を含む CVE 一覧 |
| **ライセンス分析** | 依存関係ごとの SPDX ライセンス検出とポリシー適用（Permitted / Caution / Restricted） |
| **リスク傾向** | 最大 10 回分のスキャンにわたる CVE 件数・ライセンス状況の推移チャート |
| **バージョン比較** | 2 つのスキャン結果を並べて比較 — 追加・削除・変更された依存関係を確認 |
| **Quick Import** | VCS 連携による GitHub / GitLab / Bitbucket からのワンクリックインポート |
| **CLI 連携** | プロジェクト単位の API キーを使った言語非依存のスキャン送信 REST API |
| **AI インサイト** | CVE 状況・ライセンスコンプライアンスに関する任意の LLM 生成サマリー |
| **ロールベースアクセス制御** | ロールテンプレート（Admin / Developer / Viewer）とプロジェクト単位のメンバーシップ |
| **監査ログ** | すべてのユーザー・システムイベントを記録する不変の監査ログ（CSV エクスポート対応） |
| **2FA / 信頼済みデバイス** | ブラウザ単位の信頼済みデバイス対応を含むメール OTP 二要素認証 |

---

## クイックスタート

### 前提条件

| ツール | バージョン |
|---|---|
| JDK | 25 以上 |
| Gradle Wrapper | 同梱（`./gradlew`） |
| PostgreSQL | 15 以上（本番環境） |
| （任意）Docker | PostgreSQL をローカルで実行する場合 |

### 1. クローン

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. ローカル実行（H2 ファイルモード）

```bash
./gradlew bootRun
# アプリケーションは http://localhost:8080 で起動します
```

デフォルトで `local` プロファイルが有効になります。組み込みの H2 データベース（`./oswl-db.mv.db`）を使用するため、外部データベースは不要です。

初回起動時は **セットアップウィザード** が `http://localhost:8080/setup` で自動的に開きます。
最初のシステム管理者アカウントを作成して完了させてください。

### 3. PostgreSQL で実行（本番プロファイル）

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=your_password
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

---

## ビルド

```bash
# フルビルド（Java + Tailwind CSS のコンパイル）
./gradlew build

# 本番用 JAR の検証（ローカル専用テストエンドポイントが含まれていないことを確認）
./gradlew verifyProdJar

# Tailwind CSS のみ再ビルド
./gradlew buildTailwindCss

# テスト実行
./gradlew test

# テストカバレッジレポート → build/reports/jacoco/test/html/index.html
./gradlew jacocoTestReport
```

> **補足:** 初回ビルド時に Tailwind CSS のスタンドアロン CLI バイナリ（約 7MB）が `build/tools/` にダウンロードされます。以降のビルドではキャッシュされたバイナリが使用されます。

---

## 設定リファレンス

すべての設定は環境変数または `application.yaml` のプロファイルで制御されます。

| 変数 | デフォルト | 説明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | 有効なプロファイル: `local` または `prod` |
| `OSWL_ENCRYPTION_KEY` | *(ローカル開発用のダミー値)* | 保存されるシークレット（VCS トークン）の暗号化キー。**`prod` では必須** — 設定がないとアプリは起動しません。`openssl rand -base64 32` で生成 |
| `DB_URL` | `jdbc:postgresql://localhost:5432/oswl` | PostgreSQL JDBC URL（prod プロファイル） |
| `DB_USERNAME` | `oswl` | データベースユーザー（prod プロファイル） |
| `DB_PASSWORD` | `oswl` | データベースパスワード（prod プロファイル） |
| `OSWL_CLONE_TEMP_DIR` | システム一時ディレクトリ | Quick Import 中の一時的な git クローン先ディレクトリ |
| `OSWL_GITHUB_API_BASE` | `https://api.github.com` | GitHub API のベース URL（GHES 利用時に上書き） |
| `OSWL_RISK_TREND_LIMIT` | `10` | リスク傾向チャートに表示する最大スキャン数 |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | 監査ログ API の 1 ページあたり最大レコード数 |
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | 監査ログレコードが自動削除されるまでの月数 |

---

## ローカル開発向け補足機能

### H2 コンソール

```
URL:  http://localhost:8080/h2-console
JDBC: jdbc:h2:file:./oswl-db
ユーザー: sa
パスワード: (空欄)
```

### OTP メール（local プロファイル）

`local` プロファイルは組み込みの **GreenMail** SMTP サーバーを起動します。実際のメールは送信されません。
OTP コードはサーバーログに表示されます:

```
*** OTP CODE: 123456 ***
```

### テストデータのシード

ログイン後、以下を呼び出してください:

```
GET http://localhost:8080/data/test
```

このエンドポイントは既存データを**すべて**リセットし、豊富なサンプルプロジェクト・スキャン・CVE・ライセンスデータでデータベースを再構成します。

---

## アーキテクチャ概要

```
ブラウザ / CLI
     │
     ▼
Spring MVC コントローラー  (薄いレイヤー — Service に委譲)
     │
     ▼
サービス層               (ビジネスロジック、トランザクション)
     │
   ┌─┴──────────────────┐
   ▼                    ▼
JPA リポジトリ       外部クライアント
(PostgreSQL / H2)    (OSV · deps.dev · VCS API)
```

**コアドメインモデル:**

```
Project
 └── ProjectVersion (ブランチ単位)
 └── ScanResult     (CLI / Quick Import のスキャン単位)
      └── ScanComponent
           └── DependencyPath

Library  (プロジェクト間で共有 — group:artifact@version)
 └── Cve
 └── LicensePolicyEntry
```

---

## API ドキュメント

インタラクティブな Swagger UI は **`local` プロファイル** でのみ `http://localhost:8080/swagger-ui.html` から利用できます。**`prod` では無効化**されています。

---

## ドキュメント

日本語ドキュメントは [`docs/ja/`](docs/ja/Home.md)、英語は [`docs/en/`](docs/en/Home.md)、韓国語は [`docs/ko/`](docs/ko/Home.md) にあります。[ドキュメント索引](docs/README.md)と [GitHub Wiki](https://github.com/SalkCoding/Oswl/wiki) は英語を既定の入口とし、Wiki は `main` への push 時に `docs/en/` から自動同期されます。

フォルダの役割: [`deploy/`](deploy/README.md) は Docker のビルド・実行設定と Grafana アセット、[`scripts/`](scripts/README.md) は開発・運用ツール、[`docs/`](docs/README.md) はガイド、[`landing/`](landing/index.html) は Pages の紹介サイトです。アプリと同梱設定は `src/`、GitHub 自動化は `.github/` にあります。

| ページ | 説明 |
|---|---|
| [ホーム](docs/ja/Home.md) | プラットフォーム概要とナビゲーションガイド |
| [v1.0.4 の新機能](docs/ja/Whats-New-v1.0.4.md) | リリースのハイライト — SBOM/VEX/SARIF エクスポート、CI/CD ゲート、継続的モニタリング、組織ダッシュボード、サプライチェーンヒューリスティック、エアギャップモード |
| [はじめに](docs/ja/Getting-Started.md) | インストール、セットアップウィザード、最初のプロジェクト |
| [ユーザーガイド](docs/ja/User-Guide.md) | ダッシュボードの日常的な使い方 |
| [Quick Import](docs/ja/Quick-Import.md) | GitHub / GitLab / Bitbucket からのプロジェクトインポート |
| [CLI 連携](docs/ja/CLI-Integration.md) | ビルドパイプラインからのスキャン送信 |
| [内蔵 AI](docs/ja/Embedded-AI.md) | クラウドアカウントや API キーなしでローカル LLM により CVE トリアージ・ライセンスインサイトを実行 |
| [セキュリティセンター](docs/ja/Security-Center.md) | 脆弱性（CVE）の管理 |
| [ライセンス分析](docs/ja/License-Analysis.md) | ライセンスコンプライアンスとポリシー管理 |
| [リスク傾向](docs/ja/Risk-Trend.md) | 過去のリスクチャートの読み方 |
| [バージョン比較](docs/ja/Version-Diff.md) | 2 つのスキャン結果の比較 |
| [スキャン履歴](docs/ja/Scan-History.md) | プロジェクトに送信されたすべてのスキャンを順に確認 |
| [管理](docs/ja/Administration.md) | ユーザー、ロール、監査ログ、セキュリティ設定 |
| [権限レイヤー](docs/ja/Authorization-Layers.md) | ロールテンプレート vs プロジェクトメンバーシップ |
| [プロジェクトアクセス制御](docs/ja/Project-Access-Control.md) | プロジェクト単位の権限判定に関する技術参考資料 |
| [本番デプロイ](docs/ja/Production-Deployment-Checklist.md) | 本番環境チェックリスト |
| [データベーススキーマ](docs/ja/Database-Schema.md) | `ddl-auto` 戦略と SQL マイグレーション |
| [スキャン API セキュリティ](docs/ja/Scan-Api-Security.md) | CLI スキャンの認証と監査ログ |
| [API リファレンス](docs/ja/API-Reference.md) | REST API エンドポイント概要 |
| [用語集](docs/ja/Glossary.md) | 用語と定義 |

---

## Authors

OsWL は **[SalkCoding](https://github.com/SalkCoding)** によって開発・保守されています。

| 開発者 | 役割 | GitHub |
|---|---|---|
| SalkCoding | プロジェクトリード兼主要メンテナー | [@SalkCoding](https://github.com/SalkCoding) |
| Tengball | デザイン・UI/UX | [@Tengball](https://github.com/Tengball) |

ご質問・フィードバック・協業のご相談は [GitHub Issues](https://github.com/SalkCoding/Oswl/issues) までお気軽にどうぞ。

---

## License

このプロジェクトは [MIT License](LICENSE) のもとで公開されています。
