# AndroidローカルOpenCodeランタイム

## 目的

AndCodeは、PCへ接続しなくてもAndroid端末内でOpenCodeを起動し、PCリモート実行と同じチャット・セッション・承認UIから操作できる。

OpenCode本体はフォークしない。アプリ専用ストレージ内へLinuxユーザーランドを構築し、その内部でOpenCode公式musl配布物を実行する。

## 現在の実装

現在のAPKには、AndroidローカルOpenCodeをセットアップして利用するための次の機能が入っている。

- arm64-v8a・x86_64向けPRootランナー
- Alpine Linux 3.24.1 minirootfsのダウンロード
- OpenCode 1.19.6公式muslバイナリのダウンロード
- URL・サイズ・SHA-256を固定したランタイムマニフェスト
- 一時領域への展開と、成功後だけ本番環境へ切り替えるインストール
- Alpine内へのGit、Bash、curl、OpenSSH client、CA証明書、ripgrep、libstdc++導入
- `127.0.0.1:4097`限定のOpenCodeサーバー起動
- Foreground Serviceによるセットアップ・起動・停止・稼働監視・自動復旧
- 未導入、導入中、起動中、停止中、稼働中、破損、未対応ABIの状態管理
- 作業先画面からのセットアップ、起動、停止、修復・再セットアップ
- 容量、空き容量、プロセスツリー全体のメモリ、PID、稼働時間、必須ツール、ログの診断画面
- 確認ダイアログ付きの完全削除。Linux環境、キャッシュ、ログ、ローカル作業領域、残留プロセスを削除
- OpenCode公式GitHub Release APIによる最新版確認、リリースノート・必要容量表示
- GitHub Release assetの`sha256:` digest、HTTPS URL、ファイルサイズを検証する更新ダウンロード
- 候補バイナリの`opencode --version`検証後だけ行うアトミック切り替え
- 更新失敗時の旧版自動復旧、直前バージョンへの手動ロールバック
- 更新・ロールバック中断時のトランザクションジャーナル復旧
- ローカルとPCリモートで共通のREST/SSEクライアント

API 36のARM64エミュレーターで、以下を実動作確認している。

```text
OpenCode 1.18.3
Git 2.54.0
Bash 5.3.9
ripgrep 15.1.0
OpenSSH 10.3p1
CA証明書
GET /global/health
モデル・エージェント取得
big-pickleによる推論
SSEによるリアルタイム応答
公式GitHub Release確認（1.18.3を最新版として判定）
アプリ専用ストレージ上の更新・ロールバックInstrumentation Test
```

現在のマニフェストはOpenCode 1.19.6を固定している。API互換性は1.18.5からのOpenAPI差分で確認済みだが、実機での再確認はまだ行っていない。

## ディレクトリ構成

```text
files/runtime/
├─ metadata.json
├─ metadata.rollback.json
├─ update-transaction.json          # 更新中だけ存在
├─ rollback-transaction.json        # ロールバック中だけ存在
├─ cache/
├─ environment/
│  └─ rootfs/
│     └─ usr/local/bin/
│        ├─ opencode
│        └─ opencode.rollback
├─ command-suite/
├─ workspace/
└─ logs/
```

PRoot本体・ローダー・必要共有ライブラリは、APKのABI別ネイティブライブラリとして配置する。AlpineとOpenCode本体は初回セットアップ時に取得するため、APKを不必要に大型化しない。

## セットアップフロー

```text
ユーザーが「この端末へセットアップ」
↓
ABI・ランタイムマニフェストを確認
↓
Foreground Serviceを開始
↓
AlpineとOpenCodeを一時キャッシュへダウンロード
↓
ファイルサイズとSHA-256を検証
↓
ステージング領域へ安全に展開
↓
Alpine内へ必須ツールを導入
↓
opencode --versionを確認
↓
環境を本番ディレクトリへ切り替え
↓
opencode serve --hostname 127.0.0.1 --port 4097
↓
/global/healthが成功したらReady
```

ダウンロード、検証、展開、ツール導入のいずれかに失敗した場合、破損状態と理由を表示し、修復・再セットアップを選択できる。診断画面ではOpenCode、Git、Bash、curl、SSH、ripgrep、CA証明書を実際のLinux環境内で確認する。

