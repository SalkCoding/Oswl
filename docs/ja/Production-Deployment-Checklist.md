# 運用デプロイチェックリスト

OsWL をインターネットに公開する前に、この 1 ページのチェックリストを使用してください。**`local` の既定値のまま `prod` を実行しないでください**（H2、Swagger、`/data/**`、コミットされた暗号化キーなど）。

## 1. プロファイルとビルド

| チェック | 対応 |
|-------|--------|
| プロファイル | `SPRING_PROFILES_ACTIVE=prod` を設定 |
| JAR | `./gradlew bootJar verifyProdJar` でビルド — `TestDataController` が JAR に含まれてい**ない**ことを確認 |
| ローカル専用コード | `src/local/java` は `bootRun`／開発専用で、`bootJar` にはパッケージされない |

## 2. 必須の環境変数

| 変数 | 目的 |
|----------|---------|
| `DB_URL` | JDBC URL（例: `jdbc:postgresql://db:5432/oswl`） |
| `DB_USERNAME` | データベースユーザー |
| `DB_PASSWORD` | データベースパスワード |
| `OSWL_ENCRYPTION_KEY` | インスタンス暗号化キー（`openssl rand -base64 32` で生成） |

`deploy/docker/.env.prod.example` を `.env.prod` にコピーし、すべての値を埋めてください。`application-prod.yaml` には DB や暗号化の**既定値はありません**。

起動後の設定警告は `OSWL STARTUP WARNINGS` ログブロックにまとめて出力されます。本番用暗号化キーの不足や DB 設定の不備などにより、このブロックが表示される前に起動が失敗する場合があります。本番環境では固定の `OSWL_ENCRYPTION_KEY` を維持してください。`local` YAML の固定の代替キーは開発専用であり、本番では使用できません。

## 3. ネットワークバインディング

ホストで JVM を直接実行する場合、`application-prod.yaml` の既定値は `SERVER_ADDRESS=127.0.0.1` で、同じホストのリバースプロキシから接続できます。**Docker Compose ではコンテナ内を `SERVER_ADDRESS=0.0.0.0`** にして、Docker からアプリケーションへ転送できるようにします。ホスト側の公開範囲は別の設定です。`deploy/docker/compose.prod.yml` はホストの **`127.0.0.1:8080:8080`** にのみポートを割り当てます。本番用サンプルはこのコンテナ設定を使います。更新時は既存の `.env.prod` も確認してください。

リバースプロキシで TLS を終端してください。付属のホストループバックへのポート割り当てを使う場合、プロキシは Docker ホストで実行します。プロキシもコンテナで動かす場合は、共有 Docker ネットワーク上のサービスアドレスに接続します。転送ヘッダーは信頼できるプロキシからのものだけを受け入れてください。

## 4. Docker Compose（本番）

リポジトリのルートで実行します。既存の `.env.prod` の値は保持し、新規インストール時だけテンプレートをコピーします。両 Compose ファイルの既定プロジェクト名は `oswl` です。既存環境が別の名前を使用していた場合は `-p YOUR_EXISTING_PROJECT` または `COMPOSE_PROJECT_NAME` で同じ名前を維持し、既存ボリュームに接続してください。[デプロイファイルの案内](../../deploy/README.md)を参照してください。

```bash
cp deploy/docker/.env.prod.example .env.prod
# DB_*, OSWL_ENCRYPTION_KEY, SMTP_* を編集
docker compose --env-file .env.prod -f deploy/docker/compose.prod.yml up -d --build
```

Compose は `--env-file` で `.env.prod` を読み込みます。`java -jar` や `bootRun` で直接実行する場合、このファイルは自動で読み込まれません。環境変数を設定するかサービス管理ツールに登録してください。本番環境の初回起動前に DB スキーマを準備します（§9 参照）。

ログを確認: 変数不足の警告がない、PostgreSQL に接続済み、H2 や Swagger の URL がない。

`deploy/docker/compose.prod.yml` は、コンテナ自体の stdout/stderr（docker の `json-file` ドライバ、100MB × 10 ファイル）と、アプリ自身のローテーションファイルログ（`oswl-logs-prod` ボリュームにマウント）の両方に上限を設けています — 後者は §5 を参照。

