# 管理

このページでは管理者専用の機能をすべて扱います: ユーザー管理、ロールテンプレート、監査ログ、セキュリティ設定、SMTP 設定。

> 特に断りのない限り、このページのすべての操作には**システム管理者**権限が必要です。

ここでのロールテンプレートは**インスタンス全体の権限**を制御するものであり、ユーザーがどのプロジェクトを開けるかを制御するものではありません。[権限レイヤー](Authorization-Layers.md)を参照してください。

---

## ユーザー管理

**設定 → 管理者 → ユーザー**

### ユーザーの招待

1. **ユーザーを招待**をクリックします。
2. ユーザーの**メールアドレス**と**表示名**を入力します。
3. 1 つ以上の**ロールテンプレート**を割り当てます。
4. **招待を送信**をクリックします（メールが無効な場合は**作成** — 仮パスワードが生成されます）。

ユーザーは仮パスワードが記載されたメールを受け取り、初回ログイン時に変更を強制されます。

### ユーザーの編集

| 操作 | エンドポイント |
|---|---|
| 表示名の変更 | `PUT /api/admin/users/{id}/display-name` |
| ロールの更新 | `PUT /api/admin/users/{id}/roles` |
| アカウントの有効化 | `PUT /api/admin/users/{id}/activate` |
| アカウントの無効化 | `PUT /api/admin/users/{id}/deactivate` |
| ユーザーの削除 | `DELETE /api/admin/users/{id}` |

> 無効化されたユーザーはログインできませんが、そのデータ（監査ログ、スキャンの帰属情報）は保持されます。

### セルフサービスによるアカウント削除

**システム管理者**を除く認証済みユーザーは誰でも、現在のパスワードを確認したうえで、ユーザーメニューから自分自身のアカウントを削除できます（**アカウントを削除**）。

| 項目 | 動作 |
|---|---|
| エンドポイント | `POST /api/my/delete-account` — ユーザー ID はセッションのプリンシパルからのみ取得されます（パス／ボディのユーザー ID は使用しません） |
| システム管理者 | 自己削除不可 |
| 削除対象 | `users` 行、`project_members` 行、保存済み VCS トークン |
| 保持対象 | それまでのすべての**監査ログ**行（行為者のメールアドレス／名前／ID のスナップショット）、プロジェクト、スキャン、インポート履歴 |
| 監査アクション | `USER.SELF_DELETE` — ユーザー行が削除される**前**に記録されるため、`actor_user_id` と表示名がキャプチャされます |

管理者による削除は引き続き `DELETE /api/admin/users/{id}` 経由の `USER.DELETE` です。

---

## ロールテンプレート

**設定 → 管理者 → ロールテンプレート**

ロールテンプレートは、複数のユーザーに割り当てられる名前付きの権限セットです。

### 内蔵ロールテンプレート

**空のデータベースでの初回起動時**に、OsWL は編集可能な 3 つのテンプレートを作成します:

| テンプレート | 対象ユーザー |
|----------|-------------------|
| **Admin** | 権限カタログ全体（インスタンス運用者向け） |
| **Developer** | スキャン、トリアージ、ライセンス閲覧／エクスポート、VCS・CLI キー |
| **Viewer** | 読み取り専用の分析ページとエクスポート |

これらは**ロールテンプレート**（レイヤー A）です。すべてのプロジェクトにユーザーを自動的に追加するわけではありません — [権限レイヤー](Authorization-Layers.md)を参照してください。

追加のテンプレートを作成したり、権限をいつでも変更したりできます。

### 権限リファレンス

