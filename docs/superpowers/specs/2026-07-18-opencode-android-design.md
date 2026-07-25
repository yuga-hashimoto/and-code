# OpenCode Android 設計書

## 1. 目的

OpenCode Androidは、OpenCodeをAndroidから快適に利用するためのAndroid専用OSSクライアントである。

同一のAndroidアプリから次の2つの実行方式を扱う。

1. Android端末内でOpenCodeを実行する「ローカル実行」
2. PC・Mac・Linuxサーバー上の`opencode serve`へ接続する「リモート実行」

加えて、Androidの既定デジタルアシスタントとして設定でき、ホームジェスチャー・電源ボタン長押し・ウェイクワードから、選択したOpenCodeモデルとの音声会話を開始できるようにする。

## 2. プロダクト定義

- アプリ名: `OpenCode Android`
- リポジトリ名: `opencode-android`
- 対象OS: Androidのみ
- 最小Androidバージョン: Android 8.0 / API 26
- UI: Kotlin、Jetpack Compose、Material 3
- 基本テーマ: ダークモード
- 色調: チャコール・濃紺を基調とし、青・青緑を控えめなアクセントに使用する
- 表示言語: 日本語・英語
- ライセンス: MIT
- OpenCode公式アプリと誤認されないよう、READMEとアプリ内Aboutに「非公式OSSクライアント」と明記する

## 3. 成功条件

### 3.1 第一完成版

第一完成版は、次の操作を実機で完遂できる状態とする。

1. PC上で起動した`opencode serve`をAndroidアプリへ登録する
2. ヘルスチェックでOpenCodeバージョンと接続状態を確認する
3. OpenCodeのプロジェクト・セッション・プロバイダー・モデルを取得する
4. 新規セッションを作成してメッセージを送信する
5. SSEイベントを受信し、応答・ツール実行・承認待ちをリアルタイム表示する
6. 承認要求へAndroidから回答する
7. Androidの音声認識で入力し、Android TTSで回答を読み上げる
8. 既定デジタルアシスタントに設定し、ホームアシスト起動から同じチャット画面を開く
9. ウェイクワード検知をユーザーが明示的に有効化し、Foreground Serviceで待機させる
10. 接続先、選択モデル、音声設定を暗号化して保存する

### 3.2 完成形

第一完成版に加えて、次を実装する。

- Androidアプリ内で必要なLinuxユーザーランド、Git、Bash、Node/Bun、OpenCodeをセットアップする
- AndroidローカルOpenCodeを`127.0.0.1`で起動し、リモート実行と同じAPIクライアントで操作する
- ランタイムの診断、更新、修復、空き容量表示
- AndroidローカルセッションからPCへのハンドオフ
- ファイル閲覧、検索、差分確認、セッションTodo表示
- 複数PC接続先の管理とmDNS検出

## 4. 非目標

初期設計では次を対象外とする。

- iOS、Web、Windows、macOS向けクライアント
- リモートデスクトップ画面の転送・マウス操作
- OpenCode本体のフォーク
- OpenCodeプロトコルの独自改変
- OpenCode Zenをアプリ独自の推論APIとして直接呼び出すこと
- Android Accessibility Serviceによる他アプリの自動操作
- Play Store向け課金

## 5. 検討した方式

### 5.1 OpenCodeをアプリへ直接埋め込む

OpenCode本体とBunをAPKへ静的に含める方式。

**利点**

- 初回セットアップが簡単
- オフラインで必要ファイルが揃う

**欠点**

- APKが大型化する
- OpenCode更新のたびにアプリ更新が必要になる
- Android ABI対応をアプリ側で継続保守する必要がある

採用しない。

### 5.2 リモート専用クライアント

AndroidではOpenCodeを実行せず、PCの`opencode serve`だけを操作する方式。

**利点**

- 最も軽く、安定している
- OpenCode公式HTTP APIをそのまま使える
- PC側のGit・Docker・Flutter環境を利用できる

**欠点**

- PCが停止していると使えない
- Android単体利用を満たさない

第一完成版の中心として採用するが、完成形としては不十分。

### 5.3 ローカル・リモート共通バックエンド方式

Android内OpenCodeとPC上OpenCodeの両方を、同じ`OpenCodeBackend`インターフェースで扱う方式。

**利点**