## 5. ロギングと可観測性

| チェック | 対応 |
|-------|--------|
| ログレベル | `prod` プロファイル: `com.salkcoding.oswl` は **INFO** のみ。AI／クライアントの DEBUG なし |
| AI 抜粋 | `oswl.ai.debug.log-prompt-excerpt` / `log-response-excerpt` は本番で既定 **false** |
| Actuator | **`health`、`info`、`prometheus`** を公開（v1.0.4）。それ以外はすべて無効（`enabled-by-default: false`） |
| メトリクス収集 | Prometheus を `/actuator/prometheus` に向ける — スクレイパーは管理者資格情報を提示する必要あり |
| Actuator 認証 | **SYSTEM_ADMIN** セッションが必要（公開ではない） |

### ログローテーションとリクエスト相関

`local`／`test` はコンソール出力のみです。`prod` では `logback-spring.xml` がローテーションするファイルログを追加で書き出します:

| 変数 | 既定値 | 用途 |
|------|--------|------|
| `OSWL_LOG_DIR` | `./logs`（docker では `/var/log/oswl`、§4 参照） | `oswl.log` の保存先。100MB または日次でローテーションし、最大 30 ファイル保持、合計 5GB を上限とします。 |
| `OSWL_LOG_JSON` | `false` | `true` にすると、ファイル（コンソールではない）が 1 行 1 JSON オブジェクトの形式に切り替わります — ログシッパーをこれに向けて SIEM に取り込んでください。 |

すべてのリクエストには `requestId` が付与され（レスポンスヘッダー `X-Request-Id` としても返されます）、認証済みであれば `userId` も付与されます — どちらも MDC 経由でそのリクエストのすべてのログ行に現れるため（プレーンテキストモードでは `[req=...] [user=...]`、JSON モードではトップレベルのフィールド）、あるリクエストを指すサポートチケットを、タイムスタンプで grep することなくログ全体から追跡できます。

## 6. 本番で有効なセキュリティ機能

- Springdoc / Swagger UI: **無効**
- H2 コンソールと `/data/**`: **本番 JAR には含まれない**（local プロファイル + `src/local/java` のみ）
- セキュリティヘッダー + HSTS（HTTPS 経由）: `application-prod.yaml` の `oswl.security.headers` を参照
- 信頼済みデバイスクッキー: 本番では `Secure`

## 7. 任意のシークレット

| 変数 | 目的 |
|----------|---------|
| `OSWL_TRUSTED_DEVICE_HMAC_KEY` | `OSWL_TD` クッキー専用の HMAC キー（推奨。`OSWL_ENCRYPTION_KEY` とは別に） |
| `OSWL_OIDC_CLIENT_ID` / `OSWL_OIDC_CLIENT_SECRET` / `OSWL_OIDC_ISSUER_URI` | **v1.0.4** — OIDC シングルサインオン。`application-prod.yaml` の `spring.security.oauth2.client` ブロックのコメントも解除してください。ログインページには、プロバイダーが登録されている場合のみ SSO ボタンが表示されます。 |

### v1.0.4 のオプトイン機能

すべて既定で**オフ**です — 意図的に有効化してください。

| 変数 | 既定値 | 有効化した場合の効果 |
|---|---|---|
| `OSWL_FLYWAY_ENABLED` | `false` | 付属の V1 と後続マイグレーションを実行。既存スキーマは事前確認 |
| `OSWL_AIRGAPPED_ENABLED` | `false` | すべての脆弱性／脅威インテリジェンス参照がインポート済みのオフラインスナップショットから提供される。外向きの HTTP なし |
| `OSWL_GATE_*` | [v1.0.4 の新機能](Whats-New-v1.0.4.md)を参照 | `POST /api/scan/gate` の既定閾値 |

継続的モニタリングは例外です: `OSWL_MONITORING_ENABLED` は既定で **`true`**（毎日 03:00 の夜間 OSV 再問い合わせ、`OSWL_MONITORING_CRON`）です。プロジェクトメンバーにメールを送信するため、初回起動前に SMTP が設定済みであることを確認するか、`false` に設定してください。