| 権限 | 説明 |
|---|---|
| `PROJECT_VIEW` | プロジェクト一覧・詳細の閲覧 |
| `PROJECT_CREATE` | 新規プロジェクトの登録（Quick Import または CLI） |
| `PROJECT_DELETE` | プロジェクトをゴミ箱へ移動 |
| `PROJECT_RESTORE` | ゴミ箱からプロジェクトを復元 |
| `PROJECT_PERMANENT_DELETE` | ゴミ箱からプロジェクトを完全削除 |
| `SCAN_SUBMIT` | CLI 経由でのスキャン送信（`POST /api/scan`） |
| `SCAN_VIEW` | スキャン結果の閲覧 |
| `SCAN_HISTORY_VIEW` | スキャン履歴一覧の閲覧 |
| `SECURITY_CENTER_VIEW` | セキュリティセンターの CVE 一覧の閲覧 |
| `SECURITY_CENTER_UPDATE_STATUS` | CVE トリアージ状態の更新 |
| `SECURITY_CENTER_EXPORT` | セキュリティセンター結果のエクスポート |
| `LICENSE_VIEW` | ライセンス分析ページの閲覧 |
| `LICENSE_EXPORT` | NOTICE ファイルおよび SPDX SBOM のダウンロード |
| `LICENSE_POLICY_MANAGE` | ライセンスポリシー項目の追加／編集／削除 |
| `SCAN_HISTORY_DELETE` | スキャン履歴からの項目削除 |
| `COMPONENT_DETAIL_VIEW` | コンポーネント詳細パネルの閲覧 |
| `VERSION_DIFF_VIEW` | バージョン比較の閲覧 |
| `RISK_TREND_VIEW` | リスクトレンドグラフの閲覧 |
| `SETTINGS_AI_MANAGE` | AI プロバイダー設定の構成 |
| `SETTINGS_VCS_MANAGE` | VCS 接続の追加／削除 |
| `SETTINGS_CLI_KEY_MANAGE` | プロジェクト CLI API キーの管理 |
| `SETTINGS_CACHE_MANAGE` | キャッシュ設定の管理 |
| `SETTINGS_SECURITY_MANAGE` | SMTP・2FA 設定の構成 |
| `ORG_DASHBOARD_VIEW` | **v1.0.4** — 組織ダッシュボード（`/org-dashboard`）の閲覧 |
| `AUDIT_LOG_VIEW` | **v1.0.4** — 監査ログの閲覧 |
| `AUDIT_LOG_EXPORT` | **v1.0.4** — SIEM 取り込み用の監査ログエクスポート |
| `SETTINGS_JIRA_MANAGE` | **v1.0.4** — Jira 連携の管理 |
| `SETTINGS_SNAPSHOT_MANAGE` | **v1.0.4** — オフラインスナップショットバンドルの管理 |

> v1.0.4 で追加された 5 つの権限は既存のロールテンプレートには**追加されず**、未付与の状態から始まります。特に `AUDIT_LOG_EXPORT` は監査記録をプラットフォーム外に送信するため、意図的に付与してください。

### テンプレートの作成

1. **新しいロールテンプレート**をクリックします。
2. 名前を入力します（例: "Developer"、"Security Analyst"、"Read Only"）。
3. 必要な権限にチェックを入れます。
4. **保存**をクリックします。

---

## セキュリティ設定（SMTP と 2FA）

**設定 → セキュリティ**

### SMTP（メールサーバー）

OsWL は二要素認証やユーザー招待の OTP メール送信に SMTP を使用します。

| 項目 | 説明 |
|---|---|
| **メールモード** | `DISABLED`（メールなし）、`SMTP`（標準リレー）、`STARTTLS` / `SSL_TLS` |
| **ホスト** | SMTP サーバーのホスト名 |
| **ポート** | SMTP ポート（通常 25、465、または 587） |
| **ユーザー名／パスワード** | SMTP 認証情報（パスワードは保存時に暗号化） |
| **送信者名／アドレス** | "From" に表示される名前とアドレス |

保存前に**テストメールを送信**をクリックして設定を確認できます。

### 二要素認証（2FA）

