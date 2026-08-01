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

`.env.prod.example` を `.env.prod` にコピーし、すべての値を埋めてください。`application-prod.yaml` には DB や暗号化の**既定値はありません**。

起動時、不足している変数やその他の設定問題は、（アプリケーションの準備が完了した後）ログ内の**1 つの `OSWL STARTUP WARNINGS` ブロック**にまとめて出力されます。**`prod`** で `OSWL_ENCRYPTION_KEY` が不足している場合、アプリケーションは**起動に失敗**します — 本番公開前に固定値を設定してください（`local` プロファイルは開発専用として一時的なキーを使うことがあります）。

## 3. ネットワークバインディング

| チェック | 対応 |
|-------|--------|
| 既定のバインド | `SERVER_ADDRESS=127.0.0.1`（`application-prod.yaml` 参照） |
| 公開アクセス | **nginx / Caddy / Traefik**（またはクラウド LB）を前段に置き、TLS はそこで終端させる |
| 直接 `0.0.0.0` | JVM の HTTP スタックを公開するリスクを受け入れる場合のみ。リスクとファイアウォールを文書化すること |

`docker-compose.prod.yml` は既定で **`127.0.0.1:8080:8080`** にマッピングされているため、コンテナはすべてのインターフェースには公開されません。

プロキシが HSTS とセキュアクッキーのために `X-Forwarded-Proto` を送信する場合は `server.forward-headers-strategy=framework`（`application.yaml` の既定値）を設定してください。

## 4. Docker Compose（本番）

```bash
cp .env.prod.example .env.prod
# DB_*, OSWL_ENCRYPTION_KEY, SMTP_* を編集
docker compose -f docker-compose.prod.yml up -d --build
```

ログを確認: 変数不足の警告がない、PostgreSQL に接続済み、H2 や Swagger の URL がない。

## 5. ロギングと可観測性

| チェック | 対応 |
|-------|--------|
| ログレベル | `prod` プロファイル: `com.salkcoding.oswl` は **INFO** のみ。AI／クライアントの DEBUG なし |
| AI 抜粋 | `oswl.ai.debug.log-prompt-excerpt` / `log-response-excerpt` は本番で既定 **false** |
| Actuator | **`health`、`info`、`prometheus`** を公開（v1.0.4）。それ以外はすべて無効（`enabled-by-default: false`） |
| メトリクス収集 | Prometheus を `/actuator/prometheus` に向ける — スクレイパーは管理者資格情報を提示する必要あり |
| Actuator 認証 | **SYSTEM_ADMIN** セッションが必要（公開ではない） |

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
| `OSWL_FLYWAY_ENABLED` | `false` | `baseline-on-migrate` によるバージョン管理されたマイグレーション。先に完全なベースラインを生成すること |
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

`OSWL_AIRGAPPED_ENABLED=true` に設定すると、脆弱性・脅威インテリジェンスの参照（OSV、deps.dev、EPSS、CISA KEV）がライブ外部 API ではなく、インポート済みのオフライン スナップショットから提供されます。エンリッチメント用の外向き HTTP は試行されません。

| 手順 | 対応 |
|------|------|
| 1. バンドルの作成 | インターネットに接続されたマシンで `oswl-vdb` ビルダーを実行します。ラッパー スクリプト: `scripts/oswl-vdb/oswl-vdb.sh`（Linux/macOS）または `scripts/oswl-vdb/oswl-vdb.ps1`（Windows）。いずれも `./gradlew vdbBuild --args="..."` を呼び出します。 |
| 2. バンドルの対象選定 | 対象インスタンスが実際にスキャンしているコンポーネントを `GET /api/admin/snapshot/wanted-list`（SYSTEM_ADMIN）でエクスポートし、`build --wanted wanted-list.jsonl` に渡します。ビルダーは完全なアップストリーム ミラーではなく、実際に使用するコンポーネントのみを取得します。 |
| 3. バンドルのインポート | `POST /api/admin/snapshot/import?mode=replace|merge`（multipart `.zip`）。大きなバンドルでは `OSWL_AIRGAPPED_IMPORT_DIR` ホワイトリスト ディレクトリを設定したうえで、`POST /api/admin/snapshot/import-from-path` に `{"path":"bundle.zip","mode":"merge"}` を送信します。 |
| 4. モデルの配置（内蔵 AI を使用する場合） | 閉域網ホストは自動ダウンロードを無効にします。`.gguf` ファイルを `embedded-ai/` に直接配置するか、内部ミラーを運用してください（§8 参照）。 |

