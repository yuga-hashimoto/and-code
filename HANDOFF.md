# 引き継ぎ: 音声設定まわりの作業

このファイルは作業引き継ぎ専用です。**PRを出す前に削除してください**（`git rm HANDOFF.md`）。

作業ブランチ: `claude/wakeword-button-toggle-issue-345b31`

## 完了済み（このブランチの直近2コミット）

1. `fix: show the assistant agent's own models, not the chat agent's`
   音声設定の「アシスタント起動先」が、選んだAgentではなくチャット側Agentのモデル一覧を出す不具合の修正。
   `AssistantTargetResolver.loadAssistantProviders()` を追加し、`listProviders()` の前に `connect()` するようにした。実機検証済み。

2. `feat: persist speaking rate and pitch for text-to-speech`
   `TtsTuning` ヘルパーと `SecureSettingsRepository.ttsSpeechRate` / `ttsPitch` を追加。
   **まだUIに繋がっていない**（値は常にデフォルト1.0のまま）。

## 残作業

### A. 読み上げ速度・ピッチをUIに繋ぐ（コミット2の続き）

- `AppPreferences`（`data/settings/AppPreferencesRepository.kt`）に `ttsSpeechRate` / `ttsPitch` を追加。初期化とsetterも
- `SettingsUiState` と `SettingsViewModel` にsetterを追加
- `VoiceSettingsScreen` にスライダー2つ。範囲は `TtsTuning.MIN_RATE..MAX_RATE`、`MIN_PITCH..MAX_PITCH`
- `SettingsNavGraph` で配線
- `AndCodeVoiceSession.ttsConfiguration()` にある4箇所の `TTSProviderConfig.Android(settings.ttsAndroidEngine)` を
  すべて `TtsTuning.androidConfig(settings.ttsAndroidEngine, settings.ttsSpeechRate, settings.ttsPitch)` に置換

### B. テスト再生ボタン

音声設定画面でサンプル文をその場で読み上げ、速度・ピッチを耳で確認できるようにする。

### C. バージイン（読み上げ中の割り込み）

読み上げ中にウェイクワードで割り込んで停止できるようにする。ON/OFF設定つき、デフォルトON。

### D. ウェイクワードを openWakeWord から Vosk に置き換え（本丸）

**なぜ**: 現状は `feature/wakeword/OpenWakeWordDetector.kt` がフレーズごとの学習済み `.tflite` を使う方式で、
`SettingsViewModel.WAKE_WORD_MODELS` が `hey_mycroft` の1件のみ。任意の語は原理的に不可能。
さらに `app/src/main/assets/wakeword/` の3ファイルは **CC BY-NC-SA 4.0（非商用）** で、
`assets/legal/oss_licenses.md` に `REQUIRES_LICENSE_REVIEW` と明記されている。

**方針**（ユーザー承認済み）:
- 依存: `com.alphacephei:vosk-android:0.3.75`（Apache-2.0）
- モデルは **APKに同梱しない**。ウェイクワードを初めてONにしたときにダウンロードして展開する
  （同梱すると37MBのAPKが倍増するため。ユーザーはダウンロード方式を明示的に選択した）
- ダウンロード時に **英語 / 日本語 を選べる**こと。進捗表示・キャンセル・失敗時のハンドリングを入れる
- Voskは文法制約付きキーワードスポッティングが使える。16kHz PCM を `recognizer.acceptWaveForm()` に流す
- `WakeWordService` の検知部分を差し替える。録音・フォアグラウンドサービス周りの既存構造は維持
- 動いたら `OpenWakeWordDetector` と `assets/wakeword/` の3ファイルを削除

**設定UI**: 固定ドロップダウンをやめ、自由入力のウェイクワード欄＋感度スライダー＋モデル言語選択＋DL状態表示にする。

参考実装: `github.com/yuga-hashimoto/openclaw-assistant`（ユーザー本人のリポジトリ）。
`data/SettingsRepository.kt`（`wakeWordSensitivity`, `getWakeWords()`, `ttsBargeInEnabled` など）、
`SettingsActivity.kt`（スライダーUI）、`service/HotwordService.kt` が参考になる。

### E. ライセンス文書の更新

Voskに移行して `.tflite` を削除したら、`app/src/main/assets/legal/oss_licenses.md` と
`THIRD_PARTY_NOTICES.md` から openWakeWord の CC BY-NC-SA 4.0 / `REQUIRES_LICENSE_REVIEW` の節を削除し、
Vosk（Apache-2.0）とダウンロードするモデルについて記載する。これはライセンス上の明確な改善なのでPR本文にも書くこと。

## 進め方の約束

- **テストを先に書く**。このリポジトリはTDDで進めている。`AssistantTargetResolverTest` / `TtsTuningTest` が直近の例
- コミット前に必ず全部通す:
  `./gradlew :app:testDebugUnitTest spotlessCheck detekt`
- ローカルでは `JAVA_HOME` にAndroid StudioのJBRを指定していた。クラウド環境では環境に合わせること
- `local.properties` はコミットしない

## 完了後

1. PR を作成（base: `main`）。**`HANDOFF.md` は削除してから**
2. PR本文には、実機検証ができていない旨を明記すること（クラウド環境には端末がないため）。
   特にVoskのモデルDLとウェイクワード検知は実機確認が必要な部分だと書く
3. CI の完走を待つ。**通ったらマージ**（ユーザーが明示的に承認済み）
4. マージ後、リリースタグ `v1.2.0` を切る（ユーザー指定）
5. CI が落ちたらマージせず、原因と状態を報告して止まること
