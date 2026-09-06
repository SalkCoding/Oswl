# v1.0.4 の新機能

v1.0.4 はコンプライアンスとワークフローのリリースです。標準準拠のエクスポート（CycloneDX SBOM、VEX、SARIF）、CI/CD セキュリティゲート、継続的モニタリング、組織全体ダッシュボード、サプライチェーンヒューリスティック、閉域網（エアギャップ）モード、日本語対応が追加されました。

以下の機能はすべてセルフホスト版に含まれています — 別エディションやライセンスキーは不要です。

---

## コンプライアンス向けエクスポート

### CycloneDX SBOM (1.6)

| | |
|---|---|
| **エンドポイント** | `GET /api/projects/{projectId}/sbom` |
| **UI** | セキュリティセンター → **エクスポート** → *SBOM (CycloneDX)* |
| **形式** | CycloneDX 1.6 JSON, `application/vnd.cyclonedx+json` |

プロジェクトの最新の完了スキャンから生成されます。各コンポーネントに `purl`、判定済みライセンス（SPDX ID または式）、scope が含まれ、メタデータにはツール情報、スキャン時刻、ルートコンポーネント（プロジェクト／バージョン）が記録されます。

### VEX

| | |
|---|---|
| **エンドポイント** | `GET /api/projects/{projectId}/vex` |
| **UI** | セキュリティセンター → **エクスポート** → *VEX* |

VEX は検出結果そのものではなく、**トリアージの判断**を伝えます。各脆弱性が CycloneDX の分析状態にマッピングされます。

| OsWL のステータス | VEX の状態 | 根拠 |
|---|---|---|
| 未対応 / 確認中 | `exploitable` | — |
| 無視 | `not_affected` | 無視理由をそのまま記録 |
| 保留 | `in_triage` | 保留期限を含む |
| 対応済み | `resolved` | — |

監査担当者や下流の利用者が求めるのは「脆弱なライブラリを含めて出荷しているが、実際に影響を受けるのか」への機械可読な回答です。VEX はまさにそれを提供します。

### SARIF (2.1.0)

| | |
|---|---|
| **エンドポイント** | `GET /api/projects/{projectId}/sarif` |
| **UI** | セキュリティセンター → **エクスポート** → *SARIF* |
| **形式** | SARIF 2.1.0, `application/sarif+json` |

`github/codeql-action/upload-sarif` とスキーマ互換なので、結果は GitHub の **Security** タブに表示されます。CVE ごとに `security-severity` を持つ rule が作られ、影響を受けるコンポーネントごとにマニフェストの位置と `partialFingerprints` を含む result が作られます。保留・無視した項目は `suppressed` として出力されるため、再通知されません。

```yaml
- name: Upload OsWL SARIF
  uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: oswl.sarif
```

### SBOM のインポート

| | |
|---|---|
| **エンドポイント** | `POST /api/sbom/import` (multipart) |
| **UI** | Quick Import → **SBOM をインポート** |

自社でビルドしない対象 — 取引先の納品物、コンテナのベースイメージ、他ツールが生成した SBOM — を外部の CycloneDX ファイルとしてアップロードしてスキャンできます。インポートしたコンポーネントも通常のスキャンと同様に情報が拡充されます。

### コンプライアンスレポートパック

`GET /projects/{projectId}/security-center/compliance-report` は印刷用レポートを描画します。コンポーネント一覧、ライセンス義務、NOTICE 文、深刻度別の未対応項目が含まれます。ブラウザの *印刷 → PDF として保存* を使ってください。プレビュー表示時に印刷ダイアログが自動で開かないため、出力前に内容を確認できます。

---

## CI/CD セキュリティゲート

`POST /api/scan/gate` — スキャン送信と同じ CLI API キーで認証し、プロジェクトはキーから決まります。

レスポンスは機械可読な判定結果です。`exitCode` を CI ジョブの終了コードにそのまま対応させてください（`0` 合格、`1` 失敗）。

既定値（リクエスト単位、または環境変数で上書き可能）:

| 設定 | 環境変数 | 既定値 |
|---|---|---|
| この深刻度以上で失敗 | `OSWL_GATE_FAIL_ON_SEVERITY` | `HIGH` |
| CISA KEV 収載の CVE があれば失敗 | `OSWL_GATE_FAIL_ON_KEV` | `true` |
| EPSS ≥ *n* で失敗（負値で無効） | `OSWL_GATE_FAIL_ON_EPSS` | `0.5` |
| RESTRICTED ライセンスのコンポーネントで失敗 | `OSWL_GATE_FAIL_ON_LICENSE_VIOLATION` | `true` |
| 直前のスキャンに無い新規項目のみ判定 | `OSWL_GATE_ONLY_NEW` | `true` |

既存コードベースにゲートを導入できるかどうかを決めるのが `only-new` です。直前の完了スキャンがベースラインになるため、既存の負債がマージを妨げることはなく、新たに持ち込まれたリスクだけが失敗となります。

リクエストに GitHub の対象を指定すると、判定結果が **Check Run** と PR コメントとしても投稿されます。

リクエスト形式の詳細とパイプライン例は [CLI 連携](CLI-Integration.md) を参照してください。

---

## 継続的モニタリングと CVE アラート

夜間ジョブが各プロジェクトの最新の完了スキャンを OSV に再問い合わせします。最後のスキャン**より後**に公開された CVE も見逃しません。

| 設定 | 環境変数 | 既定値 |
|---|---|---|
| 有効化 | `OSWL_MONITORING_ENABLED` | `true` |
| 実行間隔（Spring cron） | `OSWL_MONITORING_CRON` | `0 0 3 * * *`（毎日 03:00） |

新規脆弱性はプロジェクトカードにアラートとして表示され、プロジェクトメンバーにメールが送られます。確認処理は `POST /projects/{projectId}/cve-alerts/acknowledge` です。

---

## 組織ダッシュボード

`/org-dashboard` は全プロジェクトを CISO 視点の一画面にロールアップします。深刻度の合計、リスクの高いプロジェクトのランキング、KEV 収載 CVE 数、ライセンス警告をまとめて表示します。

アクセスには新しい `ORG_DASHBOARD_VIEW` 権限（または `SYSTEM_ADMIN`）が必要です。権限を付与すると、プロジェクト一覧・プロジェクト詳細・バージョン比較の各画面の上部バーに入口が表示されます。

---

## 優先順位付けのシグナル

* **CISA KEV** — *Known Exploited Vulnerabilities* に収載された脆弱性を最優先で表示します。KEV 収載は実際に悪用が確認されていることを意味するため、トリアージ順では CVSS スコアより優先されます。
* **EPSS** — FIRST.org の悪用予測スコアにより、残りの項目を今後 30 日間の悪用確率で並べ替えます。
* **依存関係の scope** — test／dev 専用の依存関係にはタグが付き、一覧から隠して本番リスクだけを残せます。除外ではなくタグ付けなので、SBOM から消えるものはありません。

---

## サプライチェーンヒューリスティック

`SupplyChainHeuristicsService` がコンポーネント単位で 2 種類のリスクを示し、コンポーネント詳細にバッジとして表示します。

* **既知の悪性パッケージ** — アドバイザリフィードと照合します。
* **タイポスクワッティングの疑い** — 著名パッケージ一覧との Levenshtein 距離により `expres`、`lodahs` のような名前を検出します。

コンポーネント詳細では deps.dev の **OpenSSF Scorecard** スコアも表示されます。まだ脆弱性はないがメンテナンスが止まっている依存関係を、インシデントになる前に把握できます。

---

## 一括アップグレード PR

セキュリティセンター → 一括操作 → **アップグレード PR を作成** で、選択したコンポーネントをすべて修正版に上げる単一のプルリクエストを作成します。マニフェストを直接パッチする方式で、セキュリティ修正に限定した Renovate-lite です。

---

## Jira 連携

設定 → 連携で一度構成すれば（`GET /api/settings/jira`）、検出項目から直接課題を作成できます: `POST /projects/{projectId}/components/{componentId}/jira-ticket`。課題本文は Atlassian Document Format で CVE・深刻度・影響バージョン・修正バージョンを含み、作成されたチケットはコンポーネント詳細からリンクされます。

---

## 閉域網（エアギャップ）モード

外部インターネットのないネットワーク向けのモードです。`OSWL_AIRGAPPED_ENABLED=true` にすると、脆弱性・脅威インテリジェンスの参照（OSV、deps.dev、EPSS、KEV）がライブ API ではなく取り込み済みのオフラインスナップショットから行われ、外向きの HTTP は一切試行されません。