| モード | 動作 |
|---|---|
| `DISABLED` | OTP ステップなし — ユーザーはメールアドレス＋パスワードのみでログイン |
| `OPTIONAL` | OTP は利用可能だがユーザーはスキップできる |
| `REQUIRED` | すべてのユーザーがログインのたびに OTP ステップを完了する必要がある |

#### 信頼済みデバイス

2FA が有効な場合、ユーザーは OTP 認証に成功した後、ブラウザを**信頼済み**としてマークできます。信頼済みデバイスは、設定可能な期間（既定: 30 日間）OTP ステップをスキップします。

### パスワードポリシー

| 設定 | 既定値 | 説明 |
|---|---|---|
| 最小パスワード長 | `8` | 招待作成時とパスワード変更時に適用 |

---

## サーバープロパティ（application.yaml）

`application.yaml` または環境変数（Spring の relaxed binding）で設定するインスタンスレベルのセキュリティフラグです。設定 UI からは編集できず、再起動が必要です。

| 設定キー | 環境変数 | 既定値 | 説明 |
|---|---|---|---|
| `oswl.quick-import.allow-build-exec` | `OSWL_QUICK_IMPORT_ALLOW_BUILD_EXEC` | `false` | `false` の場合、Quick Import はマニフェストを**静的に**解析し、クローンしたリポジトリ内で見つかったビルドツール（`mvnw`、`gradlew`、`dotnet`）を決して実行しません。すべてのインポート対象リポジトリが信頼できる場合にのみ `true` に設定してください — ビルドベースのバージョン解決は OsWL ホスト上でリポジトリのビルドスクリプトを実行します。 |
| `oswl.security.trusted-proxies` | `OSWL_SECURITY_TRUSTED_PROXIES` | *(空)* | 信頼できるリバースプロキシの IP をカンマ区切りで指定します。直接の接続元がこの一覧に含まれる場合にのみ、`X-Forwarded-For` ヘッダーがクライアント IP の解決（監査ログ、レート制限）に使われます。空の場合、このヘッダーは無視されます。OsWL が自分の管理下にあるプロキシの背後で動作している場合のみ設定してください。 |
| `oswl.timezone` | `OSWL_TIMEZONE` | `Asia/Seoul` | 時刻依存の機能（現時点では AI 使用量の追跡 — 呼び出しがどの「日」に計上されるか）に使われるタイムゾーンです。デプロイの営業日が既定と異なるタイムゾーンに従うべき場合にのみ変更してください。 |

---

## 監査ログ

**設定 → 管理者 → 監査ログ**

監査ログはすべての重要なユーザーおよびシステムの操作を記録します。

| カラム | 説明 |
|---|---|
| **タイムスタンプ** | イベントが発生した時刻 |
| **行為者** | ユーザーのメールアドレスまたは `SYSTEM` |
| **アクション** | イベントコード（例: `SCAN.INGEST`、`AUTH.LOGIN_SUCCESS`、`LICENSE.EXPORT`） |
| **リソースタイプ** | 影響を受けたエンティティ（PROJECT、SCAN、USER など） |
| **リソース ID** | 影響を受けたエンティティの ID |
| **詳細** | 追加のコンテキスト（新しい値、バージョン文字列など） |

### フィルタリング

行為者、アクション（UI 上でグループ化 — 認証、ユーザー、プロジェクト、スキャン、CLI キー、コンポーネント、設定を含む）、日付範囲でフィルタリングできます。

**ユーザーアクションコード**には `USER.SELF_DELETE`（セルフサービスによるアカウント削除）と `USER.DELETE`（管理者による削除）が含まれます。

### エクスポート

**CSV をエクスポート**をクリックすると、現在フィルタリングされているビューを CSV ファイルとしてダウンロードできます。

**SIEM エクスポート（v1.0.4）** — `GET /api/admin/audit-logs/export?format=jsonl|cef` は、同じフィルタリング済みビューを SIEM に取り込み可能な形式（既定は JSON Lines、または ArcSight CEF）でストリーミングします。`AUDIT_LOG_EXPORT` 権限が必要で、エクスポート自体も `AUDIT_LOG.EXPORT` として記録されます。

