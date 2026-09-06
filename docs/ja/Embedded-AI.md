# 内蔵AI

OsWLはllama.cppを `http://127.0.0.1:11435/v1` で実行し、LOCALに登録します。推論データはサーバー内で処理され、インストールにはネットワークまたはオフライン転送が必要です。**CPU既定はQwen3.5-2B Q4_K_M、選択肢はGemma 4 E2B Q4_K_Mです。** 一度に1モデルを実行します。テキスト専用でGPU・画像や音声のプロジェクターは不要です。

## 要件とフォルダー

| モデル | 重みダウンロード | サーバー全体の想定最小 | 推奨 |
|---|---:|---|---|
| Qwen3.5 2B Q4_K_M | 1.28 GB | 2 vCPU / RAM 8 GB | 4 vCPU / RAM 8～16 GB |
| Gemma 4 E2B Q4_K_M | 3.11 GB | 4 vCPU / RAM 8 GB | 4 vCPU / RAM 16 GB |

これは**性能保証ではなく容量見積もり**です。OsWLとPostgreSQLの同居、4K～8Kコンテキスト、小規模スキャンと生成を1件ずつ断続的に処理する条件です。大きなアーカイブ・同時スキャン・DB増加には追加容量が必要です。ファイルサイズは最大メモリではありません。重みに空きディスク6 GB以上を確保し、ランタイム・DB・ログ・旧モデルは別途計算します。ビルド要件は含みません。

Qwenの既定採用はCPU共有サーバーに合う小さな重みが理由であり、常に高精度という意味ではありません。実際の英語・韓国語・日本語で検証してください。バーストvCPU数は持続性能を保証しません。クレジット枯渇時も確認し、連続分析は持続性能のあるCPUまたは別CPUワーカーを検討します。GPUは必須ではありません。

OS・アーキテクチャに合うCPUランタイムと同じ配布物のライブラリを使用します。ローカル検証基準はllama.cpp **b10068 (571d0d540)**です。両モデルと `--chat-template-kwargs`、`-fa on` をサポートする互換版が必要です。

```text
embedded-ai/
  llama/
    llama-server(.exe)
    ... companion libraries ...
  model/
    Qwen/Qwen3.5-2B-Q4_K_M.gguf
    Gemma/gemma-4-E2B-it-Q4_K_M.gguf
  llama-server.log
```

| Model | Bytes | SHA-256 |
|---|---:|---|
| Qwen3.5-2B Q4_K_M | 1280835840 | `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223` |
| Gemma 4 E2B Q4_K_M | 3106738272 | `740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8` |

