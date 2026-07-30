# API リファレンス

このページは、OsWL が公開するすべての REST エンドポイントの概要です。リクエスト／レスポンススキーマを含む対話的なドキュメントは、**`local` プロファイル**の **Swagger UI**（`http://localhost:8080/swagger-ui.html`）を使用してください。Swagger は **`prod` では無効**です。

---

## 認証

### セッション（Web UI）

ブラウザからのリクエストは Spring Security のクッキーセッションを使用します。`POST /login` でログインします。

### API キー（CLI）

CLI のエンドポイントには次が必要です:

```
Authorization: Bearer oswl_<api_key>
```

### CLI スキャン提出者の資格情報

`POST /api/scan` は追加で、提出者を認証するために JSON 本文に `submitterEmail` と `submitterPassword` を必要とします。取り込みに成功すると、専用のスキャン結果カラムではなく、**監査ログ**（`SCAN.INGEST`）に提出者のメールアドレスとともに記録されます。

---

## 認証エンドポイント

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/login` | ログインページ |
| `POST` | `/login` | 資格情報の送信 |
| `GET` | `/login/otp-verify` | OTP 確認ページ |
| `POST` | `/login/otp-verify` | OTP コードの送信 |
| `POST` | `/login/otp-resend` | OTP メールの再送信 |
| `GET` | `/setup` | セットアップウィザードページ（初回起動時のみ） |
| `POST` | `/setup` | セットアップウィザードフォームの送信 |

---

## プロジェクト

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects` | `PROJECT_VIEW` | プロジェクトダッシュボード |
| `GET` | `/projects/list` | `PROJECT_VIEW` | プロジェクト一覧（JSON） |
| `DELETE` | `/projects/{id}` | `PROJECT_DELETE` | プロジェクトのソフト削除 |
| `POST` | `/projects/{id}/restore` | `PROJECT_RESTORE` | ゴミ箱から復元 |
| `DELETE` | `/projects/{id}/permanent` | `PROJECT_PERMANENT_DELETE` | 完全削除 |
| `DELETE` | `/projects/trash/all` | `PROJECT_PERMANENT_DELETE` | ゴミ箱を空にする |
| `DELETE` | `/projects/trash/selected` | `PROJECT_PERMANENT_DELETE` | 選択したゴミ箱項目を削除 |
| `POST` | `/projects/trash/restore-selected` | `PROJECT_RESTORE` | 一括復元 |
| `GET` | `/projects/cards` | `PROJECT_VIEW` | プロジェクトカードの HTML フラグメント（ダッシュボード） |
| `GET` | `/projects/scan-status/stream?ids=` | `PROJECT_VIEW` | **SSE** — 一覧されたプロジェクトのスキャンが完了した際の `scan-update` |
| `POST` | `/projects` | `PROJECT_CREATE` | プロジェクトの作成（JSON） |
| `PATCH` | `/api/projects/{id}/deployment-profile` | `PROJECT_UPDATE` | CVE トリアージ用の AI デプロイメントプロファイルを設定（`{ "deploymentProfile" }`） |

---

## Quick Import

