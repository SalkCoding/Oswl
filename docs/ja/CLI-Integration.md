# CLI 連携

OsWL は、Web ブラウザや VCS 接続を使わずにローカルマシンや CI パイプラインから依存関係スキャンを送信するための、公式 CLI（`oswl`）と REST API を提供します。

---

ゲートの基準は同じプロジェクト内で対象より前の最も近い完了スキャンで、スキャン時刻とscanIdで順序を決めます。過去スキャンの評価では後のスキャンを基準に選択せず、直近10件の制限もありません。先行する完了スキャンがない場合は基準なしで評価します。保護ブランチの基準やポリシーrevision固定はまだ含みません。


深刻度がなくてもKEV・EPSSゲート規則は独立して評価します。該当規則で遮断した脆弱性は深刻度を推測せず`UNSCORED`と表示します。EPSSは設定した閾値以上で遮断します。この動作は欠落した深刻度や脅威情報の根拠を補うものではありません。

照会完了の判定は未来のfetchedAt・vulnerabilityLookupAtを拒否し、出典別結果が保存されていれば照会時刻を必須にします。保存判定はキャプチャ時のlookupTimesVerifiedを保持し、後の時計や共有キャッシュで再検証しません。旧保存判定にフラグがない場合は根拠未完了として扱うため、遡って値を補わず新しい解析を実行してください。これはローカル照会時刻の検証であり、提供元データの鮮度を証明しません。出典別結果のない旧liveキャッシュは取得時刻があっても未完了です。永続キャッシュ設定でも次の解析時に再照会し、欠けた過去の出典結果を推測で補いません。

NVD/CPEのみを根拠とする検出は、信頼度がHIGHでも照合候補として保持します。ゲートは確定CVE違反の代わりに`MATCH_REVIEW`と未完了の`COVERAGE`を返します。コンポーネントの無視、深刻度の閾値、onlyNew/onlyReachableフィルターではこの確認を省略できません。ベースラインの候補は、後からパッケージの根拠で確認した検出を隠しません。OSV・deps.dev・GitHub Advisoryの出典もある場合はパッケージに基づく評価を維持します。完全なCPE configuration・環境条件の評価と確認を解決する手順は未実装です。出典と信頼度の両方がないレコードは従来の動作を維持します。

共通のパッチ推奨もCPE候補の確認が残っている間はバージョンを保留します。候補に修正文字列がある場合や、キャッシュの共通修正判定に同じIDがある場合でも修正可能性はUNKNOWNです。元の候補情報は保持し、最新リリースをセキュリティ修正の代わりに使いません。同じ検出にパッケージ提供元の根拠があれば既存の共通修正チェックを維持します。最終PR対象検証の厳格なNVD/CPE識別ガードは維持します。

印刷用コンプライアンスレポートにも同じ候補の区分を適用します。CPE候補は別の確認表に表示し、確定した深刻度・KEVの集計と修正バージョンの案内から除外します。コンポーネントを確認済み・保留にしても候補表は残り、未対応の候補は深刻度がなくてもリスクコンポーネントに数えます。この区分は完全な照会範囲を証明するものではなく、ほかのダッシュボードへの一括適用でもありません。


要求・ポリシー・サーバー既定値を解決した最終EPSS閾値は有限かつ1以下であることを検証します。[0,1]で規則を有効にし、有限の負数は強制基準が許可する場合のみ無効化します。NaN・無限大・1超過はゲート結果ではなく不正要求エラーになります。要求は以下の有効基準強化規則に従います。


強制ゲートの要求は有効な組織・チーム・プロジェクトポリシーとサーバー既定値を強化できますが、緩和はできません。深刻度は広い遮断範囲（HIGHよりLOWなど）、有効なKEV・ライセンス・シークレット規則は維持し、EPSSは有効な閾値の低い方を使います。要求の負数EPSSやNONE深刻度では有効な基準を無効化できません。ポリシーのNONEは深刻度比較を無効化しますがKEV・EPSS規則は維持し、要求で深刻度判定を再度有効にできます。不正な深刻度名は拒否します。要求はonlyNew・onlyReachableを無効化して対象を広げられますが、基準が許可しないフィルターで狭められません。応答は適用値を返します。

既存階層ではロックされていない下位ポリシーによる上書きが可能で、ロック済みフィールドの動作も維持します。この変更は決定された有効ポリシーの要求による緩和を防ぎます。保護ブランチ基準・revision固定と別の参考評価は残作業です。


## アップロードの再送

