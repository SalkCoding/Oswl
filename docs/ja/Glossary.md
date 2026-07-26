# 用語集

OsWL で使われるすべての用語 — セキュリティの概念からプラットフォーム固有の語彙まで。

---

## A

**AI インサイト（AI Insight）**
スキャンのエンリッチメント時に生成される LLM 生成のナラティブ要約。OsWL はスキャンごとに 3 種類を生成します: *セキュリティポスチャーインサイト*、*セキュリティリスクトレンドインサイト*、*ライセンスリスクトレンドインサイト*。それぞれ 1 段落の自然言語による評価です。設定で AI プロバイダーが構成されている必要があります。

**監査ログ（Audit Log）**
ユーザーまたはシステムが行ったすべての重要な操作（ログイン、スキャン送信、CVE 状態の変更、設定の更新など）の不変な時系列記録。システム管理者は設定 → 管理者 → 監査ログからアクセスできます。

---

## C

**CARGO**
`cargo` ツールで管理される Rust のパッケージエコシステム。[crates.io](https://crates.io) に公開されたパッケージ。

**CLI（コマンドラインインターフェース）**
OsWL の言語非依存な REST ベースのスキャン送信手段。任意のビルドツールや CI パイプラインが API キーを使ってスキャンペイロードを POST できます。[CLI 連携](CLI-Integration.md)を参照してください。

**コンポーネント（Component）**
スキャンで検出された個々のオープンソースライブラリ。OsWL 内では (名前、バージョン、エコシステム) をキーとする `Library` エンティティとして表現されます。UI では*コンポーネント*は*依存関係*・*ライブラリ*と同じ意味で使われます。

**CVE（Common Vulnerabilities and Exposures）**
グローバルに一意な ID（例: `CVE-2021-44228`）で識別される、公に開示されたセキュリティ脆弱性。各 CVE は 1 つ以上の影響を受けるライブラリバージョンに関連付けられ、CVSS スコアを持ちます。

**CVSS（Common Vulnerability Scoring System）**
セキュリティ脆弱性の深刻度を評価する業界標準のフレームワーク。CVSS ベーススコアは 0.0〜10.0 の範囲を取り、OsWL では深刻度ラベル（CRITICAL、HIGH、MEDIUM、LOW）にマッピングされます。

| スコア範囲 | OsWL の深刻度 |
|---|---|
| 9.0〜10.0 | CRITICAL |
| 7.0〜8.9 | HIGH |
| 4.0〜6.9 | MEDIUM |
| 0.1〜3.9 | LOW |
| 0.0 | NONE |

**CycloneDX**
OWASP の SBOM 標準。OsWL は CycloneDX 1.6 の SBOM・VEX 文書をエクスポートし、サードパーティの CycloneDX ファイルをインポートできます（v1.0.4）。[v1.0.4 の新機能](Whats-New-v1.0.4.md)を参照してください。

**CWE（Common Weakness Enumeration）**
脆弱性の*種類*を表すカテゴリ識別子（例: `CWE-79` クロスサイトスクリプティング）。OsWL は OSV の `database_specific.cwe_ids` から取得した最初の CWE を各 `library_cves` 行に保存し、コンポーネント詳細に表示します。

---

## D

**deps.dev**
OsWL が各スキャン対象ライブラリの SPDX ライセンス識別子、最新バージョン状態、非推奨通知を取得するために問い合わせる、Google の [Open Source Insights](https://deps.dev) API。

**依存関係パス（Dependency Path）**
ルートプロジェクトから特定のライブラリまでのパッケージの連鎖。ライブラリは複数のパス（直接的・推移的、あるいはその両方）を通じて到達可能な場合があります。OsWL はコンポーネントごとに解決されたすべてのパスを記録・表示します。

**デプロイメントプロファイル（Deployment Profile）**
スキャン対象の製品が実際にどのように配布されるかを示す値 — `SAAS`、`INTERNAL_TOOL`、`ON_PREMISE_DISTRIBUTION`、または既定値の `COMMERCIAL_PRODUCT`。AI トリアージとライセンスリスクの重み付けを調整します: 同じ CVE やコピーレフトライセンスでも、社内専用ツールと顧客に出荷されるソフトウェアでは露出度が大きく異なります。プロジェクトごとに設定するか（`PATCH /api/projects/{id}/deployment-profile`）、AI 設定の**既定のデプロイメントプロファイル**にフォールバックします。

**直接依存関係（Direct Dependency）**
プロジェクトのマニフェスト（例: `pom.xml`、`package.json`）に明示的に宣言されているライブラリ。*推移的依存関係*と対比されます。

---

## E

**エコシステム（Ecosystem）**
ライブラリが属するパッケージ管理システム。OsWL がサポートするもの: `MAVEN`、`NPM`、`PYPI`、`GO`、`CARGO`、`NUGET`、`RUBYGEMS`、そして v1.0.4 以降は `COMPOSER`（PHP、`composer.lock`）と `CONAN`（C/C++、`conan.lock`）。

**EPSS（Exploit Prediction Scoring System）**
今後 30 日以内に脆弱性が悪用される確率を推定する FIRST.org のスコア（0〜1）。v1.0.4 で追加され、KEV カタログに載っていない検出結果の優先順位付けに使われます。`OSWL_GATE_FAIL_ON_EPSS` でゲートの閾値として設定可能です。

**エンリッチメント（Enrichment）**
スキャンが取り込まれた後の非同期な後処理段階。OsWL は OSV と deps.dev に問い合わせ、検出されたすべてのライブラリについて CVE データ、CVSS スコア、CWE ID（OSV から）、修正バージョン、ライセンス名、バージョン状態を埋めます。再取得の挙動は**設定 → キャッシュ**（`cache_settings` テーブル）で制御されます。

---

## F

**修正バージョン（Fix Version）**
関連する CVE が修正された、ライブラリの最も早いバージョン。OSV のアドバイザリデータから取得されます。セキュリティセンターでは推奨アップグレード先として表示されます。

**誤検知（False Positive）**
その脆弱性がこのプロジェクトには該当しないことが確認された、という CVE のトリアージ状態（例: 脆弱なコードパスが実行されない、影響を受ける機能が使われていないなど）。

---

## G

**GO**
Go プログラミング言語のモジュールエコシステム。`github.com/gin-gonic/gin` のようなインポートパスで識別されるパッケージ。

---

## K

**KEV（Known Exploited Vulnerabilities）**
実際に悪用が確認された脆弱性の CISA によるカタログ。OsWL は KEV に収載された CVE を最優先で表示します（v1.0.4）。*実際に悪用されている*ことは、高い CVSS スコアよりも強いトリアージのシグナルだからです。`OSWL_GATE_FAIL_ON_KEV` で CI ゲートを失敗させる条件にできます。

---

## L

**ライブラリ（Library）**
(名前、バージョン、エコシステム) をキーとする、オープンソースパッケージの OsWL における正規のレコード。ライブラリレコードはすべてのプロジェクトにまたがって共有されます — 2 つのプロジェクトが同じバージョンの `spring-core` を使っている場合、その CVE とライセンスデータは一度だけ保存されます。

**ライセンス（License）**
オープンソースライブラリが配布される際の法的条件。OsWL は SPDX 識別子を使ってライセンスを検出し、設定されたポリシーと照合して評価します。

**ライセンスポリシー（License Policy）**
SPDX ライセンス識別子をコンプライアンス状態（PERMITTED、CAUTION、RESTRICTED）にマッピングする、管理者が管理するテーブル。すべてのプロジェクトにグローバルに適用されます。

**ライセンス状態（License Status）**
ポリシー評価後にライブラリのライセンスへ割り当てられるコンプライアンス状態:

| 状態 | 意味 |
|---|---|
| `PERMITTED` | ポリシーにより明示的に許可 |
| `CAUTION` | レビューが必要 — 潜在的なコピーレフトまたは商用制限 |
| `RESTRICTED` | ポリシーにより非互換とフラグ付け |
| `UNKNOWN` | SPDX 識別子が未取得または未認識 |

---

## M

**悪性パッケージ（Malicious Package）**
OSV が `MAL-` 接頭辞のアドバイザリ ID でフラグを立てたパッケージバージョン — 通常の（それ以外は正当な）コードにおける一般的な脆弱性ではなく、利用者を侵害する目的で最初から公開されたことを意味します。OsWL はこれらを自動的に `CRITICAL` 深刻度に昇格させ、コンポーネント詳細とセキュリティセンターの一覧に赤い**悪性（Malicious）**バッジを表示します。

**MAVEN**
Apache Maven が管理する Java/JVM エコシステム。`groupId:artifactId` として識別されるパッケージ。

**MFA / 2FA（多要素認証／二要素認証）**
標準のメールアドレス＋パスワードに加えて、メールで送信されるワンタイムパスワード（OTP）を要求する追加のログインステップ。設定 → セキュリティでグローバルに設定可能です。

---

## N

**NPM**
`npm` ツールで管理される Node.js/JavaScript のパッケージエコシステム。[npmjs.com](https://www.npmjs.com) に公開されたパッケージ。

**NUGET**
.NET のパッケージエコシステム。[nuget.org](https://www.nuget.org) に公開されたパッケージ。

---

## O

**OsWL（Open-source Software Watchlist）**
本ドキュメントで説明されているプラットフォーム — OSS 依存関係全体にわたる CVE とライセンスリスクを追跡する社内向け SCA ツール。

**OSV（Open Source Vulnerabilities）**
オープンソースパッケージに焦点を当てた、Google がホストする脆弱性データベースおよび API（[osv.dev](https://osv.dev)）。OsWL は影響を受けるバージョン範囲や修正バージョンを含むアドバイザリデータを OSV に問い合わせます。

**OTP（ワンタイムパスワード）**
2FA 認証の第 2 要素として、ユーザーのメールアドレスに送信される 6 桁のコード。ローカル開発プロファイルでは、OTP コードはサーバーログに `*** OTP CODE: NNNNNN ***` として表示されます。

---

## P

**パッチ適用可否（Patchability）**
ライブラリに影響する脆弱性に対して修正が利用可能かどうかを示す導出プロパティ:

| 値 | 意味 |
|---|---|
| `PATCHABLE` | 少なくとも 1 件の CVE に既知の `fixVersion` がある |
| `NON_PATCHABLE` | CVE は存在するが、文書化された修正がない |
| `UNKNOWN` | CVE がない、またはエンリッチメントがまだ完了していない |

**purl（package URL）**
パッケージの標準的な座標文字列。例: `pkg:maven/org.springframework/spring-core@6.1.0`。OsWL は SBOM／VEX／SARIF のエクスポートで purl を出力し、SBOM インポート時にはそれを読み取ります（v1.0.4）。

**PAT（Personal Access Token）**
VCS プロバイダー（GitHub、GitLab、Bitbucket）が発行する、ユーザーアカウントに代わって API アクセスを許可するシークレットトークン。OsWL では Quick Import とリポジトリ参照に使用されます。

**権限（Permission）**
ロールテンプレートを通じてユーザーに割り当てられる、きめ細かなアクセス制御機能。例: `PROJECT_VIEW`、`SCAN_SUBMIT`、`SECURITY_CENTER_UPDATE_STATUS`。

**プロジェクト（Project）**
分析対象のアプリケーションまたはサービスを表す、OsWL における最上位のエンティティ。1 つのプロジェクトは 1 つのリポジトリ（または CLI 専用プロジェクトの場合は 1 つの論理単位）に対応します。

**プロジェクトバージョン（Project Version）**
特定の VCS ブランチに紐づくプロジェクトのスナップショット。Quick Import によって自動作成されます。

**PYPI**
Python のパッケージインデックス。[pypi.org](https://pypi.org) に公開されたパッケージ。

---

## Q

**Quick Import**
リポジトリとブランチを選択することで、VCS プロバイダー（GitHub / GitLab / Bitbucket）から直接プロジェクトをインポートする、ブラウザベースのワークフロー。OsWL はリポジトリをクローンし、依存関係を解決してスキャンを実行します — CLI は不要です。

---

## R

**リスクレベル（Risk Level）** → *深刻度（Severity）*を参照

**リスクトレンド（Risk Trend）**
プロジェクトの直近のスキャンにわたって、CVE 件数とライセンスコンプライアンス状態がどう変化してきたかを示す時系列の可視化。

**ロールテンプレート（Role Template）**
インスタンス全体にわたる、名前付きの**権限**の集合（例: Admin、Developer、Viewer）。**設定 → 管理者**でユーザーに割り当てられます。ユーザーが OsWL 全体でどの機能を使えるかを制御します。プロジェクトメンバーシップとは**異なります**。[権限レイヤー](Authorization-Layers.md)を参照してください。

**プロジェクトメンバーシップ（Project membership）**
ユーザーを特定のプロジェクトに紐づける `project_members` の行。ユーザーがどのプロジェクトを開けるかを制御します。ロールテンプレートの権限と連携して機能します。プロジェクトメンバーシップのロール（`ADMIN` / `MEMBER`）はテンプレート名とは別の概念です。

**システム管理者（System administrator）**
初期セットアップ時に設定されるユーザーフラグ。ユーザー、ロールテンプレート、監査ログを管理でき、メンバーシップに関係なくすべてのプロジェクトにアクセスできます。「Admin」という名前のロールテンプレートとは別物です。

**RUBYGEMS**
`gem` ツールで管理される Ruby のパッケージエコシステム。[rubygems.org](https://rubygems.org) に公開されたパッケージ。

---

## S

**SARIF（Static Analysis Results Interchange Format）**
ツールの検出結果に関する OASIS 標準、バージョン 2.1.0。OsWL の SARIF エクスポート（v1.0.4）は `github/codeql-action/upload-sarif` とスキーマ互換であるため、結果が GitHub の Security タブに表示されます。

**SBOM（Software Bill of Materials）**
ビルドに含まれるすべてのコンポーネントの機械判読可能な一覧。OsWL は SPDX（ライセンス重視）と CycloneDX 1.6（v1.0.4）の SBOM を生成し、他のツールが出力した CycloneDX ファイルをインポートできます。

**Scorecard（OpenSSF）**
メンテナンス、レビュー慣行、ビルドセキュリティを評価する、deps.dev による 0〜10 のプロジェクト健全性スコア。コンポーネント詳細に表示され（v1.0.4）、まだ脆弱性はないもののメンテナンスが止まっている依存関係を早期に把握できます。

**SCA（Software Composition Analysis）**
ソフトウェアプロジェクトで使われる OSS コンポーネントを識別・評価する取り組み — 特にセキュリティ脆弱性とライセンスコンプライアンスの観点から。OsWL は SCA プラットフォームです。

**スキャン（Scan）**
あるプロジェクトバージョンに対する依存関係分析パイプラインの 1 回の実行。スキャンは、検出されたコンポーネントの一覧と、それらの解決済み CVE・ライセンスデータで構成されます。Quick Import または CLI（`POST /api/scan`）によってトリガーされます。

**スキャン状態（Scan Status）**

| 状態 | 説明 |
|---|---|
| `PENDING` | 受信済み、処理待ち |
| `SCANNING` | 依存関係マニフェストを解析中 |
| `ANALYZING` | CVE・ライセンスデータをエンリッチ中 |
| `COMPLETED` | エンリッチメント完了、結果が確定 |
| `FAILED` | 回復不能なエラーが発生 |

**セキュリティセンター（Security Center）**
プロジェクトの主要な脆弱性管理ページ。スキャンされたコンポーネントに影響するすべての CVE を、フィルタリング、ソート、状態管理、AI インサイト付きで表示します。

**深刻度（Severity）**
CVSS スコアに基づく CVE のリスクレベルの分類。OsWL が使用する分類: CRITICAL、HIGH、MEDIUM、LOW、NONE。

**単一セッション強制（Single-Session Enforcement）**
OsWL はユーザーごとに 1 つのアクティブなセッションのみを許可します。別のブラウザ／デバイスからの新しいログインは、以前のセッションを無効化します。

**SPDX（Software Package Data Exchange）**
ライセンス識別子を含む、SBOM 情報を伝達するためのオープン標準。OsWL は SPDX 識別子（例: `MIT`、`Apache-2.0`、`GPL-3.0-only`）を使ってライブラリのライセンスを表現します。

**SPDX 識別子（SPDX Identifier）**
特定のソフトウェアライセンスを識別する標準化された短い文字列。例: `MIT`、`Apache-2.0`、`GPL-3.0-only`。全リストは[spdx.org/licenses](https://spdx.org/licenses/)で管理されています。

---

## T

**タイポスクワッティング（Typosquatting）**
有名なパッケージ名に近い（一文字違いなどの）名前でパッケージを公開するサプライチェーン攻撃（`express` に対する `expres` など）。OsWL は有名パッケージの一覧との Levenshtein 距離を使って候補を検出し（v1.0.4）、コンポーネント詳細にバッジを表示します。

**推移的依存関係（Transitive Dependency）**
プロジェクトのマニフェストに直接宣言されてはいないが、直接依存関係（またはさらに深い依存関係）の依存関係として取り込まれるライブラリ。*直接依存関係*と対比されます。

**信頼済みデバイス（Trusted Device）**
2FA の OTP 認証に成功した後、信頼済みとしてマークされたブラウザ。信頼済みデバイスは、設定された信頼期間（既定: 30 日間）内であれば、以降のログインで OTP ステップをスキップします。

---

## V

**VCS（Version Control System）**
GitHub、GitLab、Bitbucket などのソースコードホスティングプラットフォーム。OsWL は Quick Import のために Personal Access Token 経由で VCS プロバイダーに接続します。

**VCS 接続（VCS Connection）**
OsWL が VCS API に対して認証するために使用する、保存・暗号化された PAT とプロバイダー設定。設定 → VCS で管理します。

**バージョン比較（Version Diff）**
2 つのスキャン結果を比較し、バージョン間でどのコンポーネントが追加・削除・変更されたかを示すもの。

**VEX（Vulnerability Exploitability eXchange）**
製品が実際に含んでいる脆弱性の影響を受けるかどうかを機械判読可能な形で示す文書。OsWL はトリアージ判断から CycloneDX VEX をエクスポートします（v1.0.4）: 無視した検出結果は `not_affected`、保留は `in_triage`、修正済みは `resolved` になります。

**脆弱性（Vulnerability）** → *CVE* を参照

---

## Z

**ゼロデイ（Zero-Day）**
公に知られているが、まだ公式パッチが存在しない脆弱性（修正バージョンが null）。OsWL は修正バージョンが公開されるまで、こうした CVE を `NON_PATCHABLE` としてマークします。