`PROJECT_CREATE`（またはシステム管理者）が必要です。セッション認証。

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/projects/quick-import` | Quick Import ページ |
| `GET` | `/api/quick-import/connections` | 現在のユーザーの VCS 接続一覧 |
| `GET` | `/api/quick-import/repos?provider=` | プロバイダーからのリポジトリ一覧（`GITHUB`、`GITLAB`、`BITBUCKET`） |
| `POST` | `/api/quick-import/start` | 新しいインポートジョブをキューに入れる（`{ "repoUrl", "branch" }` → `{ "jobId" }`） |
| `POST` | `/api/quick-import/job/{jobId}/cancel` | キュー中または実行中のジョブをキャンセル（不明または完了済みの場合は `404`） |
| `GET` | `/api/quick-import/jobs` | 現在のユーザーのすべてのジョブ一覧（キュー中、実行中、最近のもの） |
| `GET` | `/api/quick-import/job/{jobId}` | 1 件のジョブをポーリング（`QuickImportJobStatus`） |
| `GET` | `/api/quick-import/job/{jobId}/stream` | **SSE** — JSON ステータスを含む `job-update` イベント（フォールバック: ポーリング） |

**ジョブフェーズ:** `QUEUED` → `CLONING` → `PARSING` → `SCANNING` → `ENRICHING` → `DONE` | `FAILED`。
最大**3 件**のインポートが同時実行されます（`oswl.quick-import.max-concurrent`）。それ以降のジョブは FIFO キューで待機します（`queuePosition`）。
`ENRICHING` 中は、レスポンスに `percent`、`subPhase`（`CVE`、`LICENSE`、`INSIGHTS`）、`detailLines`、`aiPreviews`、および deps.dev のキャッシュ判定統計（`cacheTotal`、`cacheHit`、`cacheToFetch`）が含まれます。
別フィールド `aiStatus` はバックグラウンドの AI エンリッチメント状態（`NOT_APPLICABLE`、`PENDING`、`RUNNING`、`COMPLETED`、`FAILED`）を追跡します。ジョブが `DONE` になっても、`aiStatus` は `PENDING`/`RUNNING` のままになることがあります。

---

## GitHub OAuth / PAT

| Method | Path | 説明 |
|---|---|---|
| `POST` | `/api/github/connect` | GitHub PAT の接続 |
| `DELETE` | `/api/github/disconnect` | GitHub 接続の削除 |
| `GET` | `/api/github/status` | 接続状態 |
| `GET` | `/api/github/accounts` | 認証済みアカウントの一覧 |
| `GET` | `/api/github/repos` | アクセス可能なリポジトリの一覧 |
| `GET` | `/api/github/branches` | リポジトリのブランチ一覧 |
| `GET` | `/api/github/branches/by-project` | プロジェクトに紐づくリポジトリのブランチ一覧（`?projectId=` — パッチ適用モーダルで使用） |
| `GET` | `/api/github/branch-updated-at` | ブランチの最終コミット日時 |
| `DELETE` | `/api/github/accounts/{login}` | 特定のアカウントの削除 |

---

## CLI スキャン

| Method | Path | 認証 | 説明 |
|---|---|---|---|
| `POST` | `/api/auth` | API key | API キーの検証（レガシー） |
| `GET` | `/api/scan/ping` | API key | 疎通確認とキーの有効性確認 |
| `GET` | `/api/scan/manifest-rules` | API key | マニフェストファイルの収集ルール（`/scripts/manifest-rules.json` と同じ） |
| `POST` | `/api/scan/parse` | API key | マニフェスト zip アーカイブの解析（CLI ステップ 1） |
| `POST` | `/api/scan` | API key + 資格情報 | 依存関係スキャンの送信（CLI ステップ 2） |
| `GET` | `/api/scan/{scanId}/status` | セッション | スキャン状態のポーリング — `status`、`componentCount`、およびスキャン完了とは独立した AI エンリッチメント状態 `aiStatus` と `securityPostureInsight` を返します |
| `POST` | `/api/scan/gate` | API key | **v1.0.4** — PR / CI セキュリティゲート。`exitCode` を含む判定 |

---

## セキュリティセンター

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects/{id}/security-center` | `SECURITY_CENTER_VIEW` | セキュリティセンターページ |
| `PATCH` | `/projects/{id}/security-center/bulk-status` | `SECURITY_CENTER_UPDATE_STATUS` | CVE 状態の一括更新 |
| `GET` | `/projects/{id}/security-center/export` | `SECURITY_CENTER_EXPORT` | CVE 一覧を CSV としてダウンロード（`?scanId=`、`?format=csv`） |
| `POST` | `/projects/{id}/security-center/batch-pr` | `SECURITY_CENTER_UPDATE_STATUS` | **v1.0.4** — 選択したすべてのコンポーネントに対して 1 件のアップグレード PR を作成 |
| `GET` | `/security-center/compliance-report` | `SECURITY_CENTER_EXPORT` | **v1.0.4** — 印刷用のコンプライアンスレポート |

