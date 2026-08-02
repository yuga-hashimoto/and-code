# AndCode

**AIコーディングエージェントをAndroidのネイティブGUIでローカル実行 — ターミナル不要です。**

AndCodeはAIコーディングエージェントをスマートフォンで使えるようにするネイティブAndroid GUIアプリです。[OpenCode](https://github.com/sst/opencode)、[Claude Code](https://github.com/anthropics/claude-code)、[Google Antigravity](https://github.com/google-antigravity/antigravity-cli)とタッチ操作中心のインターフェースで対話できます — 端末エミュレータもSSHもPCも、オンデバイス実行には一切不要です。PRootによるオンデバイスランタイムか、PC/Mac/Linux上の既存OpenCodeサーバーへのリモート接続で動作します。

<p align="center">
  <img src="screenshots/navigation.png" width="240" alt="エージェント・プロジェクト・最近のチャットを表示するナビゲーションドロワー" />
  &nbsp;
  <img src="screenshots/chat.png" width="240" alt="ストリーミング回答・Todo進行・モデル切替を表示するチャット画面" />
  &nbsp;
  <img src="screenshots/model-picker.png" width="240" alt="お気に入り付きのモデル・実行先ピッカー" />
</p>

> [!IMPORTANT]
> AndCodeは独立したオープンソースプロジェクトです。OpenCodeおよびAnthropicとは一切関係ありません。

[English README](README.md)

---

## 対応エージェント

| エージェント | オンデバイス | PCリモート | 状態 |
|-------------|:---------:|:---------:|------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | 安定版 |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | ベータ |
| [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) | ✓ | — | ベータ |

オンデバイスエージェントはPRoot経由でLinux環境内で実行されます。OpenCodeとClaude CodeはAlpine Linuxを使用し、Google Antigravityは公式`agy`バイナリのglibc互換性のためDebian Bookworm rootfsを導入します。

## 主な機能

- **ネイティブAndroid GUI** — コーディングエージェントのためのタッチ操作中心インターフェース。CLIや端末は不要
- **オンデバイスランタイム** — Alpine Linux、Git、bash、curl、ripgrep、コーディングエージェントをPRootで自動セットアップ
- **リポジトリ・ワークスペース** — デバイス上でGitリポジトリを開いて作業
- **端末内ファイル** — 全ファイルアクセスを許可すると、端末内のファイル（`/sdcard`、SDカード、USBドライブ）をフォルダ選択画面から閲覧でき、エージェントからも参照可能。アプリ領域へコピーせず、その場で開きます
- **Gitサポート** — ステージング、差分表示、コミット、ブランチ管理をGUIで実行
- **差分ビューア** — 適用前にコード変更をインラインで確認
- **プルリクエストバッジ** — チャットで作成したPRを入力欄の上に固定表示。差分行数と状態（ドラフト／オープン／コンフリクト／マージ済み／クローズ）が一目でわかり、タップでGitHubを開きます
- **ツール承認** — 危険なツール操作の許可・拒否
- **セッション管理** — 新規作成、再開、名前変更、削除
- **動的モデル** — 接続中のエージェントインスタンスからモデル・プロバイダー・エージェントを動的取得
- **リアルタイムストリーミング** — SSEによる回答・実行状況・承認要求のリアルタイム受信
- **構造化タイムライン** — reasoning・ツール実行・コマンド出力を折りたたみ表示
- **音声＋ウェイクワード** — Android音声認識によるプッシュ・トゥ・トーク＋ウェイクワード検出
- **テキスト読み上げ** — 回答の音声読み上げ
- **デジタルアシスタント** — Androidの既定アシスタントとして登録（ホームジェスチャー／コーナースワイプ）
- **安全な保存** — 接続情報をAndroid Keystoreで暗号化
- **多言語UI** — 英語、日本語、中国語（簡体字）、ロシア語、スペイン語、フランス語、ポルトガル語（ブラジル）、アラビア語に対応。設定画面から切替可能

## Antigravity

Google Antigravity（`agy`）は同じPRoot環境内でオンデバイス実行されます。OpenCodeやClaude CodeがAlpine Linuxを使用するのに対し、Antigravityは公式CLIバイナリのglibc互換性のためDebian Bookworm rootfsを導入します。

- **OAuthサインイン** — ブラウザURLとワンタイムコードによる認証フロー。認証情報はLinux rootfs内（`~/.gemini`）にのみ保存され、Androidの設定領域には保存されません
- **モデル選択** — サインイン済み`agy`インスタンスから動的取得したモデル一覧（Gemini、Claude、GPT-OSSの各バリアント）
- **権限モード** — 3段階の制御：Plan、Accept Edits、Full Access（`--dangerously-skip-permissions`）
- **MCPサーバー** — `~/.gemini/config/mcp_config.json`を直接読み書きし、公式CLIと同じ設定形式に対応
- **サンドボックス起動** — 明示的な端末サイズを持つPTYベースのプロセス。`AGY_CLI_DISABLE_AUTO_UPDATE=1`によりバージョン更新はアプリ側で制御

## リモートOpenCode

オンデバイスエージェントに加えて、AndCodeは追加機能としてPC/Mac/Linux上のOpenCodeに接続できます：

- **リモート接続** — LANまたはTailscale経由で接続
- **実行先切り替え** — 会話中でもAndroidローカル／PCリモート間をシームレスに切り替え（ハンドオフ）
- **自動検出** — QRコードまたはmDNS（ゼロ設定LAN検出）でPCを検索

## クイックスタート

### オンデバイス実行（PC不要）

1. [Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)からAPKをインストール
2. アプリを開く → **作業先** → **このAndroid端末** → **この端末へセットアップ**
3. ランタイムのダウンロード・インストールを待つ（約2分）
4. コーディングエージェントを選択してチャット開始

### PCリモート実行

1. PCでOpenCodeサーバーを起動:

```bash
OPENCODE_SERVER_PASSWORD='your-strong-password' \
  opencode serve --hostname 0.0.0.0 --port 4096 --mdns
```

2. Android端末にAPKをインストール
3. アプリ → **作業先** → **接続先を追加**
4. PCのIPを入力（または**LANで検索**／**QRで追加**で自動発見）

### セキュリティ

- ポート4096をインターネットへ直接公開しないでください
- LANまたはTailscaleでの利用を推奨
- 公開ネットワークではHTTPSリバースプロキシを使用
- 危険操作は自動承認されません
- LAN上の平文HTTPは接続先ごとに明示的許可が必要

## ビルド

必要環境: JDK 17、Android SDK、Python 3、ネットワーク接続（初回のみ）

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## コントリビューション

[CONTRIBUTING.md](CONTRIBUTING.md)を参照してください。

## 設計資料

- [AndCode v2設計書](docs/superpowers/specs/2026-07-18-opencode-android-v2-design.md)
- [Antigravityエージェント同等機能設計書](docs/superpowers/specs/2026-07-27-antigravity-agent-parity-design.md)
- [第一完成版の実装計画](docs/superpowers/plans/2026-07-18-initial-mvp.md)
- [Androidローカル実行設計](docs/LOCAL_RUNTIME.md)
- [Antigravityローカルランタイム](docs/ANTIGRAVITY.md)

## 第三者ソフトウェア

ランタイム生成処理の一部は、MITライセンスのHermes Agent Android実装に含まれる汎用Termuxパッケージ解決・展開処理をコーディングエージェント向けに再設計しています。詳細は[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)を参照。

## ライセンス

[MIT](LICENSE)