完全削除はOpenCodeとPRootのプロセスツリーを停止してから、`files/runtime`全体を削除する。API 36 ARM64エミュレーターで、削除後にOpenCode・PRoot・Foreground Service・ランタイムディレクトリが残らないことを確認している。

## 更新・ロールバックフロー

```text
公式GitHub Release APIから最新版を取得
↓
対象ABIのmusl asset、HTTPS URL、サイズ、SHA-256 digestを検証
↓
空き容量を事前確認
↓
.partialへダウンロードしSHA-256を照合
↓
候補バイナリを展開して実行権限と--versionを検証
↓
OpenCodeサーバーを停止
↓
更新ジャーナルを永続化
↓
opencode / metadata.json と候補を同一ファイルシステム上で切り替え
↓
新バージョンを起動してhealthを確認
↓
成功時は切り替えを確定、失敗時は旧バージョンを自動復旧
```

直前バージョンは`opencode.rollback`と`metadata.rollback.json`として1世代だけ保持する。手動ロールバックも同じくジャーナル付きで実行し、対象版を起動できない場合は元の版へ戻す。プロセス強制終了やアプリ中断でジャーナルが残った場合、次回操作時に整合するバイナリとmetadataの組へ復旧する。

API 36 ARM64エミュレーターでは、`targetContext.filesDir`上で実ファイルの更新切り替え、metadata更新、直前版保持、再ロールバック、ジャーナル確定をInstrumentation Testで確認している。

## 端末ストレージへのアクセス

サンドボックスが元々見えていたのはアプリ専用ディレクトリだけ（Alpine rootfsと`/workspace`マウント）で、端末内のファイルは一切見えなかった。唯一の経路はSAF取り込みで、これはツリーをアプリ領域へ**コピー**するため、エージェントは元ファイルから切り離された複製を編集していた。

全ファイルアクセス（`MANAGE_EXTERNAL_STORAGE`）を許可すると、端末のストレージがそのままサンドボックスへbindされる。

| ゲストパス | ホストパス | 用途 |
| --- | --- | --- |
| `/sdcard` | `Environment.getExternalStorageDirectory()` | 内部共有ストレージ。Androidが使う別名と同じ |
| `/storage` | `/storage` | SDカード・USBドライブを含む全ボリューム。端末固有のID（`/storage/1A2B-3C4D`）はプラットフォームと同じ綴りでしか意味を持たないため、同一パスへbindする |

- 権限が未許可の間、`DeviceStorage.bindArguments()`は空を返し、サンドボックスは従来どおり狭いまま
- bindはPRootプロセス起動時に固定されるため、許可直後に稼働中のランタイムは再起動する（`WorkspaceViewModel.onDeviceStorageAccessChanged`）
- フォルダ選択ダイアログのルートに`/sdcard`と`/storage`が並び、そのままワークスペースとして登録できる
- Android 10では`MANAGE_EXTERNAL_STORAGE`が存在しないため、`READ_EXTERNAL_STORAGE`と`requestLegacyExternalStorage`で代替する

OSの制約として、他アプリの`Android/data`と`Android/obb`は全ファイルアクセスでも読めない。また共有ストレージはFUSE上にあり、実行ビットやシンボリックリンクを保持しないため、リポジトリを`/sdcard`に置くとgitの一部操作が期待どおりに動かないことがある。恒常的な作業には`/workspace`を勧める。

## セキュリティ

- 端末ストレージのbindはユーザーが全ファイルアクセスを許可した場合にだけ行う
- 初回セットアップではダウンロード先URL、期待サイズ、SHA-256をマニフェストへ固定する
- OpenCode更新では公式GitHub Release APIの対象assetだけを選び、HTTPS URL・サイズ・`sha256:` digestを必須とする
- 不一致のファイルは展開・有効化しない
- アーカイブ内のパストラバーサルと危険なシンボリックリンクを拒否する
- OpenCodeサーバーは`127.0.0.1`だけへbindする
- ランタイムはアプリ専用ストレージへ保存する
- ランタイムログへAPIキー、パスワード、Authorizationヘッダーを記録しない
- Androidアプリは危険操作を無条件に自動承認しない

## 追加済み（2026-07-19）

1. Storage Access Frameworkによる外部フォルダのローカル `/workspace` への取り込み
2. プロバイダーAPIキーの暗号化保存と `auth.json` 同期（起動時）
3. 公式Releaseからの更新確認・アトミック更新・ロールバックUI

