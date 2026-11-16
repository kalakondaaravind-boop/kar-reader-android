#!/usr/bin/env bash
# Downloads the on-device speech engine (sherpa-onnx AAR) and the models bundled in the APK:
#   - Whisper base, multilingual (int8)               → app/src/main/assets/models/whisper/
#   - pyannote segmentation 3.0 (who speaks when)     → app/src/main/assets/models/segmentation.onnx
#   - 3D-Speaker ERes2Net base (voice embeddings)     → app/src/main/assets/models/embedding.onnx
# Everything comes from the sherpa-onnx GitHub releases and is checked against scripts/speech-models.sha256.
# The app itself never downloads anything.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(sed -n 's/^sherpaOnnx = "\(.*\)"/\1/p' gradle/libs.versions.toml)
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
CACHE=.speech-cache
ASSETS=app/src/main/assets/models
SUMS=scripts/speech-models.sha256
mkdir -p "$CACHE" app/libs "$ASSETS/whisper"

fetch() { # url file
  if [ ! -s "$CACHE/$2" ]; then
    curl -fsSL --retry 4 --retry-delay 5 -o "$CACHE/$2.part" "$1"
    mv "$CACHE/$2.part" "$CACHE/$2"
  fi
  local sum; sum=$(sha256sum "$CACHE/$2" | cut -d' ' -f1)
  local want; want=$(awk -v f="$2" '$2==f {print $1}' "$SUMS" 2>/dev/null || true)
  if [ -n "$want" ] && [ "$want" != "$sum" ]; then
    echo "::error::Checksum mismatch for $2 (expected $want, got $sum)"; rm -f "$CACHE/$2"; exit 1
  fi
  [ -z "$want" ] && echo "::notice title=unpinned checksum::$sum  $2"
  return 0
}

fetch "$BASE/v$VERSION/sherpa-onnx-$VERSION.aar" "sherpa-onnx-$VERSION.aar"
fetch "$BASE/asr-models/sherpa-onnx-whisper-base.tar.bz2" whisper-base.tar.bz2
fetch "$BASE/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2" segmentation.tar.bz2
fetch "$BASE/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx" embedding.onnx

cp "$CACHE/sherpa-onnx-$VERSION.aar" app/libs/

tmp=$(mktemp -d)
tar -xjf "$CACHE/whisper-base.tar.bz2" -C "$tmp" --wildcards '*/base-encoder.int8.onnx' '*/base-decoder.int8.onnx' '*/base-tokens.txt'
cp "$tmp"/sherpa-onnx-whisper-base/base-encoder.int8.onnx "$ASSETS/whisper/encoder.int8.onnx"
cp "$tmp"/sherpa-onnx-whisper-base/base-decoder.int8.onnx "$ASSETS/whisper/decoder.int8.onnx"
cp "$tmp"/sherpa-onnx-whisper-base/base-tokens.txt "$ASSETS/whisper/tokens.txt"
tar -xjf "$CACHE/segmentation.tar.bz2" -C "$tmp" --wildcards '*/model.onnx'
cp "$tmp"/sherpa-onnx-pyannote-segmentation-3-0/model.onnx "$ASSETS/segmentation.onnx"
cp "$CACHE/embedding.onnx" "$ASSETS/embedding.onnx"
rm -rf "$tmp"

# Licences travel with the models.
cat > "$ASSETS/NOTICE.txt" <<'NOTICE'
Speech components bundled with KAR READER (all run on the device):
- sherpa-onnx (k2-fsa), Apache License 2.0 — https://github.com/k2-fsa/sherpa-onnx
- ONNX Runtime (Microsoft), MIT License
- Whisper base (OpenAI), MIT License — exported to ONNX by the sherpa-onnx project
- pyannote segmentation 3.0 (pyannote.audio), MIT License
- 3D-Speaker ERes2Net (Alibaba DAMO), Apache License 2.0
NOTICE

if [ "${1:-}" = "--test-audio" ]; then
  # Two English speakers, from the sherpa-onnx speaker-segmentation test set. Used only by the emulator test.
  fetch "$BASE/speaker-segmentation-models/1-two-speakers-en.wav" two-speakers-en.wav
  mkdir -p app/build/test-audio && cp "$CACHE/two-speakers-en.wav" app/build/test-audio/two-speakers-en.wav
fi

echo "Speech engine $VERSION and models:"; ls -l app/libs "$ASSETS" "$ASSETS/whisper"
