# Quick Import

Quick Import を使うと、CLI のコードを書かずに **GitHub**、**GitLab**、**Bitbucket** から直接プロジェクトを取り込めます。

---

## 対応プロバイダー

| プロバイダー | 認証方式 |
|---|---|
| GitHub | Personal Access Token (PAT) |
| GitLab | Personal Access Token (PAT) |
| Bitbucket | App Password |

---

## ステップ 1 — VCS 接続の追加

**設定 → VCS** で **接続を追加** をクリックします。

| 項目 | 説明 |
|---|---|
| **プロバイダー** | GitHub / GitLab / Bitbucket |
| **表示名** | わかりやすいラベル（例: "GitHub – my-org"） |
| **アクセストークン** | `repo` / `read_repository` スコープを持つ PAT または App Password |

OsWL はトークンをプロバイダー API に対して即座に検証します。トークンは**保存時に暗号化**されます（本番環境では `OSWL_ENCRYPTION_KEY` を使用）。

> 必要な権限: `SETTINGS_VCS_MANAGE` またはシステム管理者。

---

## ステップ 2 — リポジトリのインポート

**プロジェクト → Quick Import**（`/projects/quick-import`）を開きます。

次のいずれかの方法を選べます:

1. **リポジトリ URL を貼り付け**（ブランチは任意）て **インポート＆スキャン** をクリックする、または
2. 接続済みアカウントを**参照**する — プロバイダーの一覧からリポジトリとブランチを選択します。GitHub の一覧取得は API の `Link` ヘッダーによるページネーションに従います（1 ページ 100 件、アカウント／組織あたり最大 1,000 件）。大規模なアカウントは読み込み失敗ではなく、切り詰めて表示されます。

### 進捗と並行実行

各インポートは、独自の進捗カードを持つ非同期の**ジョブ**として実行されます:

| フェーズ | 説明 |
|---|---|
| `QUEUED` | ワーカースロットの空き待ち、または間もなく開始 |
| `CLONING` | リポジトリのクローン — 既定ではマニフェストファイルのみを取得する blobless sparse checkout で、フルのシャロークローンへのフォールバックもあります |
| `PARSING` | エコシステムの検出と依存関係マニフェストの解析 |
| `SCANNING` | プロジェクトを作成しスキャンペイロードを送信 |
| `ENRICHING` | CVE／ライセンスのデータパイプライン（deps.dev、OSV、脅威インテリジェンス） |
| `DONE` | データパイプライン完了 — CVE／ライセンス結果は準備済み。AI 要約はバックグラウンドで生成中の場合があります |
| `FAILED` | エラーまたはキャンセル — ジョブのメッセージを確認 |

ジョブカードはフェーズに加えて、次のようなライブフィールドを公開します:

| フィールド | 説明 |
|---|---|
| `percent` | 0〜100 の連続的な進捗率。帯域は `CLONING` 5–20、`PARSING` 20–40（マニフェスト N/M 件処理）、`SCANNING` 40–55、`ENRICHING` のデータ取得 55–80、AI ブロック 80–100 です。 |
| `subPhase` | 現在のエンリッチメントブロック: `CVE`、`LICENSE`、`INSIGHTS`（ポスチャー・セキュリティ／ライセンストレンド・バージョン比較のインサイトは v1.0.4 で 1 回の統合呼び出しにまとめられました）。 |
| `detailLines` | バッチ進捗やライブラリごとの検出サマリーなどのローリングログ行。 |
| `aiPreviews` | 自由形式の AI 出力のローリングテール。v1.0.4 以降、統合インサイト呼び出しはストリーミングテキストではなく JSON を返すため、通常のスキャンではこのフィールドは埋まりません。 |
| `aiStatus` | `NOT_APPLICABLE`、`PENDING`、`RUNNING`、`COMPLETED`、`FAILED` のいずれか。`aiStatus` がまだ `PENDING` や `RUNNING` の間でも、ジョブは `DONE` に到達することがあります。 |
| `cacheTotal` / `cacheHit` / `cacheToFetch` | deps.dev のキャッシュ判定。少なくとも 1 件のコンポーネントがキャッシュから提供された場合、キャッシュヒットバッジとして表示されます。 |

並行実行と配信:

- 同時実行できるインポートは最大**3 件**です（`oswl.quick-import.max-concurrent`、既定 `3`）。それ以降のジョブはキュー（FIFO）に入り、`queuePosition` で待機順が確認できます。ユーザーごとにキューに入れられるジョブは最大**3 件**です（`oswl.quick-import.max-queued-per-user`、既定 `3`）。
- 前のインポートの完了を待たずに**複数のインポート**を開始できます。
- すでにキュー中または実行中のジョブがあるリポジトリに対してインポートを開始すると **409 Conflict** で拒否されます — 完了を待つか、先にキャンセルしてください。
- 各ジョブカードには**キャンセル**ボタンがあります（`POST /api/quick-import/job/{jobId}/cancel`）: キュー中のジョブは即座に停止し、実行中のジョブは次のフェーズの境界で停止します（実行中の長いクローンはまず完了します）。キャンセルされたジョブは "canceled" メッセージ付きの `FAILED` として表示されます。ジョブが `DONE` に到達した後も、実行中の AI エンリッチメントは独立して継続し、Quick Import ジョブのキャンセルによって停止されることはありません。
- UI は **`GET /api/quick-import/job/{jobId}/stream`**（SSE イベント `job-update`）を購読し、必要に応じて `GET /api/quick-import/job/{jobId}` のポーリングにフォールバックします。進捗率が進まない限り、高頻度の更新は 500ms あたり 1 フレームの SSE に抑制されます。
- スキャンが完了すると、フェーズごとの所要秒数（`clone`、`parse`、`ingest`、`depsdev`、`osv`、`threatintel`、`ai.*`、`cleanup`）をまとめた `[Timing]` INFO ログが 1 行出力されます。

