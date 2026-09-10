# データベーススキーマとマイグレーション

OsWL はすべてのアプリケーションデータを PostgreSQL（`prod`）または H2 ファイルモード（`local`）に保存します。`domain/entity/`, `auth/entity/` 配下の JPA エンティティが、実際のスキーマの**信頼できる情報源**です。

---

## プロファイルごとの挙動

| プロファイル | `ddl-auto` | 意味 |
|---------|------------|---------|
| `local` | `update` | エンティティが変更されると H2 スキーマが自動で調整される |
| `prod` | `validate` | PostgreSQL がエンティティと一致しない場合は起動失敗 — **自動マイグレーションなし** |
| `test` | `create-drop` | テスト実行ごとのインメモリスキーマ |

本番データベースをアップグレードする際は、新バージョンでアプリを再起動する**前**に `src/main/resources/db/` の SQL スクリプトを適用してください。

### Flyway（v1.0.4、オプトイン）

`OSWL_FLYWAY_ENABLED=true` で `src/main/resources/db/migration/` のバージョン別マイグレーションを有効にします。既定値は `false` です。リポジトリには `V1__baseline.sql` と後続のマイグレーションが含まれています。空の PostgreSQL DB では V1 から順に実行し、その後 Hibernate がスキーマを検証します。Flyway 履歴のない既存 DB では、`baseline-on-migrate` が V1 を実行せずにバージョン 1 を記録し、V2 以降を実行します。有効化前にバックアップを取得し、既存スキーマとマイグレーションを比較してください。手動適用済みの変更と後続マイグレーションが競合する場合があります。共有 DB に適用済みのファイルを再生成・変更しないでください。SQL を手動管理する場合は、対象バージョンに必要な変更をすべて順番に適用します。以下の一部の旧スクリプトだけでは新規インストール用のスキーマを構成できません。

現在のスキーマには v1.0.4 以降の変更も含まれます。組織・チーム（V11）、SAML/SCIM（V13）、Webhook（V14）、CVE の出所と C/C++ メタデータ（V15–V16）、ポリシー継承・例外（V17、V28）、到達可能性と根拠（V18、V29–V30）、監査ログの整合性（V19）、UI 設定・オンボーディング（V20、V23–V24）、シークレット・IaC 検出（V21）、スキャンのアーカイブ（V22）、レポートのブランド設定（V25）、キャッシュ集計・無効化（V26–V27）、永続化されたインポートジョブ（V31）です。完全な適用順序は実際のマイグレーションファイルで確認してください。一部のマイグレーションは再実行できないため、無条件に繰り返さないでください。

### v1.0.4 で追加されたカラム

| テーブル | カラム | 型 |
|---|---|---|
| `libraries` | `malicious` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `typosquat_risk` | `boolean NOT NULL DEFAULT false` |
| `libraries` | `description` | `text` — deps.dev からのアップストリームプロジェクトの説明文。コンポーネント詳細に表示 |
| `libraries` | `homepage` | `varchar(500)` — プロジェクトのホームページ URL、nullable |
| `libraries` | `source_repo_url` | `varchar(500)` — ソースリポジトリの URL、nullable |
| `scan_results` | `ai_locale` | `varchar(16)` — スキャンを開始した担当者のロケール。AI Insight がその言語で応答するために使用 |
| `scan_results` | `ai_status` | `varchar(20)` — AI エンリッチメントの進行状態（`NOT_APPLICABLE`/`PENDING`/`RUNNING`/`COMPLETED`/`FAILED`）。スキャンの `status` とは別に追跡し、データパイプラインが終わり次第スキャンを完了させる |
| `ai_usage_events` | `branch` | `varchar(160)` — AI 使用量のブランチ帰属 |
| `libraries` | `version_meta_fetched_at` | `timestamp` — deps.dev バージョンメタデータの最終更新日時。キャッシュヒットしたライブラリは `oswl.cache.version-meta-ttl-seconds`（既定 24 時間）以内なら GetVersion 呼び出しをスキップ |
| `libraries` | `license_expression_raw` | `text` — deps.dev が返した結合前のライセンス一覧（JSON）。オフラインスナップショットのエクスポートでマルチライセンスパッケージをそのまま往復させるために保持 |
| `libraries` | `ai_license_context_hash` | `varchar(64)` — AI ライセンス要約プロンプトを駆動するフィールドの SHA-256。再スキャンでハッシュが同じなら AI 呼び出しをスキップ |
| `library_cves` | `ai_context_hash` | `varchar(64)` — CVE ごとの AI 要約に対する同様のコンテキストハッシュ応答キャッシュ |

