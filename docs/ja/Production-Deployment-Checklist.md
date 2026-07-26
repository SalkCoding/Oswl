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