v1.0.4 のアクションコードはフィルター UI で **モニタリング**（`MONITOR.*`）、**連携**（`JIRA.SETTINGS_UPDATE`）、**管理**（`ORG_DASHBOARD.VIEW`、`AUDIT_LOG.EXPORT`、`SNAPSHOT.IMPORT` / `SNAPSHOT.EXPORT` / `SNAPSHOT.WANTED_LIST_EXPORT`）、**キャッシュ**（`CACHE.UPDATE_TTL`、`CACHE.CLEAR`）としてグループ化され、新しいエクスポート・ゲートのコード（`SBOM.EXPORT`、`VEX.EXPORT`、`SARIF.EXPORT`、`COMPLIANCE_REPORT.VIEW`、`GATE.EVALUATE`、`GATE.GITHUB_PUBLISH`、`PROJECT.BATCH_PR`、`SBOM.IMPORT`、`COMPONENT.JIRA_TICKET`）も併せて提供されます。

### 保持期間

設定された保持期間より古い監査レコードは、スケジュールされたジョブによって自動的に削除されます。

| 設定キー | 既定値 | 説明 |
|---|---|---|
| `OSWL_AUDIT_RETENTION_MONTHS` | `6` | これより古いレコードは自動削除される月数 |
| `OSWL_AUDIT_MAX_PAGE_SIZE` | `200` | API 1 ページあたりの最大レコード数 |

---

## 組織ダッシュボード（v1.0.4）

`/org-dashboard` は、すべてのプロジェクトを 1 つのポートフォリオビューにまとめます — 深刻度の合計、最もリスクの高いプロジェクトのランキング、KEV 収載 CVE 件数、ライセンス警告。

`ORG_DASHBOARD_VIEW`（または `SYSTEM_ADMIN`）が必要です。付与されると、プロジェクト一覧・プロジェクト詳細・バージョン比較の各画面の上部バーに入口が表示されます。

---

## モニタリングエンドポイント（v1.0.4）

| エンドポイント | 用途 |
|---|---|
| `/actuator/health` | 生存確認／準備状態確認 |
| `/actuator/info` | ビルド・バージョン情報 |
| `/actuator/prometheus` | Prometheus スクレイピング用の Micrometer メトリクス |

3 つとも管理者権限が必要です。Prometheus のスクレイプ設定は `application-prod.yaml` の `management` 配下にあります。

---

## オフラインスナップショットバンドル（v1.0.4）

**設定 → 管理者 → オフラインスナップショット**

閉域網（エアギャップ）デプロイ（`OSWL_AIRGAPPED_ENABLED=true`）向けに、脆弱性・脅威インテリジェンスデータ（OSV、deps.dev、FIRST.org EPSS、CISA KEV）はライブ API ではなくインポート済みのスナップショットから提供されます — 外部への HTTP 通信は一切試みられず、スナップショットに存在しないコンポーネントは「データなし」として表示されます。

`SYSTEM_ADMIN` または `SETTINGS_SNAPSHOT_MANAGE` が必要です。[v1.0.4 の新機能](Whats-New-v1.0.4.md)を参照してください。

バンドルは v2 フォーマットです: 各 JSONL ファイルのチェックサムが `meta.json` に記録され、ソースごとの来歴情報（`bundleId`、`builtAt`、`asOf`、`origin`）も保存されます。チェックサムが一致しない場合、データを書き込む前にインポートが拒否されます。

| 操作 | エンドポイント |
|---|---|
| バンドルの状態 | `GET /api/admin/snapshot` |
| インポート（ファイルアップロード） | `POST /api/admin/snapshot/import`（multipart、任意で `?mode=replace\|merge`） |
| インポート（サーバー上のパス） | `POST /api/admin/snapshot/import-from-path` |
| エクスポート | `GET /api/admin/snapshot/export` |
| ウォンテッドリスト | `GET /api/admin/snapshot/wanted-list` |

