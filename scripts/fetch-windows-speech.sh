#!/usr/bin/env bash
# Downloads the speech engine (sherpa-onnx Java API + Windows native libraries) and the same models the Android
# app bundles, into windows/libs and windows/resources/common/models. Run by CI before building the installer.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^sherpaOnnx = "\(.*\)"/\1/p' gradle/libs.versions.toml)
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
CACHE=.speech-cache
SUMS=scripts/speech-models.sha256
OUT=windows/resources/common/models
mkdir -p "$CACHE" windows/libs "$OUT/whisper" "$OUT/omnilingual"

fetch() { # url file
  if [ ! -s "$CACHE/$2" ]; then
    curl -fsSL --retry 4 --retry-delay 5 -o "$CACHE/$2.part" "$1"
    mv "$CACHE/$2.part" "$CACHE/$2"
  fi
  local sum want
  sum=$(sha256sum "$CACHE/$2" | cut -d' ' -f1)
  want=$(awk -v f="$2" '$2==f {print $1}' "$SUMS" 2>/dev/null || true)
  if [ -n "$want" ] && [ "$want" != "$sum" ]; then
    echo "::error::Checksum mismatch for $2 (expected $want, got $sum)"; rm -f "$CACHE/$2"; exit 1
  fi
  [ -z "$want" ] && echo "::notice title=unpinned checksum::$sum  $2"
  return 0
}

fetch "$BASE/v$VERSION/sherpa-onnx-jvm-$VERSION.jar" "sherpa-onnx-jvm-$VERSION.jar"
fetch "$BASE/v$VERSION/sherpa-onnx-native-lib-win-x64-$VERSION.jar" "sherpa-onnx-native-lib-win-x64-$VERSION.jar"
cp "$CACHE/sherpa-onnx-jvm-$VERSION.jar" "$CACHE/sherpa-onnx-native-lib-win-x64-$VERSION.jar" windows/libs/

fetch "$BASE/asr-models/sherpa-onnx-whisper-base.tar.bz2" whisper-base.tar.bz2
fetch "$BASE/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" segmentation.tar.bz2
fetch "$BASE/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" embedding.onnx
fetch "$BASE/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2" omnilingual.tar.bz2

tmp=$(mktemp -d)
tar -xjf "$CACHE/whisper-base.tar.bz2" -C "$tmp" --wildcards '*/base-encoder.int8.onnx' '*/base-decoder.int8.onnx' '*/base-tokens.txt'
cp "$tmp"/sherpa-onnx-whisper-base/base-encoder.int8.onnx "$OUT/whisper/encoder.int8.onnx"
cp "$tmp"/sherpa-onnx-whisper-base/base-decoder.int8.onnx "$OUT/whisper/decoder.int8.onnx"
cp "$tmp"/sherpa-onnx-whisper-base/base-tokens.txt "$OUT/whisper/tokens.txt"
tar -xjf "$CACHE/segmentation.tar.bz2" -C "$tmp" --wildcards '*/model.onnx'
cp "$tmp"/sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$OUT/segmentation.onnx"
cp "$CACHE/embedding.onnx" "$OUT/embedding.onnx"
tar -xjf "$CACHE/omnilingual.tar.bz2" -C "$tmp" --wildcards '*/model.int8.onnx' '*/tokens.txt'
cp "$tmp"/sherpa-onnx-omnilingual-asr-*/model.int8.onnx "$OUT/omnilingual/model.int8.onnx"
cp "$tmp"/sherpa-onnx-omnilingual-asr-*/tokens.txt "$OUT/omnilingual/tokens.txt"
rm -rf "$tmp"
if [ "${1:-}" = "--test-audio" ]; then
  LID_WAVS=https://huggingface.co/spaces/k2-fsa/spoken-language-identification/resolve/6f200a5f9314348e884a5d45b4c3f6d5ccccc0f6/test_wavs
  fetch "$BASE/speaker-segmentation-models/1-two-speakers-en.wav" two-speakers-en.wav
  fetch "$LID_WAVS/hi-hindi.wav" hi-hindi.wav
  mkdir -p windows/build/test-audio
  cp "$CACHE/two-speakers-en.wav" "$CACHE/hi-hindi.wav" windows/build/test-audio/
fi
ls -lh "$OUT" "$OUT/whisper" "$OUT/omnilingual" windows/libs