### パフォーマンス調整 (v1.0.4)

既定値は本番環境で安全です。理由がある場合のみ上書きしてください。

| 変数 | 既定値 | 用途 |
|---|---|---|
| `OSWL_DEPSDEV_CONNECT_TIMEOUT_MS` / `OSWL_DEPSDEV_READ_TIMEOUT_MS` | `5000` / `10000` | deps.dev HTTP タイムアウト（以前は停止した呼び出しがスキャン全体を止められることがあった） |
| `OSWL_DEPSDEV_MAX_CONCURRENT` | `24` | deps.dev 同時リクエスト上限；HTTP 429 時はバックオフして 1 回再試行 |
| `OSWL_OSV_CONNECT_TIMEOUT_MS` / `OSWL_OSV_READ_TIMEOUT_MS` | `5000` / `30000` | OSV HTTP タイムアウト（遅い接続で 1,000 件の batch query は正当に時間がかかる） |
| `OSWL_VERSION_META_TTL_SEC` | `86400` | キャッシュヒット ライブラリの deps.dev バージョン メタデータ TTL |
| `OSWL_CLONE_SPARSE_ENABLED` | `true` | Quick Import のクローンは blobless + sparse-checked-out；partial-clone 非対応の Git サーバーは自動で完全な shallow クローンにフォールバック |
| `OSWL_AI_STREAMING_ENABLED` | `true` | セキュリティ態勢／トレンド／バージョン差分などの自由形式 AI 呼び出しを SSE でストリーミングしライブ プレビューを提供；ストリーミングを拒否するエンドポイントは自動フォールバック |
| `OSWL_AI_MAX_PARALLEL_CALLS` | `3` | 同時 AI エンリッチメント呼び出しの上限；ローカル llama-server は `--parallel` がこの値まで有益 |
| `OSWL_ANTHROPIC_PROMPT_CACHING_ENABLED` | `true` | Anthropic システム プロンプトを短期キャッシュ ブレークポイントとしてマーク |

### 7.1 閉域網 / オフライン スナップショット (v1.0.4)

このモードは対応する脆弱性・脅威情報フィードをスナップショットに切り替える機能であり、ネットワークファイアウォールではありません。閉域環境では VCS、SMTP、Webhook、外部 AI プロバイダーも別途設定してください。

`OSWL_AIRGAPPED_ENABLED=true` に設定すると、脆弱性・脅威インテリジェンスの参照（OSV、deps.dev、EPSS、CISA KEV）がライブ外部 API ではなく、インポート済みのオフライン スナップショットから提供されます。エンリッチメント用の外向き HTTP は試行されません。

| 手順 | 対応 |
|------|------|
| 1. バンドルの作成 | インターネットに接続されたマシンで `oswl-vdb` ビルダーを実行します。ラッパー スクリプト: `scripts/oswl-vdb/oswl-vdb.sh`（Linux/macOS）または `scripts/oswl-vdb/oswl-vdb.ps1`（Windows）。いずれも `./gradlew vdbBuild --args="..."` を呼び出します。 |
| 2. バンドルの対象選定 | 対象インスタンスが実際にスキャンしているコンポーネントを `GET /api/admin/snapshot/wanted-list`（SYSTEM_ADMIN）でエクスポートし、`build --wanted wanted-list.jsonl` に渡します。ビルダーは完全なアップストリーム ミラーではなく、実際に使用するコンポーネントのみを取得します。 |
| 3. バンドルのインポート | `POST /api/admin/snapshot/import?mode=replace|merge`（multipart `.zip`）。大きなバンドルでは `OSWL_AIRGAPPED_IMPORT_DIR` ホワイトリスト ディレクトリを設定したうえで、`POST /api/admin/snapshot/import-from-path` に `{"path":"bundle.zip","mode":"merge"}` を送信します。 |
| 4. モデルの配置（内蔵 AI を使う場合） | オフラインホストの起動前に `embedded-ai/llama/` に実行ファイル、`embedded-ai/model/<系列>/` に検証済みモデルを配置します。内部ミラーを設定してもエアギャップモードではダウンロードできません。§8 参照。 |