## 現在残っている実装

1. 物理端末でのバッテリー・メモリ・容量計測
2. API 26 / 34 デバイスマトリクス検証

## プロバイダー認証

利用できる認証方法は、選択中のOpenCodeランタイムの `GET /provider/auth` から取得する。Androidアプリ側でOpenAIやAnthropicなどを固定定義せず、ランタイムが返すプロバイダー、APIキー方式、OAuth方式、追加質問項目を共通画面へ表示する。

### 接続方法

1. ローカルまたはリモートのOpenCode実行先を選択する。
2. 設定画面の「プロバイダー」で対象を選ぶ。
3. OpenCodeが提示した認証方法を選択する。
4. 必要に応じてリージョン、アカウント、テナントなどの追加項目を入力する。
5. APIキー方式ではキーを入力し、OAuth方式ではブラウザの案内に従う。

APIキーの登録は `PUT /auth/{providerID}`、切断は `DELETE /auth/{providerID}` を使い、選択中のOpenCode実行先へ直接反映する。Androidローカルだけでなく、Mac・Linux・Windows上のリモートOpenCodeも同じ画面から設定できる。

OpenCodeがOAuthの `auto` 方式を返した場合、Androidはブラウザを開くと同時に認証完了を待機する。`code` 方式ではブラウザで取得したコードをAndroidへ入力して完了する。OAuthトークンはAndroidアプリではなく、接続先OpenCodeが管理する。

旧バージョンでAndroidの暗号化設定へ保存したローカルAPIキーは、ランタイム起動前のブートストラップ用途として引き続き利用できる。同期処理はアプリ管理対象のエントリだけを更新し、OAuthやユーザー管理の `auth.json` エントリを保持する。

接続または切断が成功すると、プロバイダーとモデルのカタログを自動的に再取得する。

## ADB連携（無線デバッグ）

ランタイム内の `adb` から、この端末自身へ無線デバッグ接続できる。接続すると、ランタイム内のツールが端末の画面キャプチャ・UI自動操作・アプリインストール・Instrumentation Testを実行できる。ADB接続は特定のエージェントではなく、OpenCode・Claude Code・Antigravityが共有するランタイム全体の設定である。

### セットアップ

1. 端末の開発者オプションで「無線デバッグ」を有効にする。
2. 設定 →「ローカルランタイム」→「Android Debug Bridge (ADB)」カードを開く。
3. 「ペアリング」で、無線デバッグ画面のペアリングポートと6桁コードを入力する。
4. 「接続」をタップする。

### 再接続の自動化

- 接続済みポートは暗号化設定へ永続化される。ペアリング鍵はrootfsの `/root/.android` に保存され、再インストール時も引き継がれるため、再ペアリングは不要である。
- アプリ起動時とランタイムReady時に、永続化済みポートへ自動で `adb connect` を再実行する。adbサーバーはPRoot内でランタイムと共に再起動するため、再接続は毎回必要である。
- 30秒ごとのヘルスチェックが接続断を検知し、永続化済みポートがあれば自動再接続する。ポート未設定の場合は何もしない。

### ヘルパースクリプト

ランタイムの `/usr/local/bin` に次が配置される（Alpine・Debian両rootfs）。

- `android-screenshot` — 画面キャプチャ
- `android-vision` — UI要素ラベル付きアノテーション画像とJSON
- `android-app` — 他アプリの install / launch / stop / clear / logcat / list
- `android-instrument` — `am instrument` によるInstrumentation Test実行

## スケジュールMCPツール

ゲスト内エージェント（OpenCode・Claude Code・Antigravity）からスケジュール設定を参照・作成・編集・削除・有効/無効切り替え・即時実行できるようにする。スケジュールはアプリの暗号化設定に保存されており、ゲストからは直接読めないため、既存のブラウザMCPと同じ方式でファイルブリッジを介する。

