#!/usr/bin/env bash
# Fetches the large speech binaries that are deliberately NOT in git:
#   - sherpa-onnx Android AAR            -> app/libs/
#   - streaming zipformer EN int8 model  -> app/src/main/assets/asr/
# Run once after cloning (or after bumping SHERPA_VERSION).
set -euo pipefail
cd "$(dirname "$0")/.."

SHERPA_VERSION="1.13.8"
MODEL="sherpa-onnx-streaming-zipformer-en-2023-06-26"
WHISPER="sherpa-onnx-whisper-tiny.en"

mkdir -p app/libs app/src/main/assets/asr
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if [ ! -f "app/libs/sherpa-onnx-${SHERPA_VERSION}.aar" ]; then
  echo "Fetching sherpa-onnx ${SHERPA_VERSION} AAR…"
  curl -fL -o "app/libs/sherpa-onnx-${SHERPA_VERSION}.aar" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
fi

if [ ! -f app/src/main/assets/asr/encoder.int8.onnx ]; then
  echo "Fetching streaming zipformer model (~70 MB)…"
  curl -fL -o "$tmp/model.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${MODEL}.tar.bz2"
  tar xjf "$tmp/model.tar.bz2" -C "$tmp"
  cp "$tmp/$MODEL/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" app/src/main/assets/asr/encoder.int8.onnx
  cp "$tmp/$MODEL/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" app/src/main/assets/asr/decoder.int8.onnx
  cp "$tmp/$MODEL/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"  app/src/main/assets/asr/joiner.int8.onnx
  cp "$tmp/$MODEL/tokens.txt"                                          app/src/main/assets/asr/tokens.txt
fi

if [ ! -f app/src/main/assets/asr/whisper-encoder.int8.onnx ]; then
  echo "Fetching whisper tiny.en model (~100 MB)…"
  curl -fL -o "$tmp/whisper.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${WHISPER}.tar.bz2"
  tar xjf "$tmp/whisper.tar.bz2" -C "$tmp"
  cp "$tmp/$WHISPER/tiny.en-encoder.int8.onnx" app/src/main/assets/asr/whisper-encoder.int8.onnx
  cp "$tmp/$WHISPER/tiny.en-decoder.int8.onnx" app/src/main/assets/asr/whisper-decoder.int8.onnx
  cp "$tmp/$WHISPER/tiny.en-tokens.txt"        app/src/main/assets/asr/whisper-tokens.txt
fi

echo "Speech assets ready:"
ls -la app/libs app/src/main/assets/asr
