# はじめに

このガイドでは、OsWL のインストール、セットアップウィザードの実行、最初のプロジェクトスキャンの完了までを説明します。

---

## システム要件

| コンポーネント | 要件 |
|---|---|
| **JDK** | 25 以降 |
| **ビルドツール** | Gradle Wrapper（同梱 — `./gradlew`） |
| **データベース** | H2 ファイルモード（ローカル／開発用）または PostgreSQL 15 以降（本番用） |
| **OS** | Linux、macOS、Windows |
| **メモリ** | 最小 512 MB、1 GB 以上を推奨 |

> Node.js や npm は不要です — Tailwind CSS のスタンドアロンバイナリは初回ビルド時に Gradle が自動でダウンロードします。

---

## インストール

### 1. リポジトリをクローン

```bash
git clone https://github.com/SalkCoding/Oswl.git
cd Oswl
```

### 2. プロファイルを選択

OsWL には 2 つの Spring プロファイルが用意されています。

| プロファイル | データベース | 用途 |
|---|---|---|
| `local`（既定） | H2 ファイル（`./oswl-db.mv.db`） | 開発・評価用 |
| `prod` | PostgreSQL | 本番デプロイ用 |

### 3. アプリケーションを起動

**ローカル（H2、設定不要）:**

```bash
./gradlew bootRun
```

**本番（PostgreSQL）:**

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL=jdbc:postgresql://localhost:5432/oswl
export DB_USERNAME=oswl
export DB_PASSWORD=changeme
export OSWL_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun
```

> **`OSWL_ENCRYPTION_KEY`** — VCS トークンなど保存された機密情報を保護する鍵です。`local` では開発用の鍵が自動生成されることがあります。**`prod`** では起動前に必ず固定値を設定してください。設定しないとアプリケーションは起動しません。鍵を紛失すると、それまでに保存された VCS 認証情報は使用できなくなります。

アプリケーションは既定でポート **8080** で起動します。

---

## セットアップウィザード

初回起動時（データベースが空の状態）は、すべてのリクエストが `http://localhost:8080/setup` にリダイレクトされます。

ウィザードで入力する項目:

| 項目 | 説明 |
|---|---|
| **管理者メールアドレス** | システム管理者アカウントのログイン認証情報として使用 |
| **パスワード** | 最小長ポリシーを満たす必要あり（既定: 8 文字） |
| **表示名** | UI と監査ログに表示 |

送信後、OsWL は管理者アカウントを作成し、ログインページにリダイレクトします。

> ローカルモードでクリーンな状態からやり直したい場合は、サーバーを停止して `oswl-db.mv.db`（存在する場合は `oswl-db.trace.db` も）を削除してから再起動してください。

---

## 初回ログイン

1. `http://localhost:8080/login` にアクセスします。
2. セットアップウィザードで作成したメールアドレスとパスワードを入力します。
3. **二要素認証（Two-Factor Authentication）** が有効な場合（管理者が設定可能）、メールで送信される 6 桁の OTP の入力を求められます。
   * `local` モードでは OTP がサーバーログに出力されます: `*** OTP CODE: NNNNNN ***`
   * 開発用ショートカット: test プロファイル使用時は `000000` が受け付けられます。
4. 仮パスワードでの初回ログイン時、OsWL はその場でのパスワード変更を強制します。

---

## テストデータの投入（ローカル専用）

ログイン後、次の URL を呼び出します:

```
GET http://localhost:8080/data/test
```

このエンドポイントは `local` プロファイルでのみ利用可能で、次を行います:

* 既存のすべてのプロジェクト・スキャン・ライブラリ・CVE を削除します。
* Maven・npm など複数エコシステムにまたがる複数プロジェクト、様々な深刻度の数十件の CVE、混在するライセンス状態、トレンド可視化用の複数の過去スキャンなど、リアルなデータセットでデータベースを再構築します。

テスト用の API キーも次で取得できます:

```
GET http://localhost:8080/data/test-api-key
```

---

## アクセス制御（推奨読み物）

* [権限レイヤー](Authorization-Layers.md) — ロールテンプレート（Admin / Developer / Viewer）とプロジェクトメンバーシップの違い
* [運用デプロイチェックリスト](Production-Deployment-Checklist.md) — `prod` で本番運用を始める前に

## 次のステップ

* [最初の VCS リポジトリを接続する](Quick-Import.md)
* [CLI でスキャンを送信する](CLI-Integration.md)
* [セキュリティセンターを見てみる](Security-Center.md)