一時的なクローンディレクトリは、取り込み後に非同期削除のキューに入ります。

### CLI と共通のパーサー

依存関係の検出とマニフェスト解析には **`DependencyManifestParserService`** を使用します — これは公式 CLI（`oswl scan`）と同じエンジンです。CLI は `GET /api/scan/manifest-rules`（静的コピー: `/scripts/manifest-rules.json`）に従って収集したマニフェストファイルの zip をアップロードし、Quick Import はリポジトリをクローンして（既定では blobless sparse checkout）同じルールでツリーを走査します。[CLI 連携](CLI-Integration.md)を参照してください。

### パフォーマンスに関する注意事項

v1.0.4 で導入されたいくつかのパイプライン変更が、Quick Import の速度とリソース使用量に影響します。

- **クローン**: 既定の blobless sparse checkout（`--filter=blob:none --sparse`）は、パーサーが必要とするマニフェストファイルのパターンのみを取得します。部分クローンに対応していないサーバー、またはビルドツール実行モード（`oswl.quick-import.allow-build-exec=true`）の場合は、フルのシャロークローンにフォールバックします。
- **解析**: クローンされたツリーを正確に 1 回だけ走査してマニフェストインデックスを構築します — 従来のエコシステムごとの複数回の走査を置き換えます。
- **クリーンアップ**: 一時クローンディレクトリは非同期に削除されるため、削除処理がフェーズ遷移をブロックしなくなりました。
- **エンリッチメント用 HTTP クライアント**: deps.dev と OSV のクライアントは明示的な接続／読み取りタイムアウトを使用します。deps.dev のリクエストは固有の `(ecosystem, name, version)` で重複排除され、バージョンメタデータのキャッシュヒット時は独自の TTL で再取得をスキップし、並行数は `oswl.client.deps-dev.max-concurrent`（既定 `24`）で設定できます。
- **取り込み（Ingest）**: ライブラリは一括クエリで解決され、チャンク単位で保存されます。CVE の脅威インテリジェンス更新はバッチ処理されます。
- **AI**: 同一の CVE／ライセンスコンテキスト（深刻度、CVSS、修正バージョン、EPSS バケット、KEV ステータス、デプロイメントプロファイルなどからハッシュ化）は、以前の AI 要約を再利用します。ポスチャー、トレンド、差分のインサイトは 1 回の統合呼び出しで生成されます。
- **エアギャップモード**: `oswl.airgapped.enabled=true` の場合、スキャン結果ページと SBOM／VEX／SARIF エクスポートに脆弱性定義の基準日バナーが表示されます。

---

## SBOM をインポートする方法（v1.0.4）

ソースをクローンできない場合 — 取引先の納品物、コンテナのベースイメージ、他ツールが生成した SBOM など — は、代わりに CycloneDX ファイルをアップロードできます:

**Quick Import → SBOM をインポート**、または `POST /api/sbom/import`（multipart）。

コンポーネントは CycloneDX の `components` 配列から `purl` を使って読み取られ、クローンしたスキャンとまったく同じようにエンリッチされます: CVE、ライセンス、KEV／EPSS、サプライチェーンバッジがすべて適用されます。

---

## ブランチの再インポート

同じリポジトリ／ブランチをいつでも再度インポートして、新しいスキャン結果を作成できます。結果は[バージョン比較](Version-Diff.md)と[リスクトレンド](Risk-Trend.md)で比較できます。

---

## GitHub Enterprise Server (GHES)

```bash
OSWL_GITHUB_API_BASE=https://github.example.com/api/v3
```

GitLab と Bitbucket のセルフホスト版も、VCS 接続の API ベース URL で対応できます。

---

## REST API 概要

[API リファレンス — Quick Import](API-Reference.md#quick-import)を参照してください。対話的なスキーマ: Swagger UI（`local` プロファイル）。

---

## トラブルシューティング

| 症状 | 考えられる原因 |
|---|---|
| "Token validation failed" | PAT のスコープ不足（`repo` / `read_repository`）またはトークンの期限切れ |
| "Repository not found" | トークンでアクセスできないプライベートリポジトリ |
| `CLONING` / `PARSING` で止まる | OsWL ホストのネットワークまたはディスク容量の問題 |
| `ENRICHING` で止まる | 外部 API（OSV／deps.dev）のレート制限。パイプライン内でリトライが継続します |
| ポーリング時にジョブが `404` | サーバーが再起動された — インメモリのジョブは約 30 分で失効します |