### SBOM / VEX / SARIF（v1.0.4）

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/projects/{projectId}/sbom` | `SECURITY_CENTER_EXPORT` | CycloneDX 1.6 SBOM（`application/vnd.cyclonedx+json`） |
| `GET` | `/api/projects/{projectId}/vex` | `SECURITY_CENTER_EXPORT` | トリアージ判断から構築された CycloneDX VEX |
| `GET` | `/api/projects/{projectId}/sarif` | `SECURITY_CENTER_EXPORT` | SARIF 2.1.0（`application/sarif+json`） |
| `POST` | `/api/sbom/import` | `PROJECT_CREATE` | サードパーティの CycloneDX ファイルをインポート（multipart） |

---

## コンポーネント詳細

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects/{id}/components/{compId}` | `COMPONENT_DETAIL_VIEW` | コンポーネント詳細（フルページまたは HTMX フラグメント） |
| `POST` | `/projects/{id}/components/{compId}/cves/{cveDbId}/ai-summarize` | `SECURITY_CENTER_UPDATE_STATUS` | 1 件の CVE の AI トリアージを再生成 |
| `POST` | `/projects/{id}/components/{compId}/defer` | `SECURITY_CENTER_UPDATE_STATUS` | 修正の保留を記録 |
| `POST` | `/projects/{id}/components/{compId}/create-pr` | `SECURITY_CENTER_UPDATE_STATUS` | 依存関係の修正を含む VCS プルリクエストを開く |
| `POST` | `/projects/{id}/components/{compId}/jira-ticket` | `SECURITY_CENTER_UPDATE_STATUS` | **v1.0.4** — この検出結果の Jira 課題を作成 |

---

## ライセンス

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects/{id}/license` | `LICENSE_VIEW` | ライセンス分析ページ |
| `POST` | `/projects/{id}/license/refresh-insights?scanId=` | `LICENSE_VIEW` | 1 件のスキャンの AI インサイトを再生成 |

---

## リスクトレンド

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects/{id}/risk-trend` | `RISK_TREND_VIEW` | リスクトレンドページ |

---

## バージョン比較

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects/{id}/version-diff` | `VERSION_DIFF_VIEW` | バージョン比較ページ |

---

## スキャン履歴

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/projects/{id}/scan-history` | `SCAN_HISTORY_VIEW` | スキャン履歴ページ |
| `DELETE` | `/projects/{id}/scan-history/{scanId}` | `PROJECT_DELETE` | スキャンレコードの削除 |

---

## プロジェクト API キー

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + プロジェクトメンバーシップ | プロジェクトキーの一覧 |
| `POST` | `/api/projects/{id}/keys` | `SETTINGS_CLI_KEY_MANAGE` + プロジェクトメンバーシップ | キーの作成 |
| `DELETE` | `/api/projects/{id}/keys/{keyId}` | `SETTINGS_CLI_KEY_MANAGE` + プロジェクトメンバーシップ | キーの失効 |

---

## 管理者

### ユーザー

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/api/admin/users` | 全ユーザーの一覧 |
| `POST` | `/api/admin/users` | ユーザーの作成／招待 |
| `PUT` | `/api/admin/users/{id}/roles` | ユーザーロールの更新 |
| `PUT` | `/api/admin/users/{id}/display-name` | 表示名の変更 |
| `PUT` | `/api/admin/users/{id}/activate` | アカウントの有効化 |
| `PUT` | `/api/admin/users/{id}/deactivate` | アカウントの無効化 |
| `DELETE` | `/api/admin/users/{id}` | ユーザーの削除 |

### ロールテンプレート

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/api/admin/role-templates` | テンプレートの一覧 |
| `POST` | `/api/admin/role-templates` | テンプレートの作成 |
| `GET` | `/api/admin/role-templates/permissions` | 利用可能なすべての権限の一覧 |
| `PUT` | `/api/admin/role-templates/{id}` | テンプレートの更新 |
| `DELETE` | `/api/admin/role-templates/{id}` | テンプレートの削除 |

### 監査ログ

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/api/admin/audit-logs` | ページネーションされた監査ログ |
| `GET` | `/api/admin/audit-logs/export.csv` | CSV としてエクスポート |
| `GET` | `/api/admin/audit-logs/export?format=jsonl\|cef` | **v1.0.4** — SIEM エクスポート（`AUDIT_LOG_EXPORT`） |

### 組織ダッシュボード（v1.0.4）

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/org-dashboard` | `ORG_DASHBOARD_VIEW` | ポートフォリオ全体の状態、ランキング、KEV・ライセンスの集計 |

### オフラインスナップショット（v1.0.4）

