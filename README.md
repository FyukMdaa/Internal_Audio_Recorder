# Internal Audio Recorder(内部音声レコーダー)

Android 10(minSdk 29)〜 **Android 17(API 37)** で端末の内部音声(再生中の音)をステレオ録音し、波形を見ながら不要箇所をカットして WAV / M4A / FLAC / Opus で保存できるアプリです。GitHub Actions でそのままビルドできます。

[English](#english) | [日本語](#日本語)

---

## 日本語

### 機能

- **QS タイルで録音開始**: 画面録画と同じスタイル。クイック設定に「内部音声録音」タイルを追加 → タップ → 同意ダイアログ → 録音開始。タイルをもう一度タップすると停止します。
- **ステレオ録音**: `AudioPlaybackCaptureConfiguration` + `AudioRecord` により 48 kHz / 16 bit / ステレオで PCM を取得し、WAV に書き出します(録音中は通知に経過時間と 一時停止/停止 ボタンを表示)。
- **波形エディタ**: 録音後に波形(10 ms 窓のピーク値、キャッシュ対応)を表示。タップで再生位置移動、ロングプレス+ドラッグで範囲選択、選択端のドラッグで調整、ミニマップで全体ビューの移動、×1/×4/×16/×64 ズーム。
- **カット編集**: 「選択範囲をカット」「選択部分だけ残す」「すべて戻す」+ **Undo/Redo**(50 段階)。元ファイルは書き換えず、保持区間リストとして編集を管理します。
- **無音カットの提案**: 20 ms 窓の RMS を dBFS に換算し、しきい値(既定 −45 dB)・最短無音(既定 0.8 秒)を満たす無音区間を候補として一覧表示。チェックを付けた区間をまとめてカットできます。しきい値/最短無音/余白(パディング)は設定画面で調整可能。
- **再生プレビュー**: カット後の編集結果を通して再生(AudioTrack ストリーミング)。波形上に再生ヘッドを表示。
- **4 形式で保存**: WAV / M4A(AAC-LC + MP4) / FLAC(PLATFORM エンコーダ + STREAMINFO コンテナ) / Opus(OGG)。OS 標準の `MediaCodec` / `MediaMuxer` のみ使用。ファイル名・拡張子は保存ダイアログ(SAF)で自由に変更できます。
- **日英両対応**: 端末の言語設定に追従。

### 制限(プラットフォーム仕様)

- 相手アプリがキャプチャ禁止(`allowAudioPlaybackCapture=false`)の場合や、DRM 保護された再生(一部のストリーミング系)は録音できません。
- 対象は `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN` で再生されている音です(通話音声などは対象外)。
- MediaProjection の同意は録音ごとに必要です(画面録画と同様)。

### ビルド(GitHub Actions)

1. このリポジトリを GitHub に push します。
2. `Build APK` ワークフローが push / PR ごとに **デバッグ APK** をビルドし、Artifacts に `app-debug` としてアップロードします。
3. リリース版(署名付き)はタグで出せます:

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

   `Release APK` ワークフローが走り、GitHub Release に APK を添付します。

#### 署名の設定(リリース時)

リポジトリの Settings → Secrets and variables → Actions に次の 4 つを登録すると、タグビルドが自動署名されます。未登録の場合は **未署名 APK** が Release に添付されます(後から `apksigner` で署名可能)。

| Secret 名 | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | キーストアの base64 文字列 |
| `RELEASE_KEYSTORE_PASSWORD` | キーストアのパスワード |
| `RELEASE_KEY_ALIAS` | エイリアス名 |
| `RELEASE_KEY_PASSWORD` | 鍵のパスワード |

キーストアの作成と base64 化:

```bash
keytool -genkeypair -v -keystore release.keystore -alias inrec \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # 出力を RELEASE_KEYSTORE_BASE64 に貼り付け
```

### ローカルビルド

要件: JDK 17、Android SDK(platform 37)。

```bash
./gradlew assembleDebug     # デバッグ APK: app/build/outputs/apk/debug/
./gradlew assembleRelease   # リリース APK(未署名)
```

### 使い方

1. アプリを起動 → 「録音を開始」をタップ(またはクイック設定にタイルを追加)。
2. 通知に「音声のキャプチャ」同意ダイアログが出るので許可 → 録音開始。通知から 一時停止/停止 ができます。
3. 停止すると一覧に録音が追加されます。タップでエディタを開く。
4. 「無音を検出」→ 提案を確認して「カットを適用」、または波形を直接範囲選択してカット。
5. 「保存…」→ 形式(WAV/M4A/FLAC/Opus)とファイル名を選んで保存。

### 技術スタック

- Kotlin 2.4 / Jetpack Compose(BOM 2026.09.00, Material 3)/ AGP 9.3 / Gradle 9.9.1
- compileSdk 37(targetSdk 37, Android 17)/ minSdk 29
- 録音: `MediaProjection` + `AudioPlaybackCaptureConfiguration` + `AudioRecord`(FGS type `mediaProjection`)
- 編集: 自前の PCM 解析・区間演算・波形キャッシュ(外部ライブラリなし)
- エンコード: `MediaCodec` / `MediaMuxer` のみ(FLAC のみ STREAMINFO メタデータブロックを自前でラップ)

---

## English

### Features

- **QS-tile recording, screen-recorder style**: add the “Internal audio” tile, tap → consent dialog → recording starts; tap again to stop.
- **Stereo capture**: `AudioPlaybackCaptureConfiguration` + `AudioRecord` at 48 kHz / 16-bit stereo, streamed to a WAV file. Ongoing notification shows elapsed time with Pause/Stop actions.
- **Waveform editor**: 10 ms peak cache, tap to seek, long-press-drag to select, edge-drag to adjust, minimap for the full view, ×1/×4/×16/×64 zoom.
- **Cut editing**: cut selection, keep-only-selection, restore-all, undo/redo (50 steps). The source file is never modified — edits are a kept-ranges list.
- **Silence cut suggestions**: 20 ms RMS windows in dBFS; ranges below the threshold (default −45 dB) longer than the minimum (default 0.8 s) are proposed, with configurable padding.
- **Preview playback** of the edited result via AudioTrack streaming with a playhead on the waveform.
- **Export to WAV / M4A(AAC) / FLAC / Opus** using only platform `MediaCodec`/`MediaMuxer`; filename/extension freely editable in the SAF save dialog.
- **English & Japanese** UI.

### Platform limitations

- Apps that opt out of playback capture, and DRM-protected streams, cannot be recorded.
- Captures `USAGE_MEDIA` / `USAGE_GAME` / `USAGE_UNKNOWN` playback.
- MediaProjection consent is required per recording (like screen recording).

### Build with GitHub Actions

- Push → `Build APK` workflow uploads a debug APK to Artifacts.
- Push a `v*` tag → `Release APK` workflow attaches a release APK to a GitHub Release.
  Set the 4 secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) for automatic signing; without them an unsigned APK is attached.

### Local build

Requires JDK 17 and the Android SDK (platform 37): `./gradlew assembleDebug`.

### Project layout

```
.github/workflows/        build.yml (debug APK), release.yml (tagged, signed)
app/src/main/java/com/inrec/recorder/
  capture/                CaptureService, RecordTileService, RecorderState
  audio/                  WavWriter, WaveformPeaks, SilenceDetector, Segments, PcmPlayer
  export/                 AudioExporter (WAV/M4A/FLAC/Opus)
  data/                   RecordingsRepository, AppSettings
  ui/                     Compose screens (list / editor / settings), theme
gradle/libs.versions.toml version catalog
```

## License

MIT — see [LICENSE](LICENSE).