- [Qwen model card](https://huggingface.co/Qwen/Qwen3.5-2B)
- [Qwen GGUF, pinned revision](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/f6d5376be1edb4d416d56da11e5397a961aca8ae)
- [Gemma model card](https://huggingface.co/google/gemma-4-E2B-it)
- [Gemma GGUF, pinned revision](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/tree/0314792d7f1f7e229411f620751375812bb9faf2)
- [llama.cpp releases](https://github.com/ggml-org/llama.cpp/releases)
- [License notices](../../THIRD_PARTY_LICENSES.md#embedded-ai-runtime-and-models)

設定はファミリーフォルダーではなくルートを指定します。`model/` と直下のファミリーを探索します。旧ルートGGUFおよびルート/bin実行ファイルも認識し、同名はmodel側を優先します。`mmproj*`、`mtp-*`、`imatrix*` とルート外を指すファイルは除外します。

ほかのインストールの旧モデルは自動削除・交換しません。不要なら探索対象外へ移動します。保存モデルが消えた場合はインストール済みの既定優先順位に戻ります。

プロジェクトの多言語プロンプトで両モデルの英語・韓国語・日本語の応答を確認しました。ただし一部のQwen応答では、入力した修正バージョンや件数が誤って生成されました。これは実行互換性の確認であり、正確性の保証ではありません。AIの推奨は元のスキャン結果と照合してください。既定モデルはリソース基準の選択であり、品質順位ではありません。

## インストール・モデル・言語選択

1. 実行ファイルとライブラリを `llama/` に置きます。
2. モデルがなければ起動時に **Qwen3.5 2Bのみ** `model/Qwen/` に事前取得します。開始ボタンも必要時に取得します。`OSWL_EMBEDDED_AUTO_DOWNLOAD=false` で事前取得を停止できます。
3. Gemmaは上記固定リビジョンから手動取得し、SHA-256とバイト数を確認して `model/Gemma/` に置きます。
4. **設定 → AI → プロバイダー → 内蔵AI**でモデル名と要件を確認します。
5. **停止 → モデル選択 → 保存 → 開始**で変更します。開始失敗時は代替が実行される場合があるため、実行中モデルと代替表示を確認します。
6. **分析範囲/コンテキスト**で英語・韓国語・日本語を選び保存します。内蔵識別とURLは維持されます。既存要約は再生成前には翻訳されません。

既存のAI設定管理権限を使います。開始は実際のモデルをLOCALに登録してほかのプロバイダーを無効にし、停止はLOCALも無効にします。ほかのプロバイダー選択だけではプロセスが止まらないため、メモリ解放には別途停止します。

既定優先順位はQwen3.5-2B Q4_K_M → ほかのQwen3 → Gemma 4 E2B → 残りの互換GGUFです。明示選択と有効な保存値を優先します。追加モデルは自身の要件とライセンスを確認してください。

フォルダー変更はllama/とmodel/を含むルートを保存します。変更時は管理中のサーバーが停止します。移動もロック回避のため停止後に行います。Linux実行ファイルには実行権限が必要です。

## CPU既定値

| Environment variable | Default |
|---|---|
| `OSWL_EMBEDDED_AI_DIR` | `embedded-ai` |
| `OSWL_EMBEDDED_AI_PORT` | `11435` |
| `OSWL_EMBEDDED_AI_GPU_LAYERS` | `0` |
| `OSWL_EMBEDDED_AI_THREADS` | `1` |
| `OSWL_EMBEDDED_AI_PARALLEL` | `1` |
| `OSWL_EMBEDDED_AI_CONTEXT` | `8192` |
| `OSWL_EMBEDDED_AI_FLASH_ATTN` | `true` |
| `OSWL_EMBEDDED_AI_CACHE_REUSE` | `256` |
| `OSWL_EMBEDDED_AI_STARTUP_TIMEOUT_SEC` | `120` |
| `OSWL_EMBEDDED_AI_EXTRA_ARGS` | empty |
| `OSWL_EMBEDDED_AUTO_DOWNLOAD` | `true` |

`-ngl 0` と生成スロット1を明示します。`LLAMA_ARG_CHAT_TEMPLATE_KWARGS={"enable_thinking":false}` とreasoning budget 0を併用します。予算0だけでは新モデルが空回答になる場合があります。4 vCPUで2スレッドを使う場合はWeb・スキャンの遅延を確認してください。スレッド数はCPU使用率上限ではなく、必要ならOS・コンテナ制限を使います。

全体コンテキストはスロット間で分割します。`OSWL_AI_MAX_PARALLEL_CALLS` の既定3は**スキャン単位**です。1スロットでも待機が非ストリーミング読み取り制限90秒を超える場合があります。小型サーバーは1に設定し、スキャン重複を避けます。全体の受付制限ではありません。

## 整合性・閉域・GitHub配布

既定URLは上記の固定リビジョンです。旧GitHub `models-v1` 資産はQwen3 1.7Bなので新モデルに再利用できません。この変更は新しいGitHubモデル資産を公開せず、存在も仮定しません。

`OSWL_EMBEDDED_DEFAULT_MODEL_URL`、`OSWL_EMBEDDED_DEFAULT_MODEL_SHA256`、`OSWL_EMBEDDED_DEFAULT_MODEL_SIZE_BYTES` は同一ファイルの値にします。ハッシュとサイズを検証してからインストールします。`OSWL_EMBEDDED_FALLBACK_MODEL_URL` は既定で空で、同じバイト列のミラーのみ指定できます。ミラーも既定Qwen名で保存するため、別モデルは実際の名前で手動配置します。

閉域では取得せず、対応ランタイム・検証済み重み・告知を転送します。Dockerにも含まれないためルートをマウントして **Linux用** ランタイムを用意します。自動取得には書き込み権限が必要です。Windows実行ファイルはLinuxコンテナで使えません。

`embedded-ai/` はGit・Dockerビルドコンテキストから除外し、JARに重み・実行ファイルは入りません。Qwen3.5・Gemma 4はApache 2.0、llama.cppはMITです。ライセンス全文・出典・上流告知・Unsloth GGUF Q4_K_M量子化の事実を保存します。実行ファイル再配布では同梱ライブラリの告知も保ちます。

## トラブルシューティング

- 実行ファイルなし: llama/、OS・アーキテクチャ・ライブラリ・権限を確認します。
- 空回答: 互換版と明示的な非思考設定を確認します。
- 開始失敗: llama-server.logでモデル対応・RAM不足を確認します。
- 別モデル実行: 停止・選択・保存・開始後に代替表示を確認します。
- タイムアウト: 同時スキャン・呼び出しとCPUクレジットを確認します。
- 取得失敗: URL・サイズ・SHA-256を同時に確認します。
