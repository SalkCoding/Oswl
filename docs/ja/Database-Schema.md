# データベーススキーマとマイグレーション

OsWL はすべてのアプリケーションデータを PostgreSQL（`prod`）または H2 ファイルモード（`local`）に保存します。`domain/entity/` 配下の JPA エンティティが、実際のスキーマの**信頼できる情報源**です。

---

## プロファイルごとの挙動

| プロファイル | `ddl-auto` | 意味 |
|---------|------------|---------|
| `local` | `update` | エンティティが変更されると H2 スキーマが自動で調整される |
| `prod` | `validate` | PostgreSQL がエンティティと一致しない場合は起動失敗 — **自動マイグレーションなし** |
| `test` | `create-drop` | テスト実行ごとのインメモリスキーマ |

本番データベースをアップグレードする際は、新バージョンでアプリを再起動する**前**に `src/main/resources/db/` の SQL スクリプトを適用してください。

### Flyway（v1.0.4、オプトイン）

`OSWL_FLYWAY_ENABLED=true` にすると、スキーマ管理が Flyway に委ねられます（`baseline-on-migrate` が有効なため、既存のデータベースは拒否されずベースライン処理されます）。有効化する前に、現在のスキーマに一致するベースラインを生成してください。既定値は `false` で、上記の `ddl-auto` の挙動が維持されます。

### v1.0.4 で追加されたカラム

| テーブル | カラム | 型 |
|---|---|---|
| `libraries` | `malicious` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `typosquat_risk` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `description` | `text` — deps.dev からのアップストリームプロジェクトの説明文。コンポーネント詳細に表示 |
| `libraries` | `homepage` | `varchar(500)` — プロジェクトのホームページ URL、nullable |
| `libraries` | `source_repo_url` | `varchar(500)` — ソースリポジトリの URL、nullable |
| `scan_results` | `ai_locale` | `varchar(16)` — スキャンを開始した担当者のロケール。AI Insight がその言語で応答するために使用 |
| `ai_usage_events` | `branch` | `varchar(160)` — AI 使用量のブランチ帰属 |

2 つの boolean カラムには特に SQL の既定値が設定されています。これにより `ddl-auto=update` がデータの入ったテーブルにも追加できます。既定値がないと、既存の行がある状態での `NOT NULL` カラム追加は失敗します。`description`／`homepage`／`source_repo_url` はエンリッチメント時（OpenSSF Scorecard を取得する際にすでに呼び出している同じ deps.dev プロジェクト API を使って）遅延的に埋められ、カラム追加より前にスキャンされたコンポーネントについては次回スキャンまで `null` のままです — Flyway 経路は `V3__component_metadata.sql` がカバーします。

---

## 手動マイグレーションスクリプト

| ファイル | 目的 |
|------|---------|
| `project_members.sql` | プロジェクト単位の ACL 用に `project_members` を作成 |
| `instance_setup_lock.sql` | セットアップウィザードのロックテーブル |
| `ai_enhancement.sql` | AI プリファレンスのカラム、`ai_daily_usage` テーブル |
| `schema_cleanup.sql` | **一度限り**のクリーンアップ: 未使用のテーブル／カラムを削除（下記参照） |

任意の標準クライアント（`psql`、DBeaver、CI マイグレーションジョブ）で PostgreSQL に対して実行してください。スクリプトは可能な限り `IF EXISTS` / `IF NOT EXISTS` を使用します。

### `schema_cleanup.sql`（アップグレード時の注意）

レガシースキーマを削除したリリースに移行する際に**一度だけ**実行してください:

| 削除されたもの | 理由 |
|---------|--------|
| `ai_feedback` テーブル | JPA や UI に一度も接続されていなかった |
| `external_api_settings` テーブル | `cache_settings` のみに置き換えられた |
| `api_keys.created_by_user_id` | 未使用。発行は監査ログ（`CLI_KEY.CREATE`）で追跡 |
| `scan_results.raw_payload`、`submitted_by_user_id` | 未使用。提出者は監査ログ（`SCAN.INGEST`）に記録 |
| `project_versions.imported_at`、`last_updated_at` | 未使用のタイムスタンプ |
| `projects.updated_at`、`version`、`last_scanned_at` | 非正規化されたフィールド。UI は代わりに最新の `scan_results` を読む |

[運用デプロイチェックリスト](Production-Deployment-Checklist.md) §8 を参照してください。

---

## コアテーブル（概要）

```
projects
 ├── project_versions
 ├── project_members
 ├── scan_results
 │    └── scan_components → libraries (global)
 │         └── dependency_paths
 └── api_keys

libraries (shared)
 ├── library_cves  (CVE link + severity, CWE, AI fields)
 └── license data via enrichment

users, role_templates, audit_logs, cache_settings, vcs_connections, …
```

- **プロジェクトカードのバージョン／最終スキャン** — `projects.version` ではなく、最新の `scan_results` 行から導出されます。
- **エンリッチメントキャッシュ** — `cache_settings`（設定 → キャッシュ）。OSV/deps.dev の再取得 TTL を制御します。
- **CWE** — OSV の `database_specific.cwe_ids` から `library_cves` に保存されます。

---

## ローカルのリセット

アプリを停止し、`oswl-db.mv.db`（および関連する H2 ファイル）を削除して再起動すると → 空の DB とセットアップウィザードになります。`local` では手動 SQL は不要です。

---

## 関連ドキュメント

- [運用デプロイチェックリスト](Production-Deployment-Checklist.md)
- [管理](Administration.md) — キャッシュ設定
- [スキャン API セキュリティ](Scan-Api-Security.md) — 監査ベースの提出者追跡