インポートはストリーミング処理され（アップロード全体をメモリにバッファリングしません）、`SNAPSHOT.IMPORT` として監査記録されます。エアギャップインスタンスでは、インポート直後にメモリ内の KEV カタログが再読み込みされ、新しいスナップショットが即座に適用されます。

### 定義のステータスと鮮度（staleness）

**定義ステータス（Definition Status）**カードには、ソースごとに 1 行が表示されます — `OSV`、`deps.dev (versions)`、`deps.dev (advisories)`、`FIRST.org EPSS`、`CISA KEV` — レコード数、アップストリームの**基準日（as-of）**、origin、インポート時刻とともに。タイトル横の鮮度バッジは（バンドルのビルド時刻やインポート時刻ではなく）**最も古い**ソースの基準日から計算されます:

| バッジ | 意味 |
|---|---|
| 最新（緑） | 最も古い定義が `staleness-warn-days` 以内 |
| 更新推奨（黄） | `staleness-warn-days` より古い |
| 古い（赤） | `staleness-critical-days` より古い |

| 設定キー | 環境変数 | 既定値 | 説明 |
|---|---|---|---|
| `oswl.airgapped.staleness-warn-days` | `OSWL_AIRGAPPED_STALENESS_WARN_DAYS` | `7` | 最も古い定義からこの日数を超えるとバッジが黄色になる |
| `oswl.airgapped.staleness-critical-days` | `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS` | `30` | この日数を超えるとバッジが赤色になる |

ビルダーがウォンテッドリスト経由で要求されたものの、アップストリームで見つけられなかった、または確信を持って評価できなかったコンポーネントがある場合、**未解決（アップストリームデータなし）**行が表示されます — これらのコンポーネントは「確認済みでクリーン」ではなく「データなし」として扱ってください。

### インポートモード

- **バンドルの既定** — 差分（デルタ）としてビルドされた v2 バンドルは merge としてインポートされ、それ以外は replace としてインポートされます。
- **Replace** — バンドルに含まれる各ソースを書き込む前にクリアします。
- **Merge** — キーで upsert し、削除マーカー（tombstone）を尊重するため、デルタバンドルで失効したエントリを削除することもできます。

ファイルアップロードに加えて、**パスからインポート（Import from Path）**はサーバーのディスク上に既にあるバンドルを読み込みます。`oswl.airgapped.import-dir`（`OSWL_AIRGAPPED_IMPORT_DIR`）で設定したディレクトリ配下のファイルのみを受け付け、realpath チェックにより強制されます。値が空の場合、このエンドポイントは無効化されます。

### このインスタンス向けの定義をビルドする（ウォンテッドリスト）

**ウォンテッドリストをダウンロード**をクリックすると、このインスタンスがスキャンしたすべての一意な (ecosystem, name, version) の組み合わせが JSONL としてエクスポートされます。これにより、インターネットに接続されたマシン上の `oswl-vdb` ビルダーは、アップストリーム全体をミラーリングする代わりに、それらのコンポーネントだけの定義を取得できます。ファイルには ecosystem/name/version のみが含まれ — プロジェクト名、リポジトリ URL、ファイルパスは含まれません。インターネットに接続されたマシンで次を実行します:

```bash
scripts/oswl-vdb/oswl-vdb.sh build --wanted wanted-list.jsonl --sources osv,epss,kev,depsdev --out bundle.zip
```

（Windows では `oswl-vdb.ps1`）。その後、生成された `bundle.zip` をインポートカードでアップロードします。同じスクリプトは、インポート前にバンドルを確認するための `verify` と `inspect` サブコマンドも提供します。

---

## AI 設定

**設定 → AI**

CVE／ライセンス要約用の LLM プロバイダーとエンリッチメント動作を構成します。