2 つの boolean カラムには特に SQL の既定値が設定されています。これにより `ddl-auto=update` がデータの入ったテーブルにも追加できます。既定値がないと、既存の行がある状態での `NOT NULL` カラム追加は失敗します。`description`／`homepage`／`source_repo_url` はエンリッチメント時（OpenSSF Scorecard を取得する際にすでに呼び出している同じ deps.dev プロジェクト API を使って）遅延的に埋められ、カラム追加より前にスキャンされたコンポーネントについては次回スキャンまで `null` のままです。その他の新規カラムはすべて nullable で冪等（`ADD COLUMN IF NOT EXISTS`）に追加されます。ハッシュ／ステータスが `null` の場合はキャッシュミスまたは `NOT_APPLICABLE` として扱われ、次回スキャンで埋められます。Flyway 経路は `V3__component_metadata.sql` から `V7__snapshot_v2_and_license_raw.sql` までがカバーします。

### v1.0.4 で追加されたテーブル

| テーブル | 目的 |
|---|---|
| `airgapped_snapshot_entries` | エアギャップ環境向けオフラインスナップショットストア — `(source, entry_key)` ごとに 1 行、JSON ペイロードを保持。ソース: `osv`、`depsdev-version`、`depsdev-advisory`、`epss`、`kev`、および `unresolved`（wanted-list に含まれていたが `oswl-vdb` ビルダーがアップストリームで解決できなかったコンポーネント） |
| `airgapped_snapshot_meta` | ソースごとの管理情報: `record_count`、`imported_at` に加えて v2 バンドルのプロビナンス — `bundle_id`、`built_at`、`source_as_of`（鮮度 UI の基準となるアップストリームデータ自体の基準日）、`origin`、`format_version`（null = v1 バンドル） |

`cve_alerts` と `jira_settings` も v1.0.4 で追加されています（`V2__v104_features.sql`）。カラム構成はマイグレーションスクリプトを参照してください。`airgapped_snapshot_*` テーブルは後述の `airgapped_snapshot.sql` で作成され、`V7` で拡張されます。

---

## 手動マイグレーションスクリプト

| ファイル | 目的 |
|------|---------|
| `project_members.sql` | プロジェクト単位の ACL 用に `project_members` を作成 |
| `instance_setup_lock.sql` | セットアップウィザードのロックテーブル |
| `ai_enhancement.sql` | AI プリファレンスのカラム、`ai_daily_usage` テーブル |
| `airgapped_snapshot.sql` | エアギャップ環境向けオフラインスナップショットストア（`airgapped_snapshot_entries`、`airgapped_snapshot_meta`） |
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

[運用デプロイチェックリスト](Production-Deployment-Checklist.md) §9 を参照してください。

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

airgapped_snapshot_entries ── airgapped_snapshot_meta  (オフラインスナップショットストア)

users, role_templates, audit_logs, cache_settings, user_vcs_connections, …
```

- **プロジェクトカードのバージョン／最終スキャン** — `projects.version` ではなく、最新の `scan_results` 行から導出されます。
- **エンリッチメントキャッシュ** — `cache_settings`（設定 → キャッシュ）。OSV/deps.dev の再取得 TTL を制御します。
- **CWE** — OSV の `database_specific.cwe_ids` から `library_cves` に保存されます。
- **オフラインスナップショットの鮮度** — `imported_at` ではなく `airgapped_snapshot_meta.source_as_of`（アップストリームの基準日）が基準です。

---

## ローカルのリセット

アプリを停止し、`oswl-db.mv.db`（および関連する H2 ファイル）を削除して再起動すると → 空の DB とセットアップウィザードになります。`local` では手動 SQL は不要です。

---

## 関連ドキュメント

- [運用デプロイチェックリスト](Production-Deployment-Checklist.md)
- [管理](Administration.md) — キャッシュ設定
- [スキャン API セキュリティ](Scan-Api-Security.md) — 監査ベースの提出者追跡