- `andcode-schedule-mcp.py` が `/usr/local/bin` に入り、全エージェントのMCP設定（OpenCodeの`opencode.json`、Claude Codeの`.claude.json`、Antigravityの`mcp_config.json`）へ `and-code-schedule` サーバーとして登録される
- サーバーは `/workspace/.and-code/schedule-bridge/pending/<id>.json` へリクエストを書き、アプリ側の`ScheduleBridge`がポーリングして実行し、`responses/<id>.json` へ結果を書き戻す
- ポーリングは、アプリが前面にあるか、ランタイムが作業中（チャットのセッション実行・スケジュール実行など）の間だけ動く。どちらでもないときは端末がスリープでき、ゲストも止まっているため待機し、前面に戻るか作業が始まった時点で即座に処理する。ゲスト側の応答待ち（90秒）を過ぎた古いリクエストは、実行せず期限切れエラーで破棄する
- 書き込み系はスケジューリングUIと同じく暗号化リポジトリとアラーム（`ScheduleManager`）を通るため、変更はアプリのスケジュール画面へ即反映される
- 提供ツール: `schedule_list` / `schedule_get` / `schedule_runs` / `schedule_create` / `schedule_update` / `schedule_delete` / `schedule_set_enabled` / `schedule_run_now`

## 設定MCPツール

ゲスト内エージェント（OpenCode・Claude Code・Antigravity）に頼むだけで、AndCode自体の設定変更と、全エージェント共通のMCPサーバー管理ができるようにする。設定はアプリの暗号化設定に保存されておりゲストからは直接読めないため、スケジュールMCPと同じファイルブリッジ方式を取る。

- `andcode-settings-mcp.py` が `/usr/local/bin` に入り、全エージェントのMCP設定へ `and-code-settings` サーバーとして登録される（既存インストールも起動時に補完される）
- サーバーは `/workspace/.and-code/settings-bridge/pending/<id>.json` へリクエストを書き、アプリ側の`AgentSettingsBridge`が実行して `responses/<id>.json` へ書き戻す。ポーリングの条件、期限切れリクエストの破棄はスケジュールブリッジと同じ
- 設定の項目・型・取り得る値・範囲・既定値は `AgentSettingsCatalog` に宣言されており、`settings_list` で実行時に取得できる。書き込みは入力を検証してから `AppPreferencesRepository` を通すので、画面は即時に追従する。`settings_set_many` は全件を検証してから適用し、1件でも不正なら何も変更しない
- カテゴリ: `model`（モデル・エージェント選択、お気に入り、非表示）/ `appearance`（テーマ・フォント・言語）/ `chat` / `sessions`（ピン留め、自動アーカイブ）/ `runtime` / `voice`（TTS・ウェイクワード）/ `security`
- **エージェントからは読み取り専用**: 権限リクエストの自動承認（`autoAcceptPermissions`）、ゲストブラウザの操作許可（`guestBrowserAgentControlEnabled`）、アナリティクス（`analyticsEnabled`）は、エージェント自身の権限を広げる／ユーザーの同意が前提なので、ユーザーがアプリの設定画面で変更する。マイク権限とサービス起動が要るウェイクワードの有効化（`wakeWordEnabled`）も同様。`settings_list` では `writable: false` と理由が返る
- APIキー（TTS）は書き込み専用で、読み取りは設定済みかどうか（`isSet`）しか返さない
- MCPサーバー管理: `mcp_servers_list` / `mcp_server_add`（ローカルは `command` のargv、リモートは `url`）/ `mcp_server_remove`。3エージェントそれぞれの設定ファイル形式で全rootfs（Alpine・Antigravity）へ書き込む。組み込みの `and-code-*` サーバーは置換・削除できない。環境変数の値とヘッダーは一覧に出さない。反映は各エージェントの次のセッション開始時
- 提供ツール: `settings_list` / `settings_get` / `settings_set` / `settings_set_many` / `settings_reset` / `settings_update_list` / `mcp_servers_list` / `mcp_server_add` / `mcp_server_remove`

## 組み込みターミナル

ワークスペースのターミナルアイコンから開く、サンドボックス内の対話シェル。1コマンドごとに起動して30秒で打ち切る方式ではなく、画面を開いている間はシェルが常駐する。