| プロバイダー | 補足 |
|---|---|
| **無効** | AI インサイトは生成されない |
| **OpenAI** | API キー + モデル（例: `gpt-5.6-terra`） |
| **Anthropic** | API キー + モデル（例: `claude-opus-5`）。`temperature` の上書きは適用されません — 現行の Claude モデルはサンプリングパラメータを拒否するため、応答スタイルはプロンプトで調整されます |
| **Gemini** | API キー + 必要に応じて OpenAI 互換の base URL（例: `gemini-3.1-pro`） |
| **ローカル** | OpenAI 互換のエンドポイント（例: Ollama — ドロップダウンには `qwen3`、`gemma3`、`deepseek-r1` などの人気モデルタグが提案として表示） — または下記の内蔵 AI サイドカー |

各プロバイダーのモデル欄は自由入力コンボボックスです: ドロップダウンには現行モデルが提案として表示されますが、アカウントがアクセス可能な任意のモデル ID を直接入力できます。

同じタブの**内蔵 AI（組み込みローカルモデル）**カードは、バンドルされた llama.cpp の `llama-server` サイドカー（CPU 専用、localhost 専用、API キー不要）を実行し、LOCAL プロバイダーとして登録します。既定でバンドルされているモデルは**Qwen3 1.7B**（初回使用時にダウンロード）で、カードには**モデルのドロップダウン**（フォルダ内の任意の `.gguf`、または自動優先順位）、**保存**付きの**フォルダの上書き**（永続化され、実行中に変更するとサイドカーが停止）、最初の選択が起動に失敗した場合に次に利用可能なモデルへ切り替える**自動フォールバック**があります。[内蔵 AI](Embedded-AI.md)を参照してください。

同時に**アクティブ**にできるプロバイダーは 1 つだけです。タブには次も表示されます:

| 設定 | 用途 |
|---|---|
| プロンプトロケール（`en` / `ko` / `ja`） | `prompts.properties` か、韓国語・日本語のオーバーレイかを選択 |
| CVE／ライセンスのバッチ上限と深刻度 | スキャンごとのエンリッチメント AI 呼び出しを制限 |
| Temperature／最大トークン数／日次呼び出し上限 | LLM の挙動とコストのガードレール（Anthropic では temperature は無視されます — 上記参照） |
| 既定のデプロイメントプロファイル | プロジェクトにプロファイルがない場合の CVE トリアージ**および**ライセンスリスク評価の文脈 — 社内ツール、ネットワークサービス、配布されるソフトウェアでは義務が大きく異なります |
| プロンプトの上書き | キーごとのテンプレート編集（`GET /api/settings/ai/prompts` を参照） |

**接続テスト**はトークンを一切消費しません: 完了リクエストを送る代わりに、プロバイダーの利用可能なモデル一覧を取得するため（OpenAI/Gemini/Ollama は `GET {base}/models`、Anthropic は `GET /v1/models`）、認証情報と到達可能性の確認は無料であり、日次呼び出し上限にもカウントされません。設定されたモデル ID がアカウントでアクセス可能なモデルに含まれない場合、テストは成功しますが警告が表示されるため、タイプミスや未取得のローカルモデルを次回のスキャンではなくその場で発見できます。

