# AndroidローカルOpenCodeランタイム

## 目的

AndCodeは、PCへ接続しなくてもAndroid端末内でOpenCodeを起動し、PCリモート実行と同じチャット・セッション・承認UIから操作できる。

OpenCode本体はフォークしない。アプリ専用ストレージ内へLinuxユーザーランドを構築し、その内部でOpenCode公式musl配布物を実行する。

## 現在の実装

現在のAPKには、AndroidローカルOpenCodeをセットアップして利用するための次の機能が入っている。

- arm64-v8a・x86_64向けPRootランナー
- Alpine Linux 3.24.1 minirootfsのダウンロード
- OpenCode 1.18.3公式muslバイナリのダウンロード
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

## セキュリティ

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
- Gradle・Kotlin/Javaコンパイル・JVM単体テスト（規模を問わない）
- 長時間の高負荷処理

PRootはptraceでsyscallをエミュレートするため、JVMが初期化に要求する実行可能メモリページ（`mprotect(PROT_EXEC)`）を確保できない。このためランタイム内ではJVMを使う処理が一切動作しない。AndroidアプリのビルドとInstrumentation Testは、PC等でビルドしたAPKを `android-app install` で端末へ入れ、`android-instrument` で実行する。

重い作業はPCリモート実行へ切り替える。