- UIを共通化できる
- ローカル実行を後から安全に追加できる
- OpenCode本体の更新とAndroidアプリの更新を分離できる
- 接続先切り替えとハンドオフを自然に実装できる

**欠点**

- ローカルランタイム構築が複雑
- Android上のOpenCode公式ネイティブバイナリがないため、Linuxユーザーランドが必要

この方式を採用する。

## 6. 全体アーキテクチャ

```text
Android OS
├─ VoiceInteractionService
├─ WakeWordForegroundService
├─ SpeechRecognizer / TextToSpeech
└─ OpenCode Android App
   ├─ Compose UI
   ├─ AppState / ViewModels
   ├─ OpenCodeBackend
   │  ├─ RemoteOpenCodeBackend
   │  └─ LocalOpenCodeBackend
   ├─ OpenCodeApiClient
   │  ├─ REST client
   │  └─ SSE event client
   ├─ ConnectionRepository
   ├─ PreferenceRepository
   ├─ VoiceRepository
   └─ LocalRuntimeManager
      ├─ RuntimeDoctor
      ├─ RuntimeInstaller
      ├─ RuntimeProcessManager
      └─ RuntimeUpdater
```

OpenCode公式サーバーAPIを唯一の操作境界とする。アプリはOpenCodeのDBや設定ファイルを直接解析しない。

## 7. バックエンド抽象化

```kotlin
interface OpenCodeBackend {
    val id: String
    val displayName: String
    val kind: BackendKind

    suspend fun health(): BackendHealth
    suspend fun listProjects(): List<OpenCodeProject>
    suspend fun listSessions(): List<OpenCodeSession>
    suspend fun createSession(title: String?): OpenCodeSession
    suspend fun listMessages(sessionId: String): List<OpenCodeMessage>
    suspend fun listProviders(): ProviderCatalog
    suspend fun listAgents(): List<OpenCodeAgent>
    suspend fun sendMessage(request: SendMessageRequest)
    suspend fun abortSession(sessionId: String): Boolean
    suspend fun respondToPermission(
        sessionId: String,
        permissionId: String,
        response: PermissionResponse,
        remember: Boolean
    ): Boolean
    fun events(): Flow<OpenCodeEvent>
}
```

`RemoteOpenCodeBackend`は登録されたHTTPエンドポイントへ接続する。

`LocalOpenCodeBackend`は`LocalRuntimeManager`で端末内OpenCodeを起動し、`127.0.0.1`のHTTPエンドポイントへ接続する。API層は共有する。

## 8. OpenCode API利用方針

### 8.1 接続

- 標準ポート: 4096
- ヘルスチェック: `GET /global/health`
- イベント: `GET /global/event`のSSE（未対応サーバーでは`GET /event`へフォールバック）
  - `GET /event`はインスタンス単位（`directory`クエリ、既定はサーバーのカレントディレクトリ）に
    スコープされるため、別の作業フォルダで作成したセッションのイベントが一切届かない。
    ツール出力もストリーミング本文も承認要求も受け取れず、承認待ちのまま実行が止まる。
    横断ストリームの`/global/event`を使い、`{directory, payload}`エンベロープを展開する。
- 認証: HTTP Basic認証
- アプリは平文HTTPをデフォルトではローカルネットワーク・Tailscale接続だけに許可する
- インターネット越しにはHTTPSを必須にする

### 8.2 主要API

- `GET /project`
- `GET /provider`
- `GET /agent`
- `GET /session`
- `POST /session`
- `GET /session/:id/message`
- `POST /session/:id/prompt_async`
- `POST /session/:id/abort`
- `POST /session/:id/permissions/:permissionID`
- `GET /session/:id/diff`
- `GET /session/:id/todo`
- `GET /file/status`

APIレスポンスの未知フィールドは無視し、OpenCode更新による追加フィールドに耐える。

## 9. 画面設計

### 9.1 下部ナビゲーション

- ホーム
- チャット
- 接続
- セッション
- 設定

### 9.2 ホーム

- 現在の実行先
- OpenCode接続状態とバージョン
- 現在のモデル・エージェント
- 新しいチャット
- 最近のセッション
- 承認待ち件数
- ローカルランタイム状態

### 9.3 チャット