**API:** `GET|PUT /api/settings/ai`、`POST /api/settings/ai/test-connection`、`POST /api/settings/ai/golden-test`。
**内蔵 AI:** `GET /api/settings/ai/embedded`、`POST .../embedded/start?model=`、`POST .../embedded/stop`、`PUT .../embedded/config` — [API リファレンス — AI](API-Reference.md#ai)を参照。
**プロジェクトごと:** `PATCH /api/projects/{id}/deployment-profile`。
**コンポーネント詳細:** CVE の AI 要約を再生成する `POST .../cves/{cveDbId}/ai-summarize`（`COMPONENT.CVE_AI_REGENERATE` として記録）。

### AI 応答のキャッシュ（v1.0.4）

CVE／ライセンスのバッチエンリッチメントは、各 `Cve` および `Library` 行に SHA-256 のコンテキストハッシュを保存します。次回のスキャン時、回答を左右する入力（severity、CVSS スコア／ベクター、修正バージョン、EPSS バケット、KEV ステータス、依存関係の種類、patchability、ライセンス名／ステータス、デプロイメントプロファイルなど）が変化していなければ、既存の AI 要約が再利用され、プロバイダーは再度呼び出されません。この動作は自動であり、AI 応答キャッシュをクリアする専用の管理画面はありません。コンポーネント詳細画面の **再生成** アクションを手動で実行すると、キャッシュを回避できます。

### 使用量とコストの追跡

AI カードには本日の呼び出し件数、トークン合計、推定コスト（`GET /api/settings/ai/usage`。日次集計テーブルに基づくため、履歴が増えても照会コストは低く抑えられます）に加え、10 件ずつページネーションされる**最近の呼び出し**表（`GET /api/settings/ai/usage/events`）が表示されます。生の呼び出しイベントは直近**100 件**のみ保持され、新しい呼び出しが記録されるたびに古いものから削除（FIFO）されますが、日次合計と 7 日間のトレンドは生のイベントログではなく集計テーブルから取得されるため影響を受けません。

推定コストはおおよその数値であり、プロバイダーからの請求書では**ありません**。各モデルの公式な 100 万トークンあたりの入力／出力定価を使って**モデルごと**に計算されます（例: `claude-opus-5`、`gpt-5.6-terra`、`gemini-3.1-pro` はそれぞれ異なる単価を持ち、同じプロバイダー内で 10 倍高価なモデルが 1 つの平均単価にまとめられることはもうありません）。キャッシュ入力・バッチ・ロングコンテキストの割引は反映されないため、目安として扱ってください。定価が公開されていないモデル（カスタムデプロイ、新規リリース、または**ローカル**プロバイダーで動作するもの全般）は、次のようなプロバイダーごとの固定単価にフォールバックします:

| 設定キー | 既定値（USD / 100万トークン） |
|---|---|
| `oswl.ai.pricing.openai-input-per-1m` / `openai-output-per-1m` | `2.50` / `10.00` |
| `oswl.ai.pricing.anthropic-input-per-1m` / `anthropic-output-per-1m` | `3.00` / `15.00` |
| `oswl.ai.pricing.gemini-input-per-1m` / `gemini-output-per-1m` | `1.25` / `5.00` |
| `oswl.ai.pricing.local-input-per-1m` / `local-output-per-1m` | `0` / `0` |

実際の契約単価が上記の既定値と異なる場合は、これらの値を更新してください。**ローカル**プロバイダーは、報告するモデル名にかかわらず常に設定された単価（既定 `0`）で見積もられます — 自己ホスト型のモデルには参照すべきトークン単価そのものが存在しないためです。

---

## キャッシュ設定

**設定 → キャッシュ**

**ライブラリエンリッチメントキャッシュ**（deps.dev + OSV）の一元的な制御ポイントです。個別の「外部 API 設定」画面や API はありません。

| キャッシュキー | 既定 TTL | 用途 |
|---|---|---|
| `DEPS_DEV` | 7 日 | 主要なエンリッチメントキャッシュポリシー（バージョン情報、アドバイザリ、再取得判断） |
| `OSV_VULN` | 7 日 | deps.dev と併せて追跡され、クリア時刻は「最後にクリアされた」ロジックに関与 |

| 操作 | API | 説明 |
|---|---|---|
| **表示** | `GET /api/settings/cache` | キーごとの TTL、最後にクリアした人、時刻 |
| **TTL の更新** | `PUT /api/settings/cache` | `cacheKey` + `ttlSeconds` を設定（UI: 常にリフレッシュ／カスタム／無期限） |
| **クリア** | `POST /api/settings/cache/clear?cacheKey=…` | クリア時刻以前に取得されたライブラリは、次回のスキャンで古いものとして扱われる |

変更は `CACHE.UPDATE_TTL` と `CACHE.CLEAR` として監査記録されます。

---

## SAML 2.0 SSO および SCIM 2.0 プロビジョニング

OsWL は、Okta、Entra ID、オンプレミス AD FS を利用する企業向けに SAML 2.0 シングルサインオンをサポートしています。SAML IdP が設定されると、`/login` に **SSO でサインイン** オプションが表示されます。

### SAML セットアップ

1. SP 署名鍵ペアを生成します（任意ですが推奨）:
   ```bash
   openssl req -x509 -newkey rsa:2048 -keyout oswl-saml-sp.key -out oswl-saml-sp.crt -nodes -days 3650 -subj "/CN=oswl"
   ```
2. `application-prod.yaml` の SAML ブロックのコメントを外し、環境変数を設定します:
   | 環境変数 | 用途 |
   |---|---|
   | `OSWL_SAML_IDP_METADATA_URL` | IdP メタデータ URL（例: Okta/Entra アプリメタデータ） |
   | `OSWL_SAML_IDP_CERTIFICATE` | IdP 署名証明書ファイルのパス |
   | `OSWL_SAML_SP_PRIVATE_KEY` | SP 秘密鍵ファイルのパス |
   | `OSWL_SAML_SP_CERTIFICATE` | SP 証明書ファイルのパス |
3. IdP に SP メタデータを登録します。メタデータエンドポイントは以下です:
   ```
   https://<your-oswl-host>/saml2/service-provider-metadata/oswl
   ```
4. IdP が email クレーム（NameID または `email`/`mail` 属性）を送信することを確認します。

> SAML ログインでは、IdP がすでにユーザーを認証しているため、メール OTP ステップをスキップします。既存の OsWL アカウントと一致しないメールアドレスは、SCIM が有効化してロールを割り当てられるよう、無効化されたローカルアカウントとして自動作成されます。

### SCIM 2.0 プロビジョニング

SCIM を使用すると、IdP のユーザー ライフサイクルを OsWL と同期できます。

| リソース | エンドポイント | 備考 |
|---|---|---|
| Users | `/scim/v2/Users` | GET/POST/PUT/PATCH/DELETE |
| Groups | `/scim/v2/Groups` | GET/POST/PUT/PATCH/DELETE |

**認証:** すべての SCIM リクエストに `Authorization: Bearer <scim_token>` を含める必要があります。専用 SCIM トークンは `ApiKeyService#issueScimToken` でプログラム的に発行します。SCIM トークンは `api_keys` テーブルに保存されますが、スコープは `SCIM` であり、通常の CLI スキャン API では拒否されます。

**グループ マッピング:** `oswl.scim.group-mapping`（環境変数: `OSWL_SCIM_GROUP_MAPPING`）で SCIM グループの表現方法を選択します:
- `TEAM`（既定）— 各 SCIM グループは Team になり、メンバーは TeamMember 行になります。
- `ROLE_TEMPLATE` — 各 SCIM グループは RoleTemplate になり、メンバーにはそのロール テンプレートが割り当てられます。

**ユーザー無効化:** `DELETE /scim/v2/Users/{id}` は OsWL 上で `active=false` に設定します。SCIM 経由ではユーザーを物理的に削除しないため、監査の帰属情報が保持されます。

**監査アクション:** SCIM 操作は `SCIM.USER_CREATE`、`SCIM.USER_UPDATE`、`SCIM.USER_DEACTIVATE`、`SCIM.GROUP_CREATE`、`SCIM.GROUP_UPDATE`、`SCIM.GROUP_DELETE`、`SCIM.GROUP_MEMBER_ADD`、`SCIM.GROUP_MEMBER_REMOVE`、`SCIM.AUTH_FAILURE`、`SCIM_KEY.CREATE` として記録されます。SAML ログイン イベントは `SAML.LOGIN_SUCCESS` および `SAML.LOGIN_FAILURE` として記録されます。