| 操作 | エンドポイント |
|---|---|
| バンドルの取り込み | `POST /api/admin/snapshot/import`（multipart, `SYSTEM_ADMIN`） |
| バンドルの書き出し | `GET /api/admin/snapshot/export` |
| バンドルの状態 | `GET /api/admin/snapshot` |

インターネットに接続された端末で書き出し、バンドルを持ち込んで取り込みます。スナップショットに無いコンポーネントは「脆弱性なし」ではなく**データなし**として扱われます。[組み込み AI](Embedded-AI.md) サイドカーと組み合わせれば、ネットワークを切断した状態でもスキャン・トリアージ・AI 分析まで動作します。

---

## 運用機能

| 機能 | 方法 |
|---|---|
| **Prometheus メトリクス** | `/actuator/prometheus` — micrometer で公開、管理者権限が必要 |
| **ヘルス／情報** | `/actuator/health`、`/actuator/info` |
| **Flyway マイグレーション** | `OSWL_FLYWAY_ENABLED=true` でオプトイン（`baseline-on-migrate`）。既定は `ddl-auto` のまま — [DB スキーマ](Database-Schema.md) 参照 |
| **OIDC シングルサインオン** | `application-prod.yaml` の `spring.security.oauth2.client` ブロックのコメントを解除し、`OSWL_OIDC_CLIENT_ID` / `OSWL_OIDC_CLIENT_SECRET` / `OSWL_OIDC_ISSUER_URI` を設定（Okta、Entra ID など任意の OIDC プロバイダー）。プロバイダーが登録されている場合のみログイン画面に SSO ボタンが表示されます。 |
| **監査ログの SIEM エクスポート** | `GET /api/admin/audit-logs/export?format=jsonl\|cef` — 既存の監査ログフィルターをそのまま使用し、`AUDIT_LOG_EXPORT` 権限が必要です。エクスポート自体も監査記録されます。 |

---

## 新しい権限

管理 → ロールテンプレートで付与します。

| 権限 | 許可される操作 |
|---|---|
| `ORG_DASHBOARD_VIEW` | 組織ダッシュボードの閲覧 |
| `AUDIT_LOG_VIEW` | 監査ログの閲覧 |
| `AUDIT_LOG_EXPORT` | SIEM 向け監査ログのエクスポート |
| `SETTINGS_JIRA_MANAGE` | Jira 連携の管理 |
| `SETTINGS_SNAPSHOT_MANAGE` | オフラインスナップショットバンドルの管理 |

既存のロールテンプレートは変更されていないため、これらは**未付与**の状態から始まります。必要なロールに明示的に付与してください。

---

## 新しいエコシステム

| エコシステム | マニフェスト | purl |
|---|---|---|
| PHP | `composer.lock` | `pkg:composer/<vendor>/<package>@<version>` |
| C/C++ | `conan.lock`（Conan 2.x） | `pkg:conan/<name>@<version>` |

どちらも既存のマニフェスト処理系で解析されるため、ブランチインポート・推移的パス・情報拡充は他のエコシステムと同じように動作します。

---

## 日本語対応

UI、メールテンプレート、ランディングページ、オープンソース表記が**英語・韓国語・日本語**で提供されます。上部バーの言語切り替え（または `?lang=ja`）で変更できます。

AI の出力はサーバーではなく**作業者**に従います。スキャンを開始したユーザーのロケールがスキャン記録に残り、AI Insight の結果もその言語で生成されます。プロンプトテンプレートには韓国語・日本語のオーバーレイ（`ai/prompts_ko.properties`、`ai/prompts_ja.properties`）があり、テンプレートのロケールは設定 → AI で指定します。

---

## アップグレード時の注意

* **スキーマ** — `libraries` テーブルに 2 列（`malicious`、`typosquat_risk`）が追加されます。いずれも `NOT NULL DEFAULT false` なので、データが入った DB でも `ddl-auto=update` で安全に適用されます。手動マイグレーションは不要です。
* **知らないうちに有効になる機能はありません** — ゲート、エアギャップモード、Flyway、OIDC はすべてオプトインです。例外は継続的モニタリングのみで、既定で有効です（`OSWL_MONITORING_ENABLED=false` で無効化できます）。
* **新しい権限は既定で未付与**のため、付与するまで既存ユーザーの画面に変化はありません。
