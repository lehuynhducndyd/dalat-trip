#!/usr/bin/env bash
# Vercel's build image has no supported Gradle/JDK toolchain, so the Kotlin/Wasm
# bundle is built here and uploaded as static files.
set -euo pipefail

DIST="webApp/build/dist/wasmJs/productionExecutable"

./gradlew :webApp:wasmJsBrowserDistribution
cp vercel.json "$DIST/vercel.json"
cd "$DIST"
vercel deploy --prod
