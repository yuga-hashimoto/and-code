# 第三者サービス

AndCodeは以下の第三者サービスと連携します。AndCodeは利用者とこれらのサービスの間に独自サーバーを置いていません。各行では、実際に認証と通信を処理する主体、および資格情報の保存場所を示します。Claude Code、Antigravity、OpenCodeそれぞれの詳細については、[PRIVACY.md](PRIVACY.md)と[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md)を参照してください。

| サービス | AndCodeでの用途 | 認証主体 | 通信主体 | 資格情報の保存場所 | AndCode独自サーバーを経由するか | 適用される規約 | 無効化または削除方法 | 非提携表示 |
|---|---|---|---|---|---|---|---|---|
| **OpenCode**（`anomalyco/opencode`） | ローカルまたはリモートのコーディングエージェントランタイム。モデルへのアクセスはOpenCode内で接続したプロバイダー経由 | 利用者本人（OpenCode自身のプロバイダー認証、またはAPIキーによる） | OpenCodeプロセス（端末内またはPC上）が、設定したプロバイダーへ直接通信 | 3つの異なる経路がある — 単一の回答ではなく下記の[OpenCodeの資格情報経路](#opencode-credential-paths)を参照 | いいえ | [OpenCodeの規約／ライセンス](https://github.com/anomalyco/opencode) | ローカルランタイムを削除、**設定 → プロバイダー**でプロバイダーを切断、または**設定 → リモート接続**で接続を削除 | 独立した第三者プロジェクト。AndCodeとは提携していません |
| **Anthropic Claude Code** | Claudeとチャットするために端末内へインストール・実行する公式CLI | 利用者本人。公式の`claude auth login`ブラウザフローによる | Claude Code CLIプロセスがAnthropic（または設定したプロバイダー）へ直接通信 | 端末内Alpine rootfs内の、Claude Code CLI自身の資格情報保存領域内 — AndCodeは読み取りもコピーもしません | いいえ | [Anthropicの消費者向け／商用規約](https://www.anthropic.com/legal)およびClaude Code自身の規約 | **設定 → エージェント → Claude Code**で**サインアウト**、またはローカルランタイムを削除 | Anthropicの公式CLI。AndCodeはAnthropicと提携・承認関係にありません |
| **Google Antigravity** | Gemini／Claude／GPT-OSSモデルとAntigravity経由でチャットするために端末内へインストール・実行する公式かつ無改変の`agy` CLI | 利用者本人。公式の`agy`初回起動時Google OAuthフローによる | `agy` CLIプロセスがGoogleへ直接通信 | 端末内Debian rootfs内の`root/.gemini/antigravity-cli/antigravity-oauth-token`にある、`agy`自身のゲストトークン保存領域内 — AndCodeは読み取りもコピーもしません | いいえ | [Google Antigravityの規約](https://antigravity.google/) | **設定 → エージェント → Antigravity**で**サインアウト**、またはローカルランタイムを削除 | Googleの公式CLI。AndCodeはGoogleと提携・承認関係にありません |
| **GitHub** | 任意機能：プルリクエストの作成／閲覧、「GitHubでスターを付ける」プロンプト、Linuxランタイム内の`gh` CLI | 利用者本人。GitHubのOAuthデバイスフローログインによる | アプリが利用者のトークンで直接GitHub APIを呼び出す。ランタイム内の`gh` CLIも同様 | GitHubトークンは`EncryptedSharedPreferences`（`SecureSettingsRepository`）に保存 | いいえ | [GitHub利用規約](https://docs.github.com/site-policy/github-terms/github-terms-of-service) | **設定 → GitHub**で**切断** | 独立したサービス。AndCodeとは提携していません |
| **OpenAI** | 任意のテキスト読み上げ音声（OpenAI TTSプロバイダーを選び、自身のAPIキーを入力した場合） | 利用者本人。自身のOpenAI APIキーによる | アプリが読み上げ対象のテキストを直接OpenAI APIへ送信 | APIキーは`EncryptedSharedPreferences`に保存 | いいえ | [OpenAI利用規約](https://openai.com/policies/terms-of-use) | **設定 → 音声**でTTSプロバイダーを切り替える、またはキーを削除 | 独立したサービス。AndCodeとは提携していません |
| **ElevenLabs** | 任意のテキスト読み上げ音声（ElevenLabs TTSプロバイダーを選び、自身のAPIキーを入力した場合） | 利用者本人。自身のElevenLabs APIキーによる | アプリが読み上げ対象のテキストを直接ElevenLabs APIへ送信 | APIキーは`EncryptedSharedPreferences`に保存 | いいえ | [ElevenLabs利用規約](https://elevenlabs.io/terms) | **設定 → 音声**でTTSプロバイダーを切り替える、またはキーを削除 | 独立したサービス。AndCodeとは提携していません |
| **設定したMCPサーバー** | エージェントが呼び出す任意のツール／コンテキストサーバー | サーバーによる（利用者が指定するローカルコマンドまたはリモートURL） | エージェントCLIが設定したサーバーへ接続 | サーバー側の資格情報は、設定した場所（例：サーバー側の環境変数）に依存。AndCodeはサーバー側の機密情報を管理しません | いいえ | 各MCPサーバー運営者が定める規約 | **設定 → MCP**でサーバーを削除 | 利用者が選んで追加する第三者サーバー。AndCodeとは提携していません |
| **Alpine Linux**（`dl-cdn.alpinelinux.org`） | 端末内ランタイムが動作する最小Linuxルートファイルシステム | 該当なし（認証不要のダウンロード） | アプリが公式Alpine minirootfsアーカイブをHTTPS経由でダウンロード | 該当なし | いいえ | [Alpine Linuxのライセンス条件](https://alpinelinux.org/)（パッケージごと） | ローカルランタイムを削除 | 独立したオープンソースプロジェクト |
| **Debian**（`deb.debian.org`、`security.debian.org`） | glibcに依存するAntigravity CLI専用のBookworm rootfs | 該当なし（認証不要の`apt`ミラー） | Debian rootfs内の`apt`が、設定されたミラーからパッケージを取得 | 該当なし | いいえ | [Debianのライセンス](https://www.debian.org/legal/)（パッケージごと） | ローカルランタイムを削除 | 独立したオープンソースプロジェクト |
| **Termuxパッケージミラー**（`packages.termux.dev`） | ビルド時にAPKへ同梱される`proot`、`libandroid-shmem`、`libtalloc`バイナリの取得元 | 該当なし（ビルド時のダウンロードで、ハッシュにより固定） | ビルドマシンのみ。実行時にエンドユーザーの端末が通信するわけではない | 該当なし | いいえ | 配布元のライセンスは各種 — [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)を参照 | 該当なし（ビルド時に同梱） | 独立したオープンソースプロジェクト |
| **Firebase Crashlytics**（Google） | リリースビルドにおけるクラッシュおよび非致命的エラーの報告 | 該当なし（アプリレベルのFirebaseプロジェクトで、エンドユーザーのログインはなし） | アプリがクラッシュレポートと小さな診断情報をFirebaseへ直接送信。AndCode自身のコードが追加するカスタムログ行／キーはベストエフォートでマスキングされる（PRIVACY.md §7参照）が、Crashlytics SDK自身による致命的クラッシュの自動収集はこのマスキング処理を経由しない | 該当なし（利用者の資格情報はなし。アプリレベルのFirebase設定のみ） | これはGoogleが運用する収集サービス**そのもの**であり、AndCodeのサーバーではありません | [Firebaseの利用規約](https://firebase.google.com/terms) / [Googleのプライバシーポリシー](https://policies.google.com/privacy) | デバッグビルドでは自動的に無効。現時点でリリースビルドにおいて利用者ごとの切り替えはできません | 独立したGoogleのサービス。上記のAIエージェント連携とは無関係 |

## OpenCodeの資格情報経路

上表の「OpenCode」は、実際には利用方法によって異なる3つの資格情報フローをまとめたものです。

1. **AndCode自身のUIへ入力したプロバイダーAPIキーを、ローカル（端末内）OpenCodeランタイムで使う場合。** **設定 → プロバイダー**で入力したキーは、`EncryptedSharedPreferences`に保存される*とともに*、`LocalProviderCredentialStore.syncToRuntime()`により、端末内Alpine rootfs内の`root/.local/share/opencode/auth.json`へ、ローカルOpenCodeプロセスが読み取る平文JSON形式で書き込まれます。このファイルはアプリのプライベートなファイルシステム権限によってのみ保護されており、`EncryptedSharedPreferences`が追加で行う保存時暗号化はありません。
2. **OpenCode自身のAPI経由で開始するプロバイダーOAuth**（`providerAuthMethods`／`authorizeProvider`。貼り付け式のキーではなくOAuthフローに対応したプロバイダーで使用）。取得後の資格情報はOpenCodeプロセス自身が管理します。これは経路1で同期される`auth.json`が取得後に管理されるのと同様です。AndCodeの役割は、Claude CodeやAntigravityのサインインと同様に、ブラウザURLと入力されたコードを中継することに限られます。
3. **リモートOpenCode**（利用者自身のPC／Mac／Linux上で実行するサーバー）。AndCodeはそのサーバーへの*接続プロファイル*（名前、URL、ユーザー名、パスワード）のみを`EncryptedSharedPreferences`に保存します。リモートOpenCodeサーバーが使用するプロバイダーの資格情報は、そのマシン上で管理され、Androidアプリへ同期・保存されることはありません。

## 補足

- 「AndCode独自サーバーを経由するか」は、Crashlyticsを除くすべての行で「いいえ」です。Crashlyticsはテレメトリの収集経路であり、プロンプト・ファイル・資格情報を中継するプロキシではありません。
- Claude Code、Antigravity、OpenCodeの各行は、AndCodeが再実装したものではなく、*公式*CLI自身のネットワークおよび資格情報の挙動を説明したものです。詳細は[docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md)を参照してください。
- AndCodeとともに利用しているサービスがここに記載されていない場合（例：OpenCode経由で接続した別のMCPサーバーやモデルプロバイダー）でも、そのサービスにはAndCodeの規約ではなく、そのサービス自身の規約が適用されます。