`oswl-vdb build` オプション（`VdbBuilderCli` 参照）:
- `--sources osv,epss,kev,depsdev`（既定値はすべて）。
- `--mode delta --since previous.zip` は追加・変更されたキーのみを書き、`"_deleted":true` マーカーも含めます。
- `--offline-sources <dir>` は、以前のオンライン実行で事前に作成されたキャッシュ ディレクトリから、ネットワークなしでビルドします（`osv`/`epss`/`kev` のみ；deps.dev はバルク ダンプがないためスキップされます）。
- `verify <bundle.zip>` と `inspect <bundle.zip>` はチェックサムとメタデータを確認します。

インポートの意味:
- `replace`（既定値）はそのソースの既存データを消去してバンドルを書き込みます。
- `merge` は `(source, entry_key)` 単位で upsert し、`"_deleted":true` 行は削除として扱います。
- v2 バンドルは `meta.json` に記録されたファイル単位の SHA-256 チェックサムを検証し、不一致の場合はバンドル全体を拒否し、既存ストアは変更しません。

定義の鮮度: `OSWL_AIRGAPPED_STALENESS_WARN_DAYS`（既定値 `7`）と `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS`（既定値 `30`）は、インポートされたスナップショットのソース別 `sourceAsOf` 日付のうち最も古い値を基準に管理 UI バッジを決定します。

バンドルが 50MB を超える場合は、`OSWL_MULTIPART_MAX_FILE_SIZE` / `OSWL_MULTIPART_MAX_REQUEST_SIZE`（既定値はそれぞれ `50MB`）を調整する必要があるかもしれません。

## 8. 内蔵AI（任意、CPU専用）

既定は **Qwen3.5-2B Q4_K_M**、選択肢は **Gemma 4 E2B Q4_K_M**です。OsWL・PostgreSQLの同居と断続的使用で、Qwen想定最小2 vCPU / RAM 8 GB、Gemma推奨4 vCPU / RAM 16 GBを目安にします。性能保証ではなく、CPUクレジットと同時スキャンの検証が必要です。

実行ファイルは `embedded-ai/llama/`、重みは `model/Qwen/` と `model/Gemma/` に配置します。自動取得はQwenのみです。GPUレイヤー0、スレッド1、生成スロット1、コンテキスト8192が既定です。固定URL・ハッシュ・サイズを組として維持します。旧 `models-v1` は新モデルではありません。DockerはルートのマウントとLinuxランタイムが必要です。

詳細要件・構成・チェックサム・ミラー/閉域導入・CPU調整・モデルと言語選択・再配布告知は [内蔵AI](Embedded-AI.md) を参照してください。

## 9. データベーススキーマ（アップグレード）

OsWL は `prod` で **Hibernate `ddl-auto=validate`** を使用します — アプリケーションは起動時に PostgreSQL を自動変更しません。

| プロファイル | スキーマ管理 |
|---------|-------------------|
| `local` | `ddl-auto: update` — H2 スキーマが JPA エンティティに自動追従 |
| `prod` | `ddl-auto: validate` — アップグレード時は SQL スクリプトを手動実行 |

手動スクリプトは `src/main/resources/db/` にあります:

| ファイル | 実行タイミング |
|------|-------------|
| `project_members.sql` | プロジェクト ACL の初回デプロイ時（テーブルがない場合） |
| `instance_setup_lock.sql` | セットアップロック機能導入後の初回デプロイ時 |
| `ai_enhancement.sql` | AI プリファレンスのカラム／`ai_daily_usage` 追加前からの旧インストール |
| `schema_cleanup.sql` | 未使用のテーブル／カラム（`ai_feedback`、`external_api_settings`、非正規化された `projects.version` など）を削除するリリースへアップグレードする際に**一度だけ** |

マイグレーション実行後、アプリケーションを再起動し `validate` が通ることを確認してください。

### Flyway（v1.0.4、オプトイン）