- **PTY**: AndroidのJavaからptyは開けないため、Claude Code・Antigravityのサインインと同じく、サンドボックスの `/usr/bin/script -qefc` にptyを確保させる（`LocalTerminalLauncher`）。シェルは本物の制御端末を持つので、行編集、パスワード入力（エコーなし）、`isatty` 判定、`Ctrl+C`（ptyがSIGINTへ変換）が動く
- **サイズ**: `script` はパイプ相手だとウィンドウサイズを0x0と報告するため、起動時に `stty rows/cols` で画面から測った文字数・行数を設定する。起動後に画面サイズ（回転など）が変わっても、起動済みシェルのサイズは変わらない
- **開始ディレクトリ**: 開いたワークスペースのパス（サンドボックス内の絶対パス）。`cd` できなければ `/root`。ワークスペース用の `/workspace` もバインドされる
- **出力**: ストリーミングでANSI解釈し（`AnsiTerminalEmulator`）、SGRの色（16色・256色・トゥルーカラー）、`\r` による行の上書き、行内編集、画面クリアを扱う。スクロールバックは最大10,000行で、古い行から捨てる
- **入力**: 入力欄の内容をEnter付きで送る。ソフトキーボードにない `Esc` / `Tab` / `Ctrl+C` / `Ctrl+D` / 矢印キーは入力欄の上のキー列から送る。Tab と矢印キーはシェルの行編集に作用するので、入力欄に残っている文字を先にシェルへ送ってから送る（シェルは入力欄の文字をまだ知らないため）。出力の追従は、指でスクロールして末尾から離れると止まり、末尾へ戻すか、送信・キー送信・クリアをすると再開する。マウスホイールやTalkBackのスクロールは追従の解除として扱われない
- **開発サーバーの検出**: 出力に出た `http://localhost:5173/` 形式のURL（`localhost` / `127.0.0.1` / `0.0.0.0` / `[::1]`）や `listening on port 3000` のような行から、**ポート番号だけ**を拾う（出力のホスト名は使わないので、出力でアプリ内ブラウザの行き先を変えられない）。拾っただけのポートは出さない: 入力したコマンドのエコーにも `curl http://localhost:8080` のようなURLが含まれるため、`127.0.0.1` か `::1` へのTCP接続が実際に通ったものだけを、ターミナル上部のチップ（`:5173`）として表示する。押すとアプリ内ブラウザで `http://localhost:PORT/` を開く（`DevServerDetector` / `DevServerTracker` / `LoopbackDevServerProbe`）。`/proc/net/tcp` はAndroid 10以降アプリから読めないので、接続確認で判断する
  - 確認は2秒ごと。連続2回失敗すると「停止」表示（押せない）になり、約30秒応答がなければチップを消す。出力にサーバーの記述が現れるまで確認は始めず、記述がなければ何も動かない。同時に追うのは8ポートまで。満杯のときは、一度も応答していない最も古い候補（コマンドのエコーや途中で切れた断片）を押し出して新しいポートを入れる。一度応答したポートは押し出さない
  - 調べるのは画面末尾200行のうち、前回から新しく書かれた・書き換わった行だけ。一度に200行以上流れる出力の手前にあるURLは見落とす。画面に残っている古いURLが、忘れたサーバーを呼び戻すことはない
  - 画面幅で折り返された行（幅いっぱいまで埋まった行は次の行に続くとみなす）は、つなげてから調べる（`WrappedLines`）ので、`python -m http.server` のバナーのようにURLが折り返し位置をまたいでも検出できる。行がまだ続くかもしれない間は、行末の数字を（ポートの前半かもしれないので）ポートとして読まない。「幅いっぱい＝続く」は行の長さからの推定で、エミュレータが折り返したかどうかの記録ではない。ちょうど幅で終わって改行された行も次の行とつなげるため、そういう行の末尾のポートが次の行の頭の数字と一緒に読まれることがある（応答しない候補は押し出されるか約30秒で消える）
  - シェルが終わるとチップも消える。エージェントなど、ターミナル以外から起動したサーバーはここには出ない（アプリ内ブラウザのURL欄に直接入力する）