- Androidローカル・PCリモートの実行先切り替え
- プロジェクト選択
- モデル選択
- エージェント選択
- テキスト入力
- 音声入力
- 応答ストリーミング
- ツール実行カード
- 承認・拒否
- 中断
- TTS読み上げ

### 9.4 接続

- PC接続先一覧
- 接続追加・編集・削除
- QR入力は完成形で追加
- URL、表示名、ユーザー名、パスワード
- 接続テスト
- ローカルランタイムカード

### 9.5 セッション

- セッション一覧
- 実行中・待機中・完了状態
- セッション再開
- タイトル変更
- 削除
- Todo・差分への導線

### 9.6 設定

- プロバイダーとモデル
- 音声認識
- TTS
- ホームアシスト
- ウェイクワード
- セキュリティ
- ランタイム診断・更新
- Aboutとライセンス

## 10. ホームアシスト設計

### 10.1 システムアシスタント

`VoiceInteractionService`を実装し、Android設定の既定デジタルアシスタント候補として表示する。

起動時は軽量なアシスタント画面を表示し、次を使用する。

- 設定済みの実行先
- 設定済みのプロジェクト
- 設定済みのOpenCodeエージェント
- 設定済みのモデル
- 専用または継続セッション

### 10.2 ウェイクワード

- 明示的なユーザー操作で有効化する
- Foreground Serviceと常時通知を使用する
- 初期実装では既存OpenCodeAndroidのVosk方式を再利用する
- 初期ウェイクワード: `Open Code`
- カスタムウェイクワードはVoskの文法制約の範囲で設定する
- 画面消灯中の動作は端末メーカーの電池最適化に左右されるため、設定ガイドを表示する
- マイク使用中であることを常時明示する

### 10.3 音声フロー

```text
起動またはウェイクワード
→ SpeechRecognizerで音声認識
→ 選択セッションへprompt_async
→ SSEで応答完了を検出
→ 最終テキストをTextToSpeechで読み上げ
→ 継続会話待機または終了
```

## 11. プロバイダー・モデル設定

アプリはOpenCodeサーバーの`GET /provider`を使って、接続済みプロバイダーと利用可能モデルを表示する。

認証情報は実行先ごとに管理する。

- リモート実行: 認証情報はPC側OpenCodeに保存し、Androidへ複製しない
- ローカル実行: APIキー入力UIを提供し、Android Keystoreで暗号化した後、OpenCodeの`PUT /auth/:id`へ渡す
- OpenCode Zenも通常のOpenCodeプロバイダーとして扱う
- 無料モデルはサーバーが返すモデル情報を表示し、アプリへ固定リストとして焼き込まない

## 12. Androidローカルランタイム

OpenCode公式Linux ARM64バイナリをAndroidネイティブプロセスとして直接実行しない。

アプリ専用ストレージ内にLinuxユーザーランドを構築し、その中で公式OpenCodeを動かす。

```text
filesDir/runtime/
├─ rootfs/
├─ home/
├─ bin/
├─ logs/
└─ metadata.json
```

### 12.1 必須コンポーネント

- PRootまたは互換ユーザーランドランナー
- ARM64 Linux rootfs
- Bash
- Git
- CA証明書
- curl
- Node.jsまたはBun
- OpenCode公式配布物

### 12.2 更新方針

- rootfsとランナーはアプリ管理版として更新する
- OpenCodeはユーザーランド内部で公式インストーラーまたはパッケージを使って更新する
- 更新前にバージョンと空き容量を確認する
- 失敗時は直前バージョンへ戻す
- OpenCode更新はAndroidアプリ更新から独立させる

### 12.3 制限

- 第一対象ABIはarm64-v8a
- x86_64はエミュレーター検証用として後続対応
- Android端末内でのDocker・Android Emulator・iOSビルドは対象外
- ローカル実行は軽いリポジトリ操作、会話、調査、簡易テスト向けとする

## 13. セキュリティ

- 接続パスワード、ローカルAPIキーはAndroid Keystoreで暗号化する
- スクリーンショット禁止はシークレット入力画面だけに適用する
- HTTP接続時はLAN・localhost・Tailscale IP以外へ警告する
- リモートOpenCodeはサーバーパスワード設定を必須推奨とする
- 承認要求の既定は`once`
- 危険操作をアプリ側で無条件に自動承認しない
- ウェイクワードサービスは録音内容を保存しない
- ログへAPIキー、パスワード、Authorizationヘッダーを出力しない

