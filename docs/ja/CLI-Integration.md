# CLI 連携

OsWL は、Web ブラウザや VCS 接続を使わずにローカルマシンや CI パイプラインから依存関係スキャンを送信するための、公式 CLI（`oswl`）と REST API を提供します。

---

## クイックスタート（公式 CLI）

### 1. インストール

**Mac / Linux**

```bash
curl -fsSL https://<your-server>/scripts/install.sh | bash
```

**Windows (PowerShell)**

```powershell
iex ((New-Object System.Net.WebClient).DownloadString('https://<your-server>/scripts/install.ps1'))
```

**前提条件**

| プラットフォーム | ツール |
|---|---|
| Mac / Linux | `curl`, `jq`, `zip` |
| Windows | PowerShell 5.1 以降, `curl.exe` |

### 2. API キーを保存（任意）

```bash
oswl auth --key oswl_<your_api_key> --server https://<your-server>
```

### 3. プロジェクトをスキャン

```bash
cd /your/project
oswl scan -k oswl_<your_api_key> -u you@company.com --server https://<your-server>
```

- `-u`（メールアドレス）は**必須**です。
- `-p`（パスワード）は任意です — 省略すると**対話的に入力を求められます**。
- `project_dir` を省略すると現在のディレクトリが使われます。

**CI/CD の例**

```bash
export OSWL_API_KEY=oswl_xxx
export OSWL_USERNAME=ci@company.com
export OSWL_PASSWORD=secret
export OSWL_SERVER_URL=https://sca.company.com
cd /your/project && oswl scan
```

### CLI が行うこと（ユーザーに見える範囲）

```
[OsWL] Scanning dependencies... (version: 1.4.2)
[OsWL] Parsing manifests on server...
[OsWL] Found 128 component(s).
[OsWL] Sending to server: https://...
[OsWL] Scan submitted! scanId=87
       Analysis is running on the server. Check the Security Center for results.
```

入力するコマンド自体は変わりません — 解析は **Quick Import と同じエンジン**を使ってサーバー側で行われます。

---

## アーキテクチャ

```
Your machine / CI
       │
       │  1. Zip manifest files (lock, pom, package.json, …)
       │     Rules: GET /scripts/manifest-rules.json
       │
       │  2. POST /api/scan/parse  (multipart archive)
       │     Authorization: Bearer oswl_<key>
       ▼
OsWL Server — DependencyManifestParserService (shared with Quick Import)
       │
       │  3. POST /api/scan  (JSON payload + submitter credentials)
       ▼
ScanIngestService → async CVE + license enrichment (OSV / deps.dev)
```

---

## 前提条件

1. OsWL に登録済みの**プロジェクト**。
2. **設定 → CLI** またはプロジェクト API キーページから発行した**プロジェクト API キー**（`oswl_...`）。
3. `SCAN_SUBMIT` 権限を持ち、そのプロジェクトのメンバーである**ユーザーアカウント**（[権限レイヤー](Authorization-Layers.md)参照）。

---

## API エンドポイント（CLI）

| Method | Path | 認証 | 説明 |
|---|---|---|---|
| `GET` | `/api/scan/ping` | API key | キーの有効性確認 |
| `GET` | `/api/scan/manifest-rules` | API key | マニフェスト収集ルール（JSON） |
| `GET` | `/scripts/manifest-rules.json` | なし | 同じルール（CLI キャッシュ用の静的ファイル） |
| `POST` | `/api/scan/parse` | API key | manifest zip の解析 → components |
| `POST` | `/api/scan` | API key + ユーザーパスワード | エンリッチメント用のスキャン送信 |
| `GET` | `/api/scan/{scanId}/status` | セッション | スキャン状態のポーリング（UI） |
| `POST` | `/api/scan/gate` | API key | **v1.0.4** — PR / CI セキュリティゲート。`exitCode` を含む判定を返す |
| `GET` | `/api/projects/{projectId}/sbom` | セッション / キー | **v1.0.4** — CycloneDX 1.6 SBOM |
| `GET` | `/api/projects/{projectId}/vex` | セッション / キー | **v1.0.4** — CycloneDX VEX |
| `GET` | `/api/projects/{projectId}/sarif` | セッション / キー | **v1.0.4** — SARIF 2.1.0 |
| `POST` | `/api/sbom/import` | セッション | **v1.0.4** — サードパーティの CycloneDX ファイルをインポート |