- **ライフサイクル**: 画面を閉じるとシェルと配下のプロセス（開発サーバーなど）を終了する。PRoot本体だけをkillしてもゲスト側の `script` / `bash` は残る（`--kill-on-exit` はPRoot自身が終了するときにしか効かない。`ProcessTree.kt` の実機検証メモ参照）ため、`/proc` から子孫を辿って末端から順にkillする（`killProcessTree`）。画面を閉じるのはUIスレッドなので、このkillはバックグラウンドスレッドで行う
- **電力**: シェルが生きている間は、ランタイムの作業リースを保持し、端末がサスペンドしないようにする。バックグラウンドでプロンプトに居るだけでも保持し続けるので、使い終わったら画面を閉じる。ランタイムの排他ロックはプロセス起動の間だけ取り、シェルの常駐中は取らない（更新が閉じるまで待たされないため）
- **ランタイムの停止・再起動との関係**: OpenCodeランタイムの停止・再起動・クラッシュ後の再起動は、ランタイムディレクトリを含むPRootプロセスをまとめてkillする（`LocalRuntimeProcessLauncher`）ので、開いているターミナルのシェルも終了する。その場合は「セッションが終了しました」と再起動ボタンが出る。ランタイムの更新・削除とターミナルの調停は今のところない
- **非対応**: `vim` や `htop` のような全画面プログラム（代替スクリーン、スクロール領域、マウス）、ウィンドウサイズ変更の追従、カーソル位置の問い合わせ応答（`ESC[6n`）。未知のエスケープシーケンスは無視され、文字としては表示されない
- **既知の制限**: 全角文字（CJK）も1セルとして数えるため、日本語を入力中のreadlineの行編集（`\b` などで戻る位置の計算）や、日本語を含む行の上書きで表示がずれることがある。OSC/DCS文字列は `BEL`・`ESC`・`CAN`・`SUB` でしか終わらないので、バイナリを `cat` すると次の `ESC` まで出力が欠ける。異なるSGRスタイルは4,096種類までしか保持せず、それを超えた分は既定スタイルで描く。CSIの数値パラメータは9,999に丸める（信頼できない出力でオーバーフローさせないため）
- **対応しない前提**: PRoot環境はJVMを実行できない（下記「Android上の制約」）。Gradleの実行を目的にしたものではなく、対話シェルを提供するだけである

## Android上の制約

Androidローカル実行はPCの完全な代替ではない。

適する用途:

- 小規模リポジトリの調査・編集
- Git操作
- 軽いスクリプト実行
- ドキュメント作成
- 簡易テスト

対象外:

- Docker
- Android Emulatorの内側から別のAndroid Emulatorを動かすこと
- iOSビルド
- JDK 21（`openjdk21`）を使う処理（起動できない。下記参照）
- 長時間の高負荷処理

JDKはバージョンによって挙動が異なる。

- `openjdk21`（`default-jvm` の既定）は起動時に `mprotect(PROT_EXEC)` で失敗し、`-XX:` オプションの解析前に終了するため調整できない。ただしこのメッセージはHotSpotが実行可能メモリ周りの任意の失敗で出すもので、AndroidのW^Xポリシーを示すとは限らない（`mmap`/`mprotect` 自体は成功する）。表示される「grsecurity/PaX」のヒントは当てにならず、`strace -f java -version` で実際に失敗する要求を確認する。
- `openjdk17`（`/usr/lib/jvm/java-17-openjdk`）はランタイム内で起動し、Gradle・Kotlin/Javaコンパイル・D8・apksignerが動作したという報告がある（Xiaomi 24117RN76E 1台での報告であり、全端末での保証ではない）。ランタイムは `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` を既定で設定している（`default-jvm` が指すJDK 21は使わない）。別のシェルから使う場合は同じ値を設定する。

arm64でAndroidアプリをビルドする場合の注意:

- `android-build-setup --accept-licenses` を一度実行する。`$ANDROID_HOME/licenses/` へライセンスを配置し（Android SDK Licenseへの同意になるため、フラグ指定がなければ何もしない）、arm64ではSHA-256を検証した静的リンクのaarch64版 `aapt2`（Google公式はarm64 Linux版がなく動かない）を取得して `~/.gradle/gradle.properties` の `android.aapt2FromMavenOverride` に設定する。取得した `aapt2` はAGP 7.4.2・8.5.2で動作した報告があるが、AGP 9は未確認。
- SDK約740MB・Gradleキャッシュ約3.7GBを消費する。クリーンビルドは5〜8分程度。
- NDKのarm64 Linux版は存在しないため、C/C++を含むプロジェクトはPC/CIでビルドする。

上記を満たせない場合は、PC等でビルドしたAPKを `android-app install` で端末へ入れ、`android-instrument` で実行する。

重い作業はPCリモート実行へ切り替える。