`OSWL_FLYWAY_ENABLED=true` で `src/main/resources/db/migration/` のバージョン別マイグレーションを有効にします。既定値は `false` です。リポジトリには `V1__baseline.sql` と後続のマイグレーションが含まれています。空の PostgreSQL DB では V1 から順に実行し、その後 Hibernate がスキーマを検証します。Flyway 履歴のない既存 DB では、`baseline-on-migrate` が V1 を実行せずにバージョン 1 を記録し、V2 以降を実行します。有効化前にバックアップを取得し、既存スキーマとマイグレーションを比較してください。手動適用済みの変更と後続マイグレーションが競合する場合があります。共有 DB に適用済みのファイルを再生成・変更しないでください。SQL を手動管理する場合は、対象バージョンに必要な変更をすべて順番に適用します。以下の一部の旧スクリプトだけでは新規インストール用のスキーマを構成できません。

### v1.0.4 のカラム

このリリースでは `libraries.malicious` と `libraries.typosquat_risk` が追加され、どちらも `NOT NULL DEFAULT false` です。既定値のおかげでデータが入ったテーブルにも追加できるため手動スクリプトは不要ですが、`prod`（`ddl-auto: validate`）では自分で追加する必要があります。さらに、コンポーネント詳細に表示されるアップストリームプロジェクトのメタデータ用に、nullable な `libraries` カラムが 3 つ（`description`、`homepage`、`source_repo_url`）追加されます — `validate` は nullable かどうかに関わらずマッピングされたすべてのカラムの存在を確認するため、これらも同様に手動対応が必要です:

```sql
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS malicious        boolean NOT NULL DEFAULT false;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS typosquat_risk   boolean NOT NULL DEFAULT false;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS description      text;
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS homepage         varchar(500);
ALTER TABLE libraries ADD COLUMN IF NOT EXISTS source_repo_url  varchar(500);
ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS ai_locale varchar(16);
```

（Flyway ユーザー: 新しい 3 つの `libraries` カラムは `V3__component_metadata.sql` でカバーされています。[データベーススキーマ](Database-Schema.md)を参照。）

### v1.0.5: Spring Session / ShedLock テーブル（オプトイン）

**マルチインスタンス**構成に移行する場合にのみ必要です（§12 参照）。`spring_session`、`spring_session_attributes`、`shedlock` を追加します。Flyway ユーザーは `db/migration/V10__spring_session_and_shedlock.sql` から、手動スクリプトのユーザーは `db/spring_session_and_shedlock.sql` を実行してください。シングルインスタンス構成であれば完全にスキップできます — `OSWL_SESSION_STORE_TYPE=jdbc` や `OSWL_SCHEDULER_LOCK_ENABLED=true` を設定するまで、これらのテーブルは誰にも参照されません。

## 10. デプロイ後のスモークテスト

1. HTTPS リバースプロキシ経由でのみ UI を開く。
2. セットアップ／ログイン、有効なら 2FA を完了する。
3. プロジェクトと VCS 接続を作成し、アプリを再起動 — トークンが引き続き復号できる（`OSWL_ENCRYPTION_KEY` が安定していることの確認）。
4. プロジェクト API キーで `POST /api/scan` を実行する（[スキャン API セキュリティ](Scan-Api-Security.md)を参照）。
5. 自分がメンバーであるプロジェクトを開き、他のユーザーのプロジェクト ID がアクセス拒否になることを確認する（プロジェクトメンバーシップ）。
6. 認証失敗の試行がないか監査ログを確認する。

## 11. 運用

- PostgreSQL をバックアップし、`OSWL_ENCRYPTION_KEY` をシークレットマネージャーに保管する（紛失すると VCS トークンが読めなくなります） — 手順全体と復旧リハーサル用スクリプトは [バックアップと復旧](Backup-And-Restore.md) を参照。
- 侵害があった場合は API キーと SMTP 認証情報をローテーションする。
- `local` として実行されるべきでないイメージから `SPRING_PROFILES_ACTIVE` を除外しておく。

## 12. マルチインスタンス配備（水平スケーリング / HA）

OsWL は既定では**シングルインスタンス**として動作します — インメモリの HTTP セッションと、インスタンスごとの `@Scheduled` ジョブです。コンテナ／プロセスが 1 つならこれで問題ありませんが、ロードバランサーの背後に 2 つ目のインスタンスを置くと崩れます。ユーザーのセッションはログインしたインスタンスに固定され、夜間モニタリング／猶予期限切れ／ごみ箱クリーンアップの各ジョブは、クラスタ全体で 1 回ではなく**インスタンスごと**に実行されてしまいます。この節は、同じ PostgreSQL データベースに対して **2 台以上のインスタンス**を配備する場合にのみ関係します。

