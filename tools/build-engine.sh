#!/bin/sh
# Bundles engine/app.ts (with the unmodified rekordbox-usb-repair engine in
# engine/upstream) into app/src/main/assets/engine.js, a script that defines
# the global Engine with check() and repair(). The bundle is committed, so
# building the app needs no Node. Run again after updating engine/. fs is only
# used by the file helpers of pdbEditor.ts, which the app never calls, and
# RB_REVERT_UTF16_ALIGN is a debug switch of pdbEditor.ts that stays off.
set -e
cd "$(dirname "$0")/.."
npm install --no-save --no-package-lock buffer@6.0.3 esbuild@0.24.2 >/dev/null
npx esbuild engine/app.ts \
    --bundle \
    --format=iife \
    --global-name=Engine \
    --platform=browser \
    --target=es2019 \
    --inject:tools/buffer-shim.js \
    --external:fs \
    --define:process.env.RB_REVERT_UTF16_ALIGN=undefined \
    --minify \
    --legal-comments=eof \
    --outfile=app/src/main/assets/engine.js
echo "app/src/main/assets/engine.js: $(wc -c < app/src/main/assets/engine.js) bytes"