`oswl-vdb build` オプション（`VdbBuilderCli` 参照）:
- `--sources osv,epss,kev,depsdev`（既定値はすべて）。
- `--mode delta --since previous.zip` は追加・変更されたキーのみを書き、`"_deleted":true` マーカーも含めます。
- `--offline-sources <dir>` は、以前のオンライン実行で事前に作成されたキャッシュ ディレクトリから、ネットワークなしでビルドします（`osv`/`epss`/`kev` のみ；deps.dev はバルク ダンプがないためスキップされます）。
- `verify <bundle.zip>` と `inspect <bundle.zip>` はチェックサムとメタデータを確認します。

インポートの意味:
- `replace`（既定値）はそのソースの既存データを消去してバンドルを書き込みます。
- `merge` は `(source, entry_key)` 単位で upsert し、`"_deleted":true` 行は削除として扱います。
- v2 バンドルは `meta.json` に記録されたファイル単位の SHA-256 チェックサムを検証し、不一致の場合はバンドル全体を拒否し、既存ストアは変更しません。

定義の鮮度（E7）: `OSWL_AIRGAPPED_STALENESS_WARN_DAYS`（既定値 `7`）と `OSWL_AIRGAPPED_STALENESS_CRITICAL_DAYS`（既定値 `30`）は、インポートされたスナップショットのソース別 `sourceAsOf` 日付のうち最も古い値を基準に管理 UI バッジを決定します。

バンドルが 50MB を超える場合は、`OSWL_MULTIPART_MAX_FILE_SIZE` / `OSWL_MULTIPART_MAX_REQUEST_SIZE`（既定値はそれぞれ `50MB`）を調整する必要があるかもしれません。

## 8. 内蔵 AI モデル（任意、オンプレミス向け）

クラウドプロバイダーの代わりに、またはそれに加えて**内蔵 AI**（設定 → AI → ローカル）を使う予定がある場合のみ関係します。