すべてのエンドポイントには `SYSTEM_ADMIN` ロール、または `SETTINGS_SNAPSHOT_MANAGE` 権限が必要です。

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/api/admin/snapshot` | ストア状態 — air-gapped フラグと、ソース（`osv`、`depsdev-version`、`depsdev-advisory`、`epss`、`kev`）ごとのレコード数、`importedAt`、v2 由来（`bundleId`、`builtAt`、`sourceAsOf`、`origin`；v1 またはメタ情報のないバンドルからインポートしたソースは null）を返します。また、すべてのソースの中で最も古い `sourceAsOf` である `oldestSourceAsOf` と、定義の鮮度バッジに使用される設定値 `stalenessWarnDays`、`stalenessCriticalDays` を含みます |
| `POST` | `/api/admin/snapshot/import` | スナップショットバンドルをインポート（JSONL ファイルの zip + `meta.json`）。`?mode=replace\|merge` でバンドル自身の `meta.json` モードをオーバーライドします：`replace`（デフォルト）は各ソースを書き込み前にクリアし、`merge` はキー単位で upsert し、`"_deleted":true` の tombstone を処理します。v2 チェックサムはストア変更前に検証され、不一致の場合はバンドル全体が拒否されます |
| `POST` | `/api/admin/snapshot/import-from-path` | サーバーディスク上にあるバンドルをインポート（`{ "path", "mode" }`） — ブラウザアップロードが非実用的な大きなバンドル用です。`oswl.airgapped.import-dir` が未設定の場合は 400；`path` はそのディレクトリ配下に解決される必要があります |
| `GET` | `/api/admin/snapshot/wanted-list` | このインスタンスの wanted-list を NDJSON（`application/x-ndjson`）でストリーミング — スキャン済みコンポーネントごとに `{"ecosystem","name","version"}` を 1 行ずつ、オンライン環境の `oswl-vdb build --wanted` 用です。プロジェクト名やリポジトリ URL はインスタンス外に出ません |
| `GET` | `/api/admin/snapshot/export` | このインスタンスが取得済みのデータから v2 スナップショットバンドル（`application/zip`）を構築してエクスポートします；`meta.json` の `origin` は `derived-from-scan` になります。オンラインインスタンスで実行し、air-gapped インスタンスでインポートしてください |

### モニタリング（v1.0.4）

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/actuator/health` | ヘルスチェック（管理者権限が必要） |
| `GET` | `/actuator/info` | ビルド／バージョン情報 |
| `GET` | `/actuator/prometheus` | Prometheus 用の Micrometer メトリクス |
| `POST` | `/projects/{projectId}/cve-alerts/acknowledge` | 新規 CVE アラートの確認（`SECURITY_CENTER_VIEW`） |

### 管理者用 CLI キー

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/api/admin/cli-keys` | グローバル CLI キーの一覧 |
| `POST` | `/api/admin/cli-keys` | グローバルキーの作成 |
| `PATCH` | `/api/admin/cli-keys/{keyId}/toggle` | キーの有効／無効切り替え |

---

## 設定

### セキュリティ（SMTP、2FA、パスワードポリシー）

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | セキュリティ設定の取得 |
| `PUT` | `/api/settings/security` | `SETTINGS_SECURITY_MANAGE` | 設定の更新 |
| `POST` | `/api/settings/security/mail/test` | `SETTINGS_SECURITY_MANAGE` | テストメールの送信 |

### AI

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | 現在有効なプロバイダーとエンリッチメントの設定（temperature、上限、既定のデプロイメントプロファイルなど） |
| `PUT` | `/api/settings/ai` | `SETTINGS_AI_MANAGE` | プロバイダーの認証情報・設定を作成／更新 |
| `PUT` | `/api/settings/ai/deactivate` | `SETTINGS_AI_MANAGE` | 有効なプロバイダーを無効化（設定本文は任意） |
| `PUT` | `/api/settings/ai/activate/{provider}` | `SETTINGS_AI_MANAGE` | 有効なプロバイダーの切り替え |
| `POST` | `/api/settings/ai/test-connection` | `SETTINGS_AI_MANAGE` | プロバイダー接続テスト（保存なし）。完了リクエストを送る代わりに利用可能なモデル一覧を取得するため、トークンを消費せず日次呼び出し上限にもカウントされない。設定されたモデル ID が返ってきた一覧にない場合、レスポンスに警告の `hint` が含まれる |
| `GET` | `/api/settings/ai/prompts` | `SETTINGS_AI_MANAGE` | 編集可能なプロンプトテンプレートと上書き設定 |
| `POST` | `/api/settings/ai/golden-test` | `SETTINGS_AI_MANAGE` | 組み込みのプロンプト回帰テストを実行 |
| `GET` | `/api/settings/ai/usage` | `SETTINGS_AI_MANAGE` | AI 使用統計 — 本日の呼び出し／トークン／推定コスト、日次上限、直近 7 日間（日次集計テーブルから取得） |
| `GET` | `/api/settings/ai/usage/events` | `SETTINGS_AI_MANAGE` | 最近の AI 呼び出しイベント、新しい順（`?page=`、`?size=`、既定サイズ `10`）。直近**100 件**のみ保持（FIFO）のため、最大でも 10 ページ |
| `GET` | `/api/settings/ai/embedded` | `SETTINGS_AI_MANAGE` | 内蔵 AI の状態（`running`、`external`、`binaryFound`、`activeModel`、`fallbackUsed`、`lastError`、`availableModels`、`modelsDir`、`baseUrl`。既定モデルのダウンロード中は `downloading`、`downloadedBytes`、`downloadTotalBytes` も） |
| `POST` | `/api/settings/ai/embedded/start?model=` | `SETTINGS_AI_MANAGE` | llama.cpp サイドカーの起動（モデルファイル名は任意。候補間の自動フォールバック、失敗時は理由付きで 400）。新規インストールで `.gguf` がまだない場合、代わりに Apache-2.0 の Qwen3-1.7B モデルをバックグラウンドでダウンロードし即座に応答（`downloading: true`）— 進捗は `GET .../embedded` でポーリング |
| `POST` | `/api/settings/ai/embedded/stop` | `SETTINGS_AI_MANAGE` | サイドカーの停止と LOCAL プロバイダーの無効化 |
| `PUT` | `/api/settings/ai/embedded/config` | `SETTINGS_AI_MANAGE` | フォルダ／モデルの上書き設定 `{ "dir", "model" }` を保存（null は現状維持、空文字はクリア。dir が欠けている場合は 400） |

プロバイダー: `OPENAI`、`ANTHROPIC`、`GEMINI`、`LOCAL`。内蔵エンドポイントは組み込みの llama.cpp LOCAL プロバイダーを管理します — [内蔵 AI](Embedded-AI.md)を参照してください。

### ライセンスポリシー

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/settings/license-policy` | `LICENSE_POLICY_MANAGE` | SPDX ポリシー項目の一覧 |
| `PUT` | `/api/settings/license-policy/{spdxId}` | `LICENSE_POLICY_MANAGE` | 1 つのライセンスの状態を更新 |