> CLI エンドポイントは `Authorization: Bearer` ヘッダーのみで認証し、セッションクッキーや CSRF トークンは不要です。`POST /api/scan`、`POST /api/scan/parse`、`GET /api/scan/ping` はブラウザの CSRF 検査から除外されます。それ以外のルートは通常どおり CSRF 保護が維持されます。[スキャン API セキュリティ](Scan-Api-Security.md)を参照してください。

---

## API キー管理

### プロジェクト範囲のキー

```
POST /api/projects/{projectId}/keys
```

UI: プロジェクト → **設定 (⚙)** → **CLI** → **キーを生成**。

### 管理者用グローバルキー

**設定 → 管理者 → CLI キー** — [API リファレンス](API-Reference.md)を参照してください。

---

## スキャン送信（raw API）

高度な連携では、`oswl` スクリプトを使わず API を直接呼び出すこともできます。

### ステップ 1 — manifest 解析（`components` を自前で構築する場合は省略可）

```bash
curl -s -X POST https://oswl.example.com/api/scan/parse \
  -H "Authorization: Bearer oswl_<key>" \
  -F "archive=@manifests.zip"
```

レスポンス:

```json
{
  "ecosystem": "MAVEN",
  "componentCount": 128,
  "components": [
    { "name": "org.springframework:spring-core", "version": "6.1.4", "ecosystem": "MAVEN" }
  ]
}
```

（`components` には解析された全128件が入ります — 上記は簡略化のため1件のみ表示）

### ステップ 2 — スキャン送信

```
POST /api/scan
Authorization: Bearer oswl_<key>
Content-Type: application/json
```

```json
{
  "version": "1.4.2",
  "submitterEmail": "dev@company.com",
  "submitterPassword": "yourpassword",
  "components": [
    {
      "name": "org.springframework:spring-core",
      "version": "6.1.4",
      "ecosystem": "MAVEN",
      "dependencyInfo": "Direct",
      "dependencyPaths": []
    }
  ]
}
```

### フィールド

| フィールド | 型 | 必須 | 説明 |
|---|---|---|---|
| `version` | string | ✅ | スキャン時点のプロジェクトバージョン |
| `submitterEmail` | string | ✅ | OsWL ユーザーのメールアドレス |
| `submitterPassword` | string | ✅ | BCrypt で検証され、保存もログ出力もされない |
| `components` | array | — | 検出された OSS コンポーネント |
| `components[].name` | string | ✅ | パッケージ名 |
| `components[].version` | string | — | パッケージバージョン |
| `components[].ecosystem` | string | ✅ | `MAVEN`, `NPM`, `PYPI`, `GO`, `CARGO`, `NUGET`, `RUBYGEMS`, `COMPOSER`, `CONAN` |
| `components[].dependencyInfo` | string | — | 人が読める経路の要約 |
| `components[].dependencyPaths` | array | — | 任意の経路ツリー |

### 成功レスポンス

```json
{
  "scanId": 87,
  "projectId": 42,
  "version": "1.4.2",
  "status": "SCANNING",
  "message": "Scan received successfully"
}
```

### 状態のポーリング

```
GET /api/scan/{scanId}/status
```

```json
{
  "scanId": 87,
  "status": "COMPLETED",
  "componentCount": 128,
  "aiStatus": "RUNNING",
  "securityPostureInsight": null
}
```

状態の流れ: `PENDING` → `SCANNING` → `ANALYZING` → `COMPLETED`（または `FAILED`）