`oswl scan` は実行ごとに新しいランダムな再送キーを生成し、アップロード前に表示します。応答が失われた場合は同じ入力・資格情報で `oswl scan ... --idempotency-key <表示されたキー>` を実行してください。サーバーは解析を再開せず元のscanIdを返します。同じキーで入力が異なる場合は409となり、新しい解析には新しいキーを使うかオプションを省略します。失敗したスキャンの再実行に同じキーを使わないでください。

自動アップロード再試行は行いません。サーバーが再送キー・入力ダイジェスト契約（V42スキーマ）を実装している必要があり、旧サーバーはキーを無視して重複スキャンを作成する場合があります。キーはCLI設定に保存しません。変更したmanifestの再解析、配列順や提出者メールの変更は競合として拒否され、元の結果を保持します。他のスキャンオプションと資格情報は引き続き必要です。

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

マニフェストアーカイブの収集範囲は `/scripts/manifest-rules.json` に従います。依存関係マニフェストのほか、ビルド設定、ラッパーファイル、`buildSrc` の Java/Kotlin ファイルも含まれるため、送信前に収集ルールを確認してください。

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
| `GET` | `/api/projects/{projectId}/sbom` | セッション | **v1.0.4** — CycloneDX 1.6 SBOM |
| `GET` | `/api/projects/{projectId}/vex` | セッション | **v1.0.4** — CycloneDX VEX |
| `GET` | `/api/projects/{projectId}/sarif` | セッション | **v1.0.4** — SARIF 2.1.0 |
| `POST` | `/api/sbom/import` | セッション | **v1.0.4** — サードパーティの CycloneDX ファイルをインポート |

> CLI リクエストは `Authorization: Bearer` にプロジェクト API キーを指定します。`POST /api/scan` は送信者のメールアドレス・パスワード・権限・プロジェクトへのアクセス権限も確認します。`POST /api/scan`、`POST /api/scan/parse`、`POST /api/scan/gate` にブラウザセッションや CSRF トークンは不要です。`GET /api/scan/ping` はキーを検証します。[スキャン API セキュリティ](Scan-Api-Security.md)を参照してください。

---

## API キー管理

### プロジェクト範囲のキー

```
POST /api/projects/{projectId}/keys
```

UI: プロジェクト → **設定 (⚙)** → **CLI** → **キーを生成**。

### 管理者によるキー管理

管理者は各プロジェクトの CLI キーを一覧表示・失効させ、指定した `projectId` にキーを発行できます。スキャンキーの範囲はプロジェクト単位です。別の SCIM トークンではスキャンを送信できません。

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

サーバー側の既定値（要求は有効基準の強化のみ可能）:

| フィールド | 環境変数 | 既定値 |
|---|---|---|
| `failOnSeverity` | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| `failOnKev` | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| `failOnEpss` | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| `failOnLicenseViolation` | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| `onlyNew` | `OSWL_GATE_ONLY_NEW` | `true` |
| `onlyReachable` | `OSWL_GATE_ONLY_REACHABLE` | `false` |
| `failOnSecrets` | `OSWL_GATE_FAIL_ON_SECRETS` | `false` |

`onlyNew` はベースラインで確認済みの CVE・ライセンス問題を除外しますが、ゲートの通過を保証しません。悪意あるパッケージ、有効にしたシークレット検出など、ほかの適用ルールによって失敗する場合があります。`onlyReachable` は、対応するバイトコード解析またはソース参照解析で `REACHABLE` となったコンポーネントの CVE のみを評価します。有効時は `UNKNOWN` の CVE を除外しますが、参照を確認できないことは悪用不可能である証明にはなりません。ライセンスと悪意あるパッケージの検査はこのフィルターとは独立しています。解析範囲を確認してから有効にしてください。GitHub の対象を設定すると Check Run と PR コメントで結果を公開できます。

確定的に悪性と判定されたパッケージ(OSV `MAL-` アドバイザリ)は、上記のすべてのしきい値および `onlyNew`/`onlyReachable` に関係なく常にブロックされます — 解除する唯一の方法は承認済みのポリシー例外(waiver、**v1.0.5**、`/api/policies/exceptions` 参照)です。

`failOnSecrets`(**v1.0.5**)は、Quick Import クローンのスキャンで CRITICAL/HIGH severity のシークレット検出(正規表現 + エントロピー規則 — AWS キー、GitHub/GitLab/Slack/npm トークン、埋め込みプライベートキーブロックなど)が1件でもあればブロックします。他の閾値と同様に有効ポリシー・既定値を強制し、要求は強化のみ反映します。

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
