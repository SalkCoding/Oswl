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

各インポートは独自の進捗カードを持つ非同期の**ジョブ**として実行されます:

| フェーズ | 説明 |
|---|---|
| `QUEUED` | ワーカースロットの空き待ち、または間もなく開始 |
| `CLONING` | リポジトリをシャロークローン中 |
| `PARSING` | エコシステムの検出と依存関係マニフェストの解析 |
| `SCANNING` | プロジェクトを作成しスキャンペイロードを送信 |
| `ENRICHING` | CVE／ライセンスのエンリッチメントと任意の AI 要約 |
| `DONE` | インポート完了 — プロジェクトと API キーが利用可能 |
| `FAILED` | エラーまたはキャンセル — ジョブのメッセージを確認 |

- 同時実行できるインポートは最大**2 件**です（`oswl.quick-import.max-concurrent`、既定 `2`）。それ以降のジョブはキュー（FIFO）に入り、`queuePosition` で待機順が確認できます。
- 前のインポートの完了を待たずに**複数のインポート**を開始できます。
- すでにキュー中または実行中のジョブがあるリポジトリに対してインポートを開始すると **409 Conflict** で拒否されます — 完了を待つか、先にキャンセルしてください。
- 各ジョブカードには**キャンセル**ボタンがあります（`POST /api/quick-import/job/{jobId}/cancel`）: キュー中のジョブは即座に停止し、実行中のジョブは次のフェーズの境界で停止します（実行中の長いクローンはまず完了します）。キャンセルされたジョブは "canceled" メッセージ付きの `FAILED` として表示されます。
- UI は **`GET /api/quick-import/job/{jobId}/stream`**（SSE イベント `job-update`）を購読し、必要に応じて `GET /api/quick-import/job/{jobId}` のポーリングにフォールバックします。
- `ENRICHING` 中は、ジョブが `percent`（0〜100）、`subPhase`（`CVE`、`LICENSE`、`POSTURE`、`TREND`、`DIFF`）、`detailLines`、そして AI エンリッチメントが有効な場合は `aiPreviews` を公開します。

取り込み完了後、一時的なクローンディレクトリは削除されます。

### CLI と共通のパーサー

依存関係の検出とマニフェスト解析には **`DependencyManifestParserService`** を使用します — これは公式 CLI（`oswl scan`）と同じエンジンです。CLI は `GET /api/scan/manifest-rules`（静的コピー: `/scripts/manifest-rules.json`）に従って収集したマニフェストファイルの zip をアップロードし、Quick Import はリポジトリをシャロークローンして同じルールでツリーを走査します。[CLI 連携](CLI-Integration.md)を参照してください。

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