### VCS 接続

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/settings/vcs` | `SETTINGS_VCS_MANAGE` | 接続の一覧 |
| `POST` | `/api/settings/vcs` | `SETTINGS_VCS_MANAGE` | 接続の追加 |
| `DELETE` | `/api/settings/vcs/{id}` | `SETTINGS_VCS_MANAGE` | 接続の削除 |

### キャッシュ（エンリッチメントポリシー）

**deps.dev** と **OSV** からのライブラリ CVE／ライセンスデータをスキャン間でどれくらいの期間再利用するかを制御します。

| Method | Path | 権限 | 説明 |
|---|---|---|---|
| `GET` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | キャッシュキー（`DEPS_DEV`、`OSV_VULN`）の一覧、TTL と最終クリア時刻のメタデータ |
| `PUT` | `/api/settings/cache` | `SETTINGS_CACHE_MANAGE` | 1 つのキーの TTL を更新（`cacheKey`、`ttlSeconds`） |
| `POST` | `/api/settings/cache/clear?cacheKey=…` | `SETTINGS_CACHE_MANAGE` | キャッシュをクリア済みとしてマーク — その時刻より前に取得されたライブラリは次回のエンリッチメントで再取得される |

**TTL の意味**（設定 → キャッシュ UI は、エンリッチメントで使われる `DEPS_DEV` の TTL にマッピングされます）:

| UI モード | `ttlSeconds` | 動作 |
|---|---|---|
| 常にリフレッシュ | `1` | すべてのスキャンでライブラリを毎回再取得 |
| カスタム TTL | `N`（秒） | `libraries.fetched_at` が N 秒より古い場合に再取得 |
| 永続キャッシュ | 非常に大きな値（例: 50 年） | ライブラリを一度だけ取得 |

> **注:** かつての `/api/settings/external` エンドポイントと `external_api_settings` テーブルは削除されました。キャッシュは `/api/settings/cache` のみで管理されます。

---

## ローカル／テスト（local プロファイル専用）

| Method | Path | 説明 |
|---|---|---|
| `GET` | `/data/test` | DB をリセットし、豊富なテストデータを投入 |
| `GET` | `/data/test-api-key` | 利用可能なテスト用 API キーを取得 |
