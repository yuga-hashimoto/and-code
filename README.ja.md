# AndCode

<p align="center">
  <a href="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml"><img src="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/v/release/yuga-hashimoto/and-code" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/yuga-hashimoto/and-code" alt="License: MIT" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/downloads/yuga-hashimoto/and-code/total" alt="Downloads" /></a>
</p>

**AIコーディングエージェントをAndroidのネイティブGUIでローカル実行 — ターミナル不要です。**

AndCodeはAIコーディングエージェントをスマートフォンで使えるようにするネイティブAndroid GUIアプリです。[OpenCode](https://github.com/sst/opencode)、[Claude Code](https://github.com/anthropics/claude-code)、[Google Antigravity](https://github.com/google-antigravity/antigravity-cli)とタッチ操作中心のインターフェースで対話できます — 端末エミュレータもSSHもPCも、オンデバイス実行には一切不要です。PRootによるオンデバイスランタイムか、PC/Mac/Linux上の既存OpenCodeサーバーへのリモート接続で動作します。

[Releases](https://github.com/yuga-hashimoto/and-code/releases/latest) · [English README](README.md)

> [!IMPORTANT]
> AndCodeは、利用者自身のAndroid端末上で対応する第三者製コマンドラインツールをインストールまたは起動する、独立したローカルファーストGUIです。AndCode自体がClaude、Google Antigravity、OpenCodeなどのAIサービス、サブスクリプション、モデル利用権またはアカウント利用権を提供するものではありません。認証、モデルアクセス、推論およびサービスとの通信は、各公式CLIまたは利用者が設定したプロバイダーによって処理されます。AndCodeはOpenCode、AnthropicまたはGoogleと提携、承認、後援または公式サポート関係にありません。詳細は[法的情報・第三者ソフトウェア](#法的情報第三者ソフトウェア)を参照してください。

<div align="center">

### スクリーンショット

<table>
  <tr>
    <td align="center"><img src="screenshots/navigation-drawer.jpg" width="180" alt="エージェント、プロジェクト、最近のチャットを表示するナビゲーションドロワー"><br><em>ナビゲーションドロワー</em></td>
    <td align="center"><img src="screenshots/model-picker.jpg" width="180" alt="お気に入りのモデルを検索できるモデル・実行先ピッカー"><br><em>モデル・実行先ピッカー</em></td>
    <td align="center"><img src="screenshots/repository-chat.jpg" width="180" alt="AndCodeのリポジトリを調査するエージェントとのチャット"><br><em>リポジトリチャット</em></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/image-generation.jpg" width="180" alt="会話内に表示されたエージェント生成画像"><br><em>画像生成</em></td>
    <td align="center"><img src="screenshots/schedules.jpg" width="180" alt="毎日実行される有効なスケジュール済みプロンプト"><br><em>スケジュール</em></td>
    <td align="center"><img src="screenshots/run-result.jpg" width="180" alt="チャットを開ける完了済みスケジュール実行"><br><em>実行結果</em></td>
  </tr>
</table>

</div>

> [!IMPORTANT]
> AndCodeは独立したオープンソースプロジェクトです。OpenCode、Anthropic、Googleのいずれとも一切関係ありません。

---

## 目次

- [対応エージェント](#対応エージェント)
- [主な機能](#主な機能)
- [Antigravity](#antigravity)
- [リモートOpenCode](#リモートopencode)
- [画面構成](#画面構成)
- [クイックスタート](#クイックスタート)
- [セキュリティ](#セキュリティ)
- [オンデバイスランタイムの詳細](#オンデバイスランタイムの詳細)
- [ハンドオフ（会話途中での実行先切替）](#ハンドオフ会話途中での実行先切替)
- [OpenCode Desktopとの接続](#opencode-desktopとの接続)
- [ビルド](#ビルド)
- [ドキュメント](#ドキュメント)
- [コントリビューション](#コントリビューション)
- [法的情報・第三者ソフトウェア](#法的情報第三者ソフトウェア)
- [ライセンス](#ライセンス)

## 対応エージェント

| エージェント | オンデバイス | PCリモート | 状態 |
|-------------|:---------:|:---------:|------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | 安定版 |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | ベータ |
| [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) | ✓ | — | ベータ |

オンデバイスエージェントはPRoot経由でLinux環境内で実行されます。OpenCodeとClaude CodeはAlpine Linuxを使用し、Google Antigravityは公式`agy`バイナリのglibc互換性のためDebian Bookworm rootfsを導入します。

## 主な機能

- **ネイティブAndroid GUI** — コーディングエージェントのためのタッチ操作中心インターフェース。通常利用ではCLIや端末は不要
- **オンデバイスランタイム** — Alpine Linux、Git、bash、curl、ripgrep、コーディングエージェントをPRootで自動セットアップ
- **リポジトリ・ワークスペース** — デバイス上でGitリポジトリを開き、ファイルツリーを閲覧し、シンタックスハイライト付きでファイルを表示
- **端末内ファイル** — 全ファイルアクセスを許可すると、端末内のファイル（`/sdcard`、SDカード、USBドライブ）をフォルダ選択画面から閲覧でき、エージェントからも参照可能。アプリ領域へコピーせず、その場で開きます
- **Gitサポート** — ステージング、差分表示、コミット、ブランチ管理をGUIで実行
- **差分ビューア** — 適用前にコード変更をインラインで確認
- **組み込みターミナル** — オンデバイスランタイムへの本格的なPTYターミナルアクセス
- **プルリクエストバッジ** — チャットで作成したPRを入力欄の上に固定表示。差分行数と状態（ドラフト／オープン／コンフリクト／マージ済み／クローズ）が一目でわかり、タップでGitHubを開きます
- **ツール承認** — 危険なツール操作の許可・拒否
- **セッション管理** — 新規作成、再開、名前変更、削除
- **スケジュール実行** — 単発またはcronによる定期実行でプロンプトを自動実行し、実行履歴を確認
- **動的モデル** — 接続中のエージェントインスタンスからモデル・プロバイダー・エージェントを動的取得
- **リアルタイムストリーミング** — SSEによる回答・実行状況・承認要求のリアルタイム受信
- **構造化タイムライン** — reasoning・ツール実行・コマンド出力を折りたたみ表示
- **音声＋ウェイクワード** — Android音声認識によるプッシュ・トゥ・トーク＋ウェイクワード検出
- **テキスト読み上げ** — 回答の音声読み上げ
- **デジタルアシスタント** — Androidの既定アシスタントとして登録（ホームジェスチャー／コーナースワイプ）
- **ホーム画面ウィジェット** — アプリを開かずにランチャーから直接プロンプトを送信
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

## 画面構成

| 画面 | 説明 |
|------|------|
| チャット | ホーム画面兼会話画面 — 最近のセッション、折りたたみ可能なreasoning／ツール、音声入力、モデル切替、承認、ハンドオフ |
| 作業先（Workspaces） | ローカルランタイムのセットアップ、PC接続、作業フォルダ、ファイルブラウザ、コードビューア、組み込みターミナル |
| スケジュール | 単発またはcronによる定期実行プロンプトの作成・編集・有効／無効切替・実行履歴の確認 |
| 設定 | エージェント・プロバイダー、GitHub、MCPサーバー、モデル表示設定、音声／TTS、デジタルアシスタント、言語 |

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

```text
Name:     Mac mini
URL:      http://192.168.1.10:4096
Username: opencode
Password: your-strong-password
```

> Tailscaleも利用可能: `http://100.x.y.z:4096` または `http://your-mac.tailnet-name.ts.net:4096`

### QRコードでのセットアップ

PCでQRコードを生成:

```bash
npx qrcode "opencode://connect?name=Mac%20mini&url=http%3A%2F%2F192.168.1.10%3A4096&username=opencode&password=your-password&insecure=true"
```

アプリの**作業先** → **QRで追加**からスキャンしてください。

## セキュリティ

- ポート4096をインターネットへ直接公開しないでください
- LANまたはTailscaleでの利用を推奨
- 公開ネットワークではHTTPSリバースプロキシを使用
- 危険操作は自動承認されません
- LAN上の平文HTTPは接続先ごとに明示的許可が必要
- PRootは互換実行環境であり、完全なセキュリティサンドボックスではありません。Full Access／bypass permissionsモードを有効にすると、公式CLIが確認なしにコマンド実行やファイル変更を行えるようになります。有効化前にアプリが警告を表示しますが、重要なファイルは各自バックアップし、信頼できるリポジトリ・プロンプト・MCPサーバーとのみ組み合わせて使用してください
- `opencode://connect`用に生成したQRコードには平文の接続パスワードが含まれます。信頼できる相手から受け取ったQRコードのみをスキャンし、QRコードの共有はパスワードの共有と同等に扱ってください

## オンデバイスランタイムの詳細

セットアップ処理（作業先画面から実行）:

1. APKに同梱されたネイティブPRootランナーを検証
2. 公式CDNからAlpine Linux minirootfsをダウンロード
3. GitHub Releasesからエージェントバイナリをダウンロード
4. 両方のSHA-256チェックサムを検証
5. アプリ専用ディレクトリに展開
6. Alpine内にGit、bash、curl、ripgrep、CA証明書をインストール
7. `127.0.0.1:4097`でエージェントサーバーを起動
8. アプリをローカルランタイムに切り替え

固定バージョン（エージェント自体を変更せずアプリのリリースで更新可能。詳細は[`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)を参照）:

- Alpine Linux 3.24.1
- OpenCode 1.18.5
- Google Antigravity CLI 1.1.7（Debian Bookworm rootfs）
- 対応アーキテクチャ: arm64-v8a, x86_64

## ハンドオフ（会話途中での実行先切替）

チャットヘッダーのメニュー → **別の実行先で続ける** — アプリが会話の要約プロンプトを生成し、選択した実行先へ送信することで、続きから再開できます（例：通勤中はオンデバイスで開始し、帰宅後はPCで続きを行う）。

## OpenCode Desktopとの接続

`~/.config/opencode/opencode.json`にサーバー設定を追加:

```json
{
  "server": {
    "port": 4096,
    "hostname": "0.0.0.0",
    "mdns": true
  }
}
```

デスクトップアプリを再起動後、Androidアプリの**LANで検索**から発見できます。

## ビルド

必要環境: JDK 17、Android SDK、Python 3、ネットワーク接続（初回のみ）

```bash
./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

出力されるAPK:

```text
app/build/outputs/apk/github/debug/app-github-debug.apk
app/build/outputs/apk/github/release/app-github-release-unsigned.apk
```

端末へのインストール:

```bash
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

## ドキュメント

- [オンデバイスランタイム設計](docs/LOCAL_RUNTIME.md)
- [Antigravityローカルランタイム](docs/ANTIGRAVITY.md)
- [Antigravityエージェント同等機能設計書](docs/superpowers/specs/2026-07-27-antigravity-agent-parity-design.md)
- [CIガイド](docs/CI.md)
- [リリースガイド](docs/RELEASE.md)
- [翻訳ガイド](docs/TRANSLATION.md)
- [デバイス検証マトリクス](docs/device-matrix.md)

## コントリビューション

コード、バグ報告、翻訳など、どんな貢献も歓迎します！セットアップと開発フローは[CONTRIBUTING.md](CONTRIBUTING.md)を、翻訳で協力したい場合は[docs/TRANSLATION.md](docs/TRANSLATION.md)を参照してください。

## 法的情報・第三者ソフトウェア

- **公式CLIは端末内で実行されます。** OpenCode、Claude Code、Google Antigravityは各プロジェクトの公式配布チャネルから取得した無改変のバイナリであり、この端末のPRoot Linux環境内（OpenCodeについては利用者自身のPC/Mac/Linux上）で実行されます。AndCodeはこれらのエージェントロジックをフォーク・改変・再実装していません。
- **アカウントやAPI設定は利用者自身が用意します。** AndCodeはClaude、Antigravity、OpenCode用モデル、GitHubへのアクセスを販売・提供しません。利用者自身のアカウントまたはプロバイダー設定で認証し、各サービスの最新の利用規約が適用されます。
- **AndCode独自サーバーへプロンプトやトークンを中継しません。** プロンプト、ファイル、OAuthトークンが経由するAndCode独自バックエンドは存在しません。リクエストは端末上のCLI（またはPC上のOpenCodeサーバー）から、利用者が設定したプロバイダーへ直接送信されます。詳細は[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md)を参照してください。
- **OAuthトークンを他のツールへ転用しません。** Claude CodeとAntigravityは、それぞれの公式CLIが通常行うのと同様に、OAuth認証情報をLinux/Debian rootfs内に保持します。AndCodeはこれをAndroidアプリの設定領域、他エージェントの認証情報保存領域、外部サービスへコピーすることはありません。
- **関連文書：** [PRIVACY.md](PRIVACY.md)、[TERMS.md](TERMS.md)、[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md)、[TRADEMARKS.md](TRADEMARKS.md)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)（同梱ランタイムコンポーネントおよび依存ライブラリのOSSライセンス一覧）。これらはアプリ内の**設定 → 法的情報・プライバシー**からオフラインでも確認できます。

ランタイム生成処理の一部は、MITライセンスのHermes Agent Android実装に含まれる汎用Termuxパッケージ解決・展開処理をコーディングエージェント向けに再設計しています。同梱する第三者コンポーネントとライセンスの一覧は[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)を参照してください。

## ライセンス

このリポジトリの**AndCodeソースコード**は[MITライセンス](LICENSE)です。このライセンスが適用されるのはAndCode自身のKotlin/Androidコードのみであり、AndCodeが導入・起動する第三者CLIやランタイム（Claude Code、Google Antigravity、OpenCode、PRoot、Alpine/Debianパッケージなど）には適用されません。それぞれ独自の配布元ライセンスが適用されます。詳細は[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)および[TRADEMARKS.md](TRADEMARKS.md)を参照してください。