**1. まずスキーマを適用してください。** 以下の機能を有効にしたインスタンスを起動する前に、`spring_session`、`spring_session_attributes`、`shedlock` が存在することを確認してください（§9「v1.0.5: Spring Session / ShedLock テーブル」）。テーブルが存在しない状態で以下の環境変数を先に展開すると、最初のリクエスト／ジョブ実行時に全インスタンスがクラッシュします。

**2. 環境変数:**

| 変数 | 目的 |
|------|------|
| `OSWL_SESSION_STORE_TYPE=jdbc` | HTTP セッションを Tomcat のインメモリ保存から PostgreSQL（`spring_session`）へ移します。ログイン状態とシングルセッション強制（`maximumSessions(1)`）が、インスタンス単位ではなくクラスタ全体で機能するようになります。 |
| `OSWL_SCHEDULER_LOCK_ENABLED=true` | 3 つのスケジュールジョブ（`ContinuousMonitoringScheduler`、`DeferExpiryScheduler`、`TrashCleanupScheduler`）を、`shedlock` テーブルを利用したクラスタ全体のロック（ShedLock）で包み、サイクルごとに 1 インスタンスだけが実行するようにします。 |

実際にマルチインスタンス配備する場合は両方を同時に設定してください — 片方だけ有効にすると、もう片方の穴がそのまま残ります。

**3. ロードバランサー:** nginx や ALB など一般的な L7 LB で構いません — `OSWL_SESSION_STORE_TYPE=jdbc` を設定すればセッション状態はインスタンスのメモリではなく PostgreSQL に一元化されるため、**スティッキーセッションは不要です。**

**4. ただしスキャン進捗のポーリングだけは例外です。** Quick Import／スキャンのエンリッチメント中に表示されるライブ進捗（`EnrichmentProgressHolder`、`ScanStatusEmitterRegistry`）は、依然としてインスタンスごとのインメモリ状態であり、DB には保存されません。推奨策: ロードバランサーのルーティングを**アクティブなスキャンが実行されている間だけ**スティッキーにする（例: セッション基準のクッキーアフィニティ）ことで、進捗ポーリングのリクエストが実際にそのスキャンを実行しているインスタンスに戻るようにしてください。スキャン進捗を DB に移し UI を純粋なポーリング方式に切り替える代替案はより大きな変更になるため別途追跡しており、現時点ではスティッキールーティングが実用的な既定策です。

**5. ローリングデプロイの手順:**
   1. まず未適用の DB マイグレーションを適用します（旧バージョンのアプリコードが新しいスキーマに耐えられる必要があるため — `db/migration` はこの方針に従い追加のみの変更にしています）。
   2. インスタンスは一斉にではなく 1 台ずつ入れ替え、新しいインスタンスの readiness チェックが通るのを待ってから次に進みます。
   3. `OSWL_SESSION_STORE_TYPE=jdbc` を設定していれば、セッションはインスタンスのメモリではなく PostgreSQL にあるため、ローリング再起動でユーザーがログアウトされることはありません。

**6. 動作確認:**
   - インスタンス A にログインした後、LB がインスタンス B にルーティングする後続リクエストを送っても、認証状態が維持される（`/login` にリダイレクトされない）ことを確認します。
   - インスタンス A を停止しても、セッション（およびシングルセッション強制）がインスタンス B から引き続き機能することを確認します。
   - 夜間ジョブの実行後、両方のインスタンスのログを確認し、そのジョブのログ行が両方ではなく正確に 1 つのインスタンスにのみ現れることを確認します。

---

**ローカル開発:** `./gradlew bootRun`（PowerShell: `.\gradlew.bat bootRun`）で `local` プロファイルと H2 を使用します。ローカル YAML には開発専用の暗号化キーがあります。必要に応じてプロセスの環境変数で変更してください。`.env` は起動ツールが明示的に読み込む場合のみ適用されます。
