# 認証とデータの流れ

この文書は、対応する各エージェントについて、AndCodeのコードが実際に行っていることを説明します。バイナリの取得元、インストール場所、起動方法、サインインの仕組み、Linux／Debianランタイムから Androidアプリへ渡る情報とそうでない情報です。機能がどう振る舞うべきかという想定ではなく、各フローを実装しているクラスをもとに記述しています。

## OpenCode

- **バイナリの取得元:** 公式OpenCodeリリースアーカイブ。取得元は
  `https://github.com/anomalyco/opencode/releases/download/v<version>/opencode-linux-<arch>-musl.tar.gz`で、
  アーキテクチャごとに
  [`app/src/main/assets/local-runtime-manifest.json`](../app/src/main/assets/local-runtime-manifest.json)
  内の`openCodeUrl`、`sha256`で固定されています。
- **更新時の問い合わせ:** 新しいリリースは、`LocalRuntimeReleaseClient`がGitHubの
  `https://api.github.com/repos/anomalyco/opencode/releases/latest`へ最新リリースを問い合わせて見つけます。
  この問い合わせは、**設定 → エージェント → OpenCode**を開いたとき（およびその画面を開いている間にランタイムの
  インストール・起動・更新・ロールバックが完了するたび）、更新確認をタップしたとき、そしてアプリがローカルランタイムを
  自動的に再起動する際（クラッシュ後、サービスの復元、ウォッチドッグによるもので、手動の「開始」は含みません）に、
  インストールされているバージョンがこのアプリのビルドに同梱されているバージョンより古い場合（たとえばアプリの更新直後）に行われます。
  この最後の場合は、画面を開かなくても、問い合わせと、見つかったリリースへの更新が実行されます。
  更新用アーカイブは、そのレスポンスでGitHubが示すアセットをHTTPSでダウンロードしたもので、
  GitHubが報告するSHA-256ダイジェストと一致した場合にのみインストールされます。
  詳細は[リリースと更新の問い合わせ](#release-and-update-lookups)を参照してください。
- **インストール場所:** SHA-256検証後、`LocalRuntimeInstaller`／`LocalOpenCodeBackend`により、アプリ専用の端末内Alpine rootfsへ展開されます。
- **起動方法:** Alpine PRootサンドボックス内で`127.0.0.1:4097`にバインドされたローカルHTTPサーバーとして起動します（`LocalOpenCodeBackend`）。AndCodeは他のOpenCodeクライアントと同様に`OpenCodeApiClient`経由で通信します。`OPENCODE_DISABLE_AUTOUPDATE=true`が設定されており、バージョン更新はOpenCode自身に任せず、アプリ側で制御されます。**リモートOpenCode**の場合、AndCodeは代わりに、利用者が既に自身のPC／Mac／Linuxで実行しているOpenCodeサーバーへ、入力されたURL／ユーザー名／パスワードを用いて接続します（`RemoteOpenCodeBackend`）。
- **認証方法:** OpenCode自身のプロバイダー認証（例：AnthropicやOpenAIのAPIキー、あるいはプロバイダー自身のOAuthフロー）は、OpenCodeサーバー自身が処理します。AndCodeの`ProviderAuthDialog`／`SettingsViewModel`は、OpenCode APIの`providerAuthMethods`／`authorizeProvider`／`completeProviderOAuth`エンドポイントを呼び出し、サーバーが返す方式・URL・入力項目をそのまま表示します。資格情報のやり取り自体は、OpenCodeプロセスとプロバイダーの間で行われ、AndCodeの内部では行われません。
- **認証URL／コードの扱い:** プロバイダーの認証方式がブラウザURLを開くものである場合、AndCodeはAndroidの`Intent.ACTION_VIEW`でそれを開き、入力されたコードをOpenCode APIへ送り返します。完全な認証URLは永続的なログへ書き込まれません（ログに記録する前に、`SecretRedaction.redactUrlQuery`によりクエリ文字列が除去されます）。
- **OAuth／APIキーの保存主体 — 単一ではなく3つの異なる経路:**
  1. AndCode自身のUI（**設定 → プロバイダー**）へ入力した、**ローカル**（端末内）ランタイム用のプロバイダーAPIキーは、`EncryptedSharedPreferences`に保存される*とともに*、AndCode自身の`LocalProviderCredentialStore.syncToRuntime()`により、端末内Alpine rootfs内の`root/.local/share/opencode/auth.json`へ、ローカルOpenCodeプロセスが読み取る平文JSON形式で書き込まれます。この経路では、独立して管理するプロセスへ中継しているのではなく、AndCode自身がこのファイルを書き込んでいます。
  2. OpenCode自身のAPI経由（上記のフロー）で取得したプロバイダーOAuthは、取得後はOpenCodeプロセス自身が管理します。これは経路1で同期される`auth.json`が、その後OpenCodeによって管理されるのと同様です。
  3. **リモート**OpenCodeサーバーの場合、AndCodeは**接続プロファイル**（サーバーURL、ユーザー名、パスワード）のみを`EncryptedSharedPreferences`（`SecureSettingsRepository`）に保存します。プロバイダーの資格情報はリモートマシン上に存在し、Androidアプリへ同期されることはありません。
- **AndCodeが読み取る情報:** チャットUIを表示するための、OpenCodeのREST／SSE API経由のセッション／メッセージ／ツール呼び出しデータ、およびプロバイダー一覧／接続状況。
- **AndCodeが読み取らない情報:** 上記経路1で管理対象のプロバイダーキーをマージするために必要な範囲を超えた、既存の`auth.json`の内容。リモートサーバーの場合、そのサーバーのディスク上の資格情報ファイルはまったく読み取りません。
- **プロンプトと応答のデータフロー:** Androidアプリと（ローカルループバックまたはリモートホストの）OpenCodeサーバーとの間で、HTTP／SSE経由で直接やり取りされます。そこからOpenCodeが設定済みのモデルプロバイダーと通信します。この経路にAndCodeが運用するサーバーは存在しません。
- **ログアウト時の処理:** プロバイダー（`disconnectProvider`）またはリモート接続を切断すると、`EncryptedSharedPreferences`から資格情報が削除され、必要に応じてOpenCode側にもプロバイダー認証の破棄を依頼します。他のエージェントの資格情報には影響しません。
- **AndCode独自サーバーの有無:** なし。

## Claude Code

- **バイナリの取得元:** 公式Claude Codeパッケージリポジトリ`https://downloads.claude.ai/claude-code/apk/latest`。Anthropicの署名鍵（`https://downloads.claude.ai/keys/claude-code.rsa.pub`）で検証されます。詳細は`ClaudeCodeInstaller`を参照。
- **リリースの問い合わせ:** このエージェントについては、AndCode自身のコードによるバージョン問い合わせはありません。
  Claude Codeのインストールまたは更新（**設定 → エージェント → Claude Code**、セットアップガイド、
  またはClaude Codeを含むランタイムのインストール）では、PRootサンドボックス内で`wget`と`apk`が実行されます。
  `wget`は署名鍵をダウンロードし（インストール時だけでなく更新のたびに）、`apk update`／`apk add`が
  `https://downloads.claude.ai/claude-code/apk/latest`からパッケージインデックスとパッケージを取得します。
  `apk update`はサンドボックスに設定されているすべてのリポジトリを更新するため、サンドボックスに設定されている
  Alpineパッケージリポジトリ（`dl-cdn.alpinelinux.org`）にも同じ手順の中で接続され、初回インストールでは
  `util-linux`と`jq`もそこから取得されます。これらのリクエストはAndCodeのHTTPクライアントではなく
  サンドボックス内のツール自身が送信するため、`User-Agent`は`AndCode`ではなくそれらのツール固有のものになります。
  詳細は[リリースと更新の問い合わせ](#release-and-update-lookups)を参照してください。
- **インストール場所:** 端末内Alpine Linux rootfs（OpenCodeと同じrootfs）内へ、`apk add`によってインストールされます。
- **起動方法:** `ClaudeSandboxLauncher`によりPRootサンドボックス内の子プロセスとして実行され、対話的な入出力が必要な場合（サインイン時など）はPTYを使用します。
  `CLAUDE_CODE_DISABLE_AUTOUPDATER=1`が設定されており、バージョン更新はCLI自身に任せず、アプリ側で制御されます。
- **認証開始方法:** `ClaudeAuthCoordinator.begin()`が、公式CLIを`claude auth login`で起動します。これは端末で実行するのと同じコマンドです。AndCodeはClaude用の独自OAuthクライアントを実装していません。
- **認証URL／コードの扱い:** コーディネーターがCLI自身の端末出力を走査し、印字されるAnthropic／Claudeのサインイン用URLを検出してAndroidブラウザで開き（`onOpenUrl` → `Intent.ACTION_VIEW`）、貼り付けられた確認コードをCLIのPTY標準入力へ書き込みます（`submitCode`）。これは実際の端末で入力した場合とまったく同じです。AndCodeはこの処理のために、元となるOAuthトークンを見ることも必要とすることもありません。
- **OAuthトークンの保存主体:** Claude Code CLI自身の資格情報保存領域（端末内Alpine rootfs内）に完全に保存され、どのLinuxマシン上でもそうであるようにCLI自身が書き込みます。AndCodeのコードはこのファイルを読み取ったり、解析したり、コピーしたりしません。
- **AndCodeが読み取る情報:** CLIの端末出力（サインインURL、確認コードの入力欄、成功／失敗を検知するため）、および現在サインイン中のアカウントを表示するための`claude auth status --text`の結果（`signedInAccount()`）。
- **AndCodeが読み取らない情報:** トークンファイルの内容、およびサインイン完了後にClaude Code自身がAnthropicへ行うリクエストの内容。
- **プロンプトと応答のデータフロー:** サインイン後、Claude Codeはセッションごとにストリーミングjsonモードの子プロセスとして駆動され、プロンプトと必要なファイル／ツールのコンテキストはそのプロセスから直接Anthropic（または設定したプロバイダー）へ送られ、応答は同じプロセスの標準出力を通じてAndCodeのチャットUIへストリーミングされます。この間にAndCodeが運用するサーバーは介在しません。
- **ログアウト時の処理:** `signOut()`がランタイム内で`claude auth logout`を実行します。これは公式CLI自身のサインアウトであり、CLIのローカル資格情報を削除するものです。AndCodeが別途管理しているものではありません。
- **AndCode独自サーバーの有無:** なし。

## Google Antigravity

- **バイナリの取得元:** 公式`agy` CLIリリースアーカイブ。取得元は
  `https://github.com/google-antigravity/antigravity-cli/releases/download/<version>/agy_cli_linux_<arch>.tar.gz`です。
  最新リリースはインストール／更新時にGitHubの
  `https://api.github.com/repos/google-antigravity/antigravity-cli/releases/latest`から解決され（`AntigravityReleaseClient`）、GitHubが報告するアセットのSHA-256ダイジェストを`VerifiedRuntimeDownloader`が検証します。
  この問い合わせが失敗した場合は、アプリ内にハッシュを組み込んだ`AntigravityManifest`で固定されたリリースを代わりに使用します。
  この問い合わせは、Antigravityをインストールまたは更新するとき（**設定 → エージェント → Antigravity**、
  セットアップガイド、またはAntigravityを含むランタイムのインストール）にのみ行われ、バックグラウンドでの確認はありません。
  詳細は[リリースと更新の問い合わせ](#release-and-update-lookups)を参照してください。
- **インストール場所:** 専用の**Debian Bookworm** rootfs内の`usr/local/bin/agy`へ展開されます（AntigravityはOpenCode／Claude CodeのAlpine／musl環境とは異なり、glibcを必要とするため）。詳細は`AntigravityInstaller`、`DebianRootfsInstaller`を参照。
- **起動方法:** `AntigravitySandboxLauncher`により、Debian PRootサンドボックス内のPTY子プロセスとして実行されます。`AGY_CLI_DISABLE_AUTO_UPDATE=1`が設定されており、バージョン更新はCLI自身に任せず、アプリ側で制御されます。
- **認証開始方法:** `AntigravityAuthCoordinator.start()`が、公式`agy`バイナリを引数なしで起動し（ドキュメント化された初回実行フロー）、ログイン方式選択のTUIを待ち、あらかじめ選択されている「Google OAuth」を確定するためEnterを送信します。これも端末で`agy`を自ら実行した場合に選ぶのと同じ操作です。
- **認証URL／コードの扱い:** コーディネーターが（`AntigravityAuthParser`を通じて）CLIのTUI出力を解析し、`accounts.google.com`のサインインURLを検出してAndroidブラウザで開き、貼り付けられたコードをCLIのPTYへ書き込みます。コード入力欄が表示された*後*にTUIが描画するすべての内容は、そのコードを含んでいる可能性があるものとして扱われ、UIやログへは一切表示されません（`AntigravityAuthCoordinator`内の`codeFieldLive`ゲート）。診断用の出力テキストは、表示・ログ記録の前に`AntigravityAuthParser.redact`を経由します。
- **OAuthトークンの保存主体:** Debian rootfs内のゲスト`$HOME`にある`root/.gemini/antigravity-cli/antigravity-oauth-token`に、公式CLI自身によって書き込まれます。AndCodeのサインアウト処理（`logout()`）は、このファイルを（内容を抽出・読み取ることなく）直接削除します。対話的な`/logout` TUIコマンドを自動操作することが信頼できなかったためです。この処理はローカルの資格情報を削除するのみで、どこへもコピーしません。
- **AndCodeが読み取る情報:** CLIのTUI出力（ログイン方式選択画面、サインインURL、コード待機状態を検知するため）、および`agy models`の出力（成功したやり取りの後もCLIプロセスは終了せず動作し続けるため、サインイン完了を帯域外で確認する用途で使用）。
- **AndCodeが読み取らない情報:** OAuthトークンファイルの内容、およびトークン交換自体でのGoogleからの応答内容（この交換は`agy`プロセス内部で完結します）。
- **プロンプトと応答のデータフロー:** サインイン後、`agy`はセッションごとに子プロセスとして駆動され、プロンプトと応答はそのプロセスとGoogleの間で直接やり取りされます。この経路にAndCodeが運用するサーバーは存在しません。
- **ログアウト時の処理:** `logout()`は、上記のゲストトークンファイルを削除し、`AntigravityGuestSettings.repair()`を呼び出してゲスト設定を整合性のある状態に戻し、アプリ内の状態を直接`Idle`に設定します。CLIが実際にサインアウト状態を報告するかを確認する`agy models`チェックは**実行しません**（この帯域外チェックはサインイン時の`verifyModels()`でのみ使用されます）。ログアウトの確認は、CLIへ再度問い合わせるのではなく、資格情報ファイルを削除したことをもって行われます。
- **AndCode独自サーバーの有無:** なし。

## OpenAI Codex

- **バイナリの取得元:** 公開npmレジストリにある公式`@openai/codex`パッケージ。AndCodeはCodexをAlpineパッケージやGitHubリリースではなくnpmから
  インストールするため、`CodexReleaseClient`が`https://registry.npmjs.org/`へ2回問い合わせます。まず`@openai/codex/latest`（最新バージョン）、
  続いて`@openai/codex/<version>-linux-arm64`または`@openai/codex/<version>-linux-x64`（そのプラットフォーム用の
  tarballのURLとSHA-512の`integrity`文字列）です。`CodexInstaller`は、レジストリが報告するURL（HTTPS必須。
  実際には`registry.npmjs.org/@openai/codex/-/…`）からtarballをダウンロードし、そのSHA-512 integrityと照合したうえで、
  `codex`と`codex-code-mode-host`の2つのバイナリだけを展開します。この問い合わせは、Codexをインストールまたは再インストールするとき
  （**設定 → エージェント → Codex**、セットアップガイド、またはCodexを含むランタイムのインストール）にのみ行われ、
  バックグラウンドでの確認はありません。詳細は[リリースと更新の問い合わせ](#release-and-update-lookups)を参照してください。
- **インストール場所:** 端末内Alpine rootfsの`usr/local/bin`。OpenCodeやClaude Codeと同じrootfsです
  （Codexのネイティブバイナリはmuslを対象としているため、2つ目のrootfsは不要です）。`CodexInstaller`を参照。
- **起動方法:** `codex app-server`が、PRootサンドボックス内で長時間動作する1つの子プロセスとして実行されます
  （`CodexSandboxLauncher`、`CodexRuntime`）。`CODEX_HOME=/root/.codex`が設定され、AndCodeは標準入出力上の
  JSON-RPC 2.0（`CodexJsonRpcClient`）でこれと通信します。OpenCode、Claude Code、Antigravityとは異なり、
  AndCodeはCodex自身の更新動作を無効にするフラグを設定していません。Codex CLIが自ら行うネットワークアクセスは
  CLI自身のものであり、AndCodeが中継したり制御したりするものではありません。
- **認証開始方法:** OpenCodeと同じプロバイダーダイアログが2つの方式を提示します（`CodexTarget.providerAuthMethods`）。
  **ChatGPTアカウント:** `account/login/start`がサインイン用URLを返し、AndCodeはそれをAndroidブラウザで開きます。
  ブラウザはCodex自身が`localhost:1455`で提供するコールバックへ戻り、完了は`account/login/completed`通知として届くため、
  何かを貼り付け直す必要はありません。**APIキー:** 入力したキーは`codex login --with-api-key`の標準入力へ書き込まれます。
- **資格情報の保存主体:** 端末内Alpine rootfs内の`CODEX_HOME`（`/root/.codex`）にある、Codex自身の資格情報保存領域に
  CLI自身が書き込みます。AndCodeのコードはこれを読み取ったり、解析したり、コピーしたりしません。ダイアログに入力した
  APIキーは、`codex login`へ渡されるまでそのダイアログのメモリ上の状態にのみ存在し、`EncryptedSharedPreferences`には保存されません。
- **AndCodeが読み取る情報:** JSON-RPCのストリーム（スレッド、ターン、アイテム、モデル一覧、承認リクエスト）、および
  サインイン済みのアカウントがあるかどうかを判定するためだけの`account/read`の結果。
- **AndCodeが読み取らない情報:** 資格情報ファイルの内容、およびトークン交換そのもの（`codex`プロセス内部で完結します）。
- **プロンプトと応答のデータフロー:** プロンプトと必要なファイル／ツールのコンテキストは、`codex`プロセスから直接
  OpenAI（またはCodex CLI自身に設定されているプロバイダー）へ送られ、応答は同じプロセスの標準出力を通じてAndCodeの
  チャットUIへストリーミングされます。この間にAndCodeが運用するサーバーは介在しません。
- **ログアウト時の処理:** `logout()`がランタイム内で`codex logout`を実行します。これは公式CLI自身のサインアウトであり、
  CLIのローカル資格情報を削除するものです。
- **AndCode独自サーバーの有無:** なし。

## リリースと更新の問い合わせ

AndCode（エージェントCLIではありません）が、どのリリースをインストールするかを下記の取得元へ問い合わせ、ダウンロードします。
これはAndCode自身のHTTPクライアントで行われますが、Claude Codeだけは、インストール時にサンドボックス内で`wget`と`apk`が実行されます。
各行にその処理を行うコードを示します。いつ行われるかは、上記の各エージェントの項を参照してください。

| エージェント | 接続先 | リクエスト内容 | 検証の基準 |
|---|---|---|---|
| OpenCode | `api.github.com` | `GET /repos/anomalyco/opencode/releases/latest`（`LocalRuntimeReleaseClient`）、続いてGitHubが示すリリースアセット（`github.com`から提供） | GitHubが報告するアセットのSHA-256ダイジェスト |
| Antigravity | `api.github.com` | `GET /repos/google-antigravity/antigravity-cli/releases/latest`（`AntigravityReleaseClient`）、続いてGitHubが示すリリースアセット（`github.com`から提供） | GitHubが報告するアセットのSHA-256ダイジェスト。問い合わせが失敗した場合は、このビルドに固定されたリリースと組み込み済みハッシュにフォールバック |
| Codex | `registry.npmjs.org` | `GET /@openai/codex/latest`、`GET /@openai/codex/<version>-linux-<arch>`（`CodexReleaseClient`）、続いてレジストリが報告するtarballのURL | レジストリが記録するSHA-512の`integrity` |
| Claude Code | `downloads.claude.ai`（およびサンドボックスのAlpineパッケージリポジトリ） | `keys/claude-code.rsa.pub`の`wget`、続いて`claude-code/apk/latest`に対する`apk update`／`apk add` — AndCodeのHTTPクライアントではなく、サンドボックス内のツール自身が実行 | AnthropicのAPK署名鍵 |

AndCode自身のHTTPクライアントが行うリクエスト（OpenCode、Antigravity、Codex）について:

- **送信される内容:** 認証なしのHTTPS `GET`です。問い合わせには`User-Agent: AndCode`（GitHubへの2つの問い合わせでは、
  これに加えてGitHubの`Accept: application/vnd.github+json`メディアタイプ）とHTTPライブラリが付加する標準ヘッダーが付き、
  Codexのtarballのダウンロードも同様です。OpenCodeとAntigravityのアーカイブのダウンロードは、HTTPライブラリ既定の
  `User-Agent`（`okhttp/…`）のみの単純なリクエストで、中断したダウンロードを再開する場合に限り`Range`ヘッダーが付きます。
  いずれにも`Authorization`ヘッダーはなく、プルリクエストに使用するGitHubトークン（**設定 → GitHub**）は、接続済みであっても
  これらのリクエストには付与されません。プロンプト、コード、ワークスペースのパス、アカウント識別子、資格情報も含まれません。
- **接続先から見える情報:** あらゆるHTTPSリクエストと同様に、サーバーの運営者（GitHub、npm、Anthropic）には、
  利用者のIPアドレス、リクエストの時刻、そしてどのリリースが要求されたかが見えます。問い合わせではクライアントが`AndCode`と
  名乗り、GitHubからのアーカイブのダウンロードではHTTPライブラリとしてのみ識別されます。Claude Code向けに行われる
  `apk`／`wget`のリクエストでは、それらのツール固有の`User-Agent`が見えます。
- **リダイレクト:** GitHubのリリースダウンロード（`github.com/<owner>/<repo>/releases/download/…`）は、GitHubが運営する
  GitHub独自のアセット保存先ホスト（例：`release-assets.githubusercontent.com`）へリダイレクトされる場合があります。
- **失敗時の挙動:** Antigravityは、問い合わせが失敗した場合、固定されたリリースにフォールバックします。OpenCodeとCodexでは、
  問い合わせの失敗は確認またはインストールの失敗として報告され、他の接続先は試行されません。
- **AndCodeのサーバーは経由しない:** これらのリクエストは、AndCodeが運用するサーバーには一切送られません。
- **実行されるタイミング:** 上記の各エージェントの項で説明した操作の場合のみで、定期的なポーリング処理はありません。
  無人で実行され得るのはOpenCodeの確認です。**設定 → エージェント → OpenCode**を開いたときや更新確認をタップしたとき
  のほか、アプリが古いローカルランタイムを自動的に再起動する際（クラッシュ後、サービスの復元、ウォッチドッグ）にも
  実行されます（OpenCodeの項を参照）。OpenCodeの初回インストールと再インストールは、問い合わせを行わずに、
  アプリに固定されたリリースのアーカイブを`github.com`から取得します（OpenCodeの項を参照）。

## 4つのエージェントに共通する性質

- AndCodeは、4つのエージェントいずれについても、独自の認証サーバー、トークン発行サービス、APIプロキシを運用していません。ブラウザへの受け渡しはすべて単純なAndroidの`Intent.ACTION_VIEW`であり、コードの送信はすべて*既存の*CLIプロセス自身の標準入力／PTYへの書き込みです。
- Claude Code、Codex、AntigravityのOAuthトークンは、それが書き込まれたrootfsから外へ出ることはありません。Androidの`SharedPreferences`／`EncryptedSharedPreferences`へコピーされることも、サーバーへ送信されることも、エージェント間で共有されることもありません（Antigravityの資格情報をClaude Code、Codex、OpenCodeの認証に転用することはできず、その逆も同様です）。
- 完全な認証URL（`code`／`state`のクエリパラメータを含みうるもの）は、永続的なログへは書き込まれません。詳細は`SecretRedaction.redactUrlQuery`および`AntigravityAuthParser.redact`を参照してください。