## 14. データモデル

```kotlin
data class ConnectionProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val encryptedPassword: String?,
    val allowInsecureLan: Boolean,
    val lastConnectedAt: Instant?
)

data class AssistantProfile(
    val backendId: String,
    val projectPath: String?,
    val providerId: String?,
    val modelId: String?,
    val agentId: String?,
    val wakeWord: String,
    val continuousConversation: Boolean,
    val speakResponses: Boolean
)
```

接続先一覧と一般設定はDataStore、セッション内容はOpenCodeサーバーを正とする。Android側に会話全文を重複保存しない。

## 15. エラー処理

ユーザー向けエラーを次の分類に統一する。

- 接続不能
- 認証失敗
- OpenCode API非互換
- モデル・プロバイダー未設定
- 実行中断
- 権限待ち
- Androidマイク権限不足
- ランタイム未導入
- ランタイム破損
- 空き容量不足

再試行可能なエラーには再試行ボタンを表示し、技術詳細は展開式の診断情報へ分離する。

## 16. テスト戦略

### 16.1 JVM単体テスト

- URL検証
- Basic認証ヘッダー生成
- OpenCode JSONモデルのデコード
- SSEイベントパーサー
- 接続先選択
- アシスタントプロファイル選択
- エラー分類

### 16.2 MockWebServer統合テスト

- ヘルスチェック
- セッション一覧
- セッション作成
- 非同期メッセージ送信
- SSEイベント受信
- 承認応答
- 401・500・タイムアウト

### 16.3 Compose UIテスト

- 初回接続フロー
- ホーム状態表示
- チャット送信
- 承認カード
- 設定保存

### 16.4 実機テスト

- Android 16 Xiaomi端末
- 既定アシスタント設定
- 電源ボタン長押しまたはホームジェスチャー
- マイクForeground Service
- 画面消灯・電池最適化条件
- Mac mini上のOpenCodeへのLAN/Tailscale接続

## 17. 実装フェーズ

### Phase 1: Remote Core

- 新規Androidアプリ基盤
- ダークテーマ
- 接続先管理
- REST/SSEクライアント
- ホーム・チャット・セッション・設定
- OpenCodeリモート接続
- 単体・統合テスト

### Phase 2: Voice Assistant

- SpeechRecognizer
- TTS
- VoiceInteractionService
- Voskウェイクワード
- Foreground Service

### Phase 3: Local Runtime

- ランタイムドクター
- rootfsインストーラー
- OpenCode導入・起動・停止・更新
- ローカルバックエンド接続

### Phase 4: Developer Surfaces

- ファイル・検索
- Diff
- Todo
- 複数PC
- ハンドオフ

## 18. 第一実装で再利用する既存資産

既存`OpenCodeAndroid`から次を移植し、OpenCode固有コードは削除する。

- Jetpack Compose・Material 3基盤
- `VoiceInteractionService`
- Android SpeechRecognizer
- Android TextToSpeech
- Voskウェイクワード検知
- Foreground Service
- 日本語・英語リソース
- 暗号化設定保存の考え方

Webhookクライアント、OpenCode名称、OpenCode専用データモデルは再利用しない。

## 19. リポジトリ運用

- `main`: 安定版
- `feature/initial-mvp`: 第一完成版の実装ブランチ
- 設計変更は先に本設計書を更新する
- 機能実装はテストを先に追加する
- GitHub Actionsで`testDebugUnitTest`と`assembleDebug`を実行する
- リリースAPKはGitHub Releasesで配布する

## 20. 受け入れ基準

第一完成版は次の全項目を満たした場合に完了とする。

- Debug APKがビルドできる
- JVM単体テストがすべて成功する
- 接続先の追加・編集・削除ができる
- Mac mini上のOpenCodeヘルスチェックに成功する
- セッション一覧を表示できる
- 新規セッションへメッセージを送信できる
- SSE応答を表示できる
- 承認要求へ回答できる
- 音声入力とTTS読み上げが動く
- Androidの既定アシスタント候補として表示される
- ウェイクワードサービスを開始・停止できる
- シークレットがログやGitへ含まれない
- READMEに導入手順と非公式クライアント表記がある