| チェック | 対応 |
|-------|--------|
| サーバーバイナリ | [llama.cpp のリリース](https://github.com/ggml-org/llama.cpp/releases)からお使いのプラットフォーム用の `llama-server(.exe)` をダウンロードし、`embedded-ai/`（またはその配下の `bin/`、あるいは `PATH` 上の任意の場所）に配置してください — これが唯一の手動手順です |
| モデル | 何もする必要はありません — 新規インストールで**開始**をクリックすると、Apache-2.0 ライセンスの Qwen3-1.7B モデルが自動でダウンロードされます（約 1.2 GB、SHA256 を検証、UI に進捗表示） |
| 閉域網（エアギャップ）ホスト | 自動ダウンロードには一度だけ外向きのインターネットアクセスが必要です。それがない場合は、開始をクリックする前に自分で入手した `.gguf` ファイルを `embedded-ai/` に配置してください |
| カスタムモデル | OsWL が同梱・自動取得するのは Qwen3-1.7B のみです。それ以外の `.gguf`（サイズやライセンスが異なるもの）を使いたい場合は、そのモデル自体のライセンスを確認したうえで自分で `embedded-ai/` に配置してください。[内蔵 AI](Embedded-AI.md)を参照 |
| ディレクトリ | 既定では JVM が起動する作業ディレクトリからの相対パス `./embedded-ai` — 別のパスにするには `OSWL_EMBEDDED_AI_DIR` を設定 |

Gradle タスクや別のスクリプトは関与しません — ダウンロードは開始が初めてクリックされたときにアプリケーション自体の中で実行されるため、単純な `java -jar app.jar` によるデプロイでも動作します。

### 内蔵 AI チューニング (B1 / v1.0.4)

既定値は本番環境で安全です。測定された理由がある場合のみ上書きしてください。

| 変数 | 既定値 | 用途 |
|---|---|---|
| `OSWL_EMBEDDED_AI_CONTEXT` | `8192` | 総コンテキスト サイズ（`-c`）。`--parallel` 使用時はスロット間で分割され、スロット コンテキストが 2048 を下回ると警告ログが出力されます。 |
| `OSWL_EMBEDDED_AI_GPU_LAYERS` | `-1` | `-ngl`: `-1` はビルドがサポートする限りオフロード（`999` を渡す）、`0` は CPU のみ、正の値は明示的なレイヤー数 |
| `OSWL_EMBEDDED_AI_THREADS` | `0` | `-t`: `0` は llama.cpp の自動検出、正の値はスレッド数を固定 |
| `OSWL_EMBEDDED_AI_PARALLEL` | `4` | `--parallel N --cont-batching` を有効化；1 より大きいと同時 AI 呼び出しが直列化されません |
| `OSWL_EMBEDDED_AI_FLASH_ATTN` | `true` | `-fa`（flash attention）を追加 |
| `OSWL_EMBEDDED_AI_CACHE_REUSE` | `256` | `--cache-reuse` トークン数；`0` 以下は無効化 |
| `OSWL_EMBEDDED_AI_EXTRA_ARGS` | （空） | `llama-server` CLI 引数を空白区切りでそのまま追加（管理者専用設定、リクエスト入力ではない） |
| `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC` | `120` | `/health` 応答を待つ秒数。時間内に失敗すると CPU のみでの再試行、または次のモデル候補に進みます。 |
| `OSWL_EMBEDDED_DEFAULT_MODEL_URL` / `SHA256` / `SIZE_BYTES` | 上流の Hugging Face `ggml-org/Qwen3-1.7B-GGUF` | 既定 Qwen3-1.7B ダウンロード用のマッチング セット；自己ホスティング ミラーを使用する場合は 3 つすべてを上書き（バイト単位で同一の再ホストなら URL のみ変更） |
| `OSWL_EMBEDDED_FALLBACK_MODEL_URL` | Hugging Face | 既定 URL が失敗した場合に 1 回再試行；primary と同じか空にすると再試行を無効化 |
| `OSWL_EMBEDDED_AUTO_DOWNLOAD` | `true` | 起動時に既定モデルをバックグラウンドでプリフェッチ（ダウンロードのみで、サイドカーは起動しません）。**`OSWL_AIRGAPPED_ENABLED=true` の場合は無視されます**。 |

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

`OSWL_FLYWAY_ENABLED=true` を設定すると、手動実行スクリプトの代わりに Flyway がスキーマを管理します。`baseline-on-migrate` が有効なため、データが入った既存の DB も拒否されずベースライン処理されます — ただし、有効化する**前に**、現在のスキーマと一致する完全なベースラインマイグレーションを生成してください。既定の `false` のままなら何も変わりません。

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

## 10. デプロイ後のスモークテスト

1. HTTPS リバースプロキシ経由でのみ UI を開く。
2. セットアップ／ログイン、有効なら 2FA を完了する。
3. プロジェクトと VCS 接続を作成し、アプリを再起動 — トークンが引き続き復号できる（`OSWL_ENCRYPTION_KEY` が安定していることの確認）。
4. プロジェクト API キーで `POST /api/scan` を実行する（[スキャン API セキュリティ](Scan-Api-Security.md)を参照）。
5. 自分がメンバーであるプロジェクトを開き、他のユーザーのプロジェクト ID がアクセス拒否になることを確認する（プロジェクトメンバーシップ）。
6. 認証失敗の試行がないか監査ログを確認する。

## 11. 運用

- PostgreSQL をバックアップし、`OSWL_ENCRYPTION_KEY` をシークレットマネージャーに保管する（紛失すると VCS トークンが読めなくなります）。
- 侵害があった場合は API キーと SMTP 認証情報をローテーションする。
- `local` として実行されるべきでないイメージから `SPRING_PROFILES_ACTIVE` を除外しておく。

---

**ローカル開発:** `SPRING_PROFILES_ACTIVE=local`、`.env.example` を `.env` にコピー、`OSWL_ENCRYPTION_KEY` を設定、`./gradlew bootRun` を実行。H2 ファイル DB、H2 コンソール、Swagger、`GET /data/test` はこのプロファイルでのみ利用可能です。