`aiStatus`（**v1.0.4**）は AI エンリッチメントの進行を個別に追跡します: `NOT_APPLICABLE` → `PENDING` → `RUNNING` → `COMPLETED`（または `FAILED`）。CVE／ライセンス解析が終わり次第スキャンは `COMPLETED` になり、AI サマリーは完了をブロックせずバックグラウンドで生成され続けます（AI プロバイダー未設定の場合は `NOT_APPLICABLE`）。`securityPostureInsight` は `aiStatus` が `COMPLETED` になるまで `null` です。

---

## セキュリティゲート（v1.0.4）

`POST /api/scan/gate` は、プロジェクトの最新スキャンを設定した閾値と照らし合わせて評価し、機械可読な判定を返します。`exitCode` をジョブの終了コードに対応させてください — `0` が合格、`1` が失敗です。

```bash
verdict=$(curl -sS -X POST "$OSWL_URL/api/scan/gate"   -H "Authorization: Bearer $OSWL_API_KEY"   -H 'Content-Type: application/json'   -d '{"failOnSeverity":"HIGH","onlyNew":true}')

echo "$verdict"
exit "$(echo "$verdict" | jq -r .exitCode)"
```

サーバー側の既定値（すべてリクエストごとに上書き可能）:

| フィールド | 環境変数 | 既定値 |
|---|---|---|
| `failOnSeverity` | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| `failOnKev` | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| `failOnEpss` | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| `failOnLicenseViolation` | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| `onlyNew` | `OSWL_GATE_ONLY_NEW` | `true` |
| `onlyReachable` | `OSWL_GATE_ONLY_REACHABLE` | `false` |

`onlyNew` は直前の完了スキャンをベースラインとして比較するため、既存の技術的負債がマージを妨げることはありません。`onlyReachable`(**v1.0.5**)はさらに、バイトコード呼び出しグラフ解析で脆弱なライブラリが実際に参照されていることが確認された場合のみブロックする追加のノイズ削減オプションです。`oswl.reachability.bytecode-root` が設定された Java/Gradle コンポーネントにのみ適用され、それ以外はすべて UNKNOWN のままこのオプションではブロックされないため、Java プロジェクトでない限り無効のままにしてください。リクエストに GitHub の対象を指定すると、判定結果は Check Run と PR コメントとしても投稿されます。

確定的に悪性と判定されたパッケージ(OSV `MAL-` アドバイザリ)は、上記のすべてのしきい値および `onlyNew`/`onlyReachable` に関係なく常にブロックされます — 解除する唯一の方法は承認済みのポリシー例外(waiver、**v1.0.5**、`/api/policies/exceptions` 参照)です。

---

## GitHub Actions の例

```yaml
- name: Install OsWL CLI
  run: curl -fsSL https://oswl.example.com/scripts/install.sh | bash

- name: Submit OsWL Scan
  env:
    OSWL_API_KEY: ${{ secrets.OSWL_API_KEY }}
    OSWL_USERNAME: ${{ secrets.OSWL_USERNAME }}
    OSWL_PASSWORD: ${{ secrets.OSWL_PASSWORD }}
    OSWL_SERVER_URL: https://oswl.example.com
  run: oswl scan
```

---

## エコシステムの値

| エコシステム | 名前の形式の例 |
|---|---|
| `MAVEN` | `org.springframework:spring-core` |
| `NPM` | `lodash`, `@angular/core` |
| `PYPI` | `requests`, `django` |
| `GO` | `github.com/gin-gonic/gin` |
| `CARGO` | `serde` |
| `NUGET` | `Newtonsoft.Json` |
| `RUBYGEMS` | `rails` |
| `COMPOSER` | `monolog/monolog`（v1.0.4） |
| `CONAN` | `openssl`（v1.0.4） |

---

## 関連ドキュメント

- [スキャン API セキュリティ](Scan-Api-Security.md)
- [Quick Import](Quick-Import.md) — 同じパーサー、リモート Git URL 版
- [API リファレンス](API-Reference.md)
