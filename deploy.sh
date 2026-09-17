#!/usr/bin/env bash
# Vercel's build image has no supported Gradle/JDK toolchain, so the Kotlin/Wasm
# bundle is built here and uploaded as static files.
set -euo pipefail

DIST="webApp/build/dist/wasmJs/productionExecutable"
# Vercel names the project after the deployed directory, so stage the bundle
# under a directory named like the project rather than "productionExecutable".
STAGE="build/vercel/dalat-trip"

./gradlew :webApp:wasmJsBrowserDistribution

rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -r "$DIST"/. "$STAGE"/

# No vercel.json: Vercel already serves .wasm as application/wasm, and the app
# is a single page that never changes the URL, so an SPA rewrite has nothing to
# catch. A config file that does nothing is worse than none.
cd "$STAGE"
npx -y vercel deploy --prod --yes
