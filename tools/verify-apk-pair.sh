#!/usr/bin/env bash
set -euo pipefail
phone=app/build/outputs/apk/debug/whisperflow-debug.apk
watch=wear/build/outputs/apk/debug/whisperflow-wear-debug.apk
analyzer="$(command -v apkanalyzer || find "$ANDROID_HOME/cmdline-tools" -name apkanalyzer -type f | sort -V | tail -1)"
test -n "$analyzer"
signer="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -1)"
test -n "$signer"
phone_id="$($analyzer manifest application-id "$phone")"
watch_id="$($analyzer manifest application-id "$watch")"
test "$phone_id" = com.pimax.whisperflow
test "$phone_id" = "$watch_id"
phone_cert="$($signer verify --print-certs "$phone" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
watch_cert="$($signer verify --print-certs "$watch" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
test -n "$phone_cert"
test "$phone_cert" = "$watch_cert"
$analyzer manifest print "$watch" > wear/build/outputs/apk/debug/wear-manifest.xml
grep -q 'com.pimax.whisperflow.wear.WearMainActivity' wear/build/outputs/apk/debug/wear-manifest.xml
grep -q 'android.intent.category.LAUNCHER' wear/build/outputs/apk/debug/wear-manifest.xml
{
  printf 'Commit: %s\nApplication ID (both): %s\nSigning SHA256 (both): %s\n' "${GITHUB_SHA:-local}" "$phone_id" "$phone_cert"
  sha256sum "$phone" "$watch"
} | tee app/build/outputs/apk/debug/build-identity.txt
