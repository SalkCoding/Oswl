# プロジェクトアクセス制御（技術参考資料）

> **専門知識不要の概要はこちら:** [権限レイヤー](Authorization-Layers.md)では、ロールテンプレート・プロジェクトメンバーシップ・システム管理者の違いを説明しています。

## 概要

OsWL は**連携する 2 つのレイヤー**を使用します:

1. **グローバル権限**（**ロールテンプレート**上の `Permission`）— 例: `SCAN_VIEW`、`LICENSE_EXPORT`。
2. **プロジェクトメンバーシップ**（`project_members`）— ログイン中のユーザーが特定のプロジェクトにアクセスできるかどうか。

ユーザーは通常、該当する権限**と**プロジェクトメンバーシップの両方を必要とします。**システム管理者**はメンバーシップを迂回します。

## データモデル

| テーブル | 目的 |
|-------|---------|
| `project_members` | `user_id` を `project_id` に、ロール `ADMIN` または `MEMBER` とともに紐づける |

- **ADMIN**（メンバーシップ）— 作成時にプロジェクト作成者へ割り当てられる。
- **MEMBER**（メンバーシップ）— 他の行の既定値。機能の許可判定は引き続きグローバルな `Permission` 値を使用する。

`projects.created_by_user_id` はブートストラップに使われます: 起動時、作成者がいてメンバーがいないプロジェクトには、作成者がメンバーシップ **ADMIN** として追加されます。

## 適用

`ProjectAccessService` が唯一のエントリポイントです:

| メソッド | 用途 |
|--------|-----|
| `assertCanViewProject(projectId)` | プロジェクト単位の UI と読み書き API。拒否された場合は **403** |
| `assertCanSubmitScan(projectId, userId)` | API キー＋パスワード認証後の CLI スキャン取り込み |
| `accessibleProjectIds()` | システム管理者以外のユーザー向けにプロジェクト一覧とゴミ箱をフィルタリング |

## プロジェクト単位の画面（メンバーシップチェック）

これらはデータを返す前に `assertCanViewProject`（または同等のサービスチェック）を呼び出します:

| 領域 | 例 |
|------|----------|
| 分析 UI | セキュリティセンター、ライセンス（エクスポート含む）、コンポーネント詳細、バージョン比較、リスクトレンド、スキャン履歴 |
| API | `GET/POST /api/projects/{projectId}/keys`、`GET /api/vcs/branches?projectId=`、スキャン状態ポーリング |
| サービス | `ProjectService.getById`、`findAll`、アクセス可能な ID でフィルタリングされたゴミ箱操作 |

> **管理画面はプロジェクト単位ではありません。** オフラインスナップショット管理（`/api/admin/snapshot/*` — ステータス、バンドルのインポート／エクスポート、サーバーパスからのインポート(import-from-path)、wanted リスト）は `SETTINGS_SNAPSHOT_MANAGE` 権限または `SYSTEM_ADMIN` ロールで制御され、これらのエンドポイントでプロジェクトメンバーシップが参照されることはありません。

## CLI スキャン認証

`POST /api/scan` は次を要求します:

1. 有効なプロジェクト**API キー**（インターセプター）。
2. `SCAN_SUBMIT` を持つ提出者の**メールアドレス＋パスワード**。
3. 提出者がそのプロジェクトの**`project_members`**に登録されていること。

監査イベントには `SCAN.INGEST`、`SCAN.AUTH_FAILURE`、`SCAN.API_KEY_FAILURE`、`SCAN.AUTH_RATE_LIMITED` が含まれます。レート制限は `oswl.scan-api.*` で設定できます。

## 新規データベース vs アップグレード

- **新規インストール:** Hibernate の `ddl-auto`（またはお使いのスキーマツール）が `project_members` を作成し、作成者が自動的に追加されます。
- **既存のデータベース:** `project_members` テーブルが存在することを確認してから再起動し、`ProjectMemberBootstrapRunner` が必要に応じて作成者を遡って追加できるようにしてください。

## 関連ドキュメント

- [権限レイヤー](Authorization-Layers.md)
- [スキャン API セキュリティ](Scan-Api-Security.md)
- [CLI 連携](CLI-Integration.md)
