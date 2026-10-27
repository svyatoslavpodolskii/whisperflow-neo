#!/usr/bin/env bash
set -euo pipefail
phone=app/build/outputs/apk/debug/whisperflow-debug.apk
watch=wear/build/outputs/apk/debug/whisperflow-wear-debug.apk
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$android_sdk"
signer="$(find "$android_sdk/build-tools" -name apksigner -type f | sort -V | tail -1)"
test -n "$signer"
aapt="$(dirname "$signer")/aapt"
test -x "$aapt"
phone_badging="$($aapt dump badging "$phone")"
watch_badging="$($aapt dump badging "$watch")"
phone_id="$(printf '%s\n' "$phone_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
watch_id="$(printf '%s\n' "$watch_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
test "$phone_id" = com.pimax.whisperflow
test "$phone_id" = "$watch_id"
phone_cert="$($signer verify --print-certs "$phone" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
watch_cert="$($signer verify --print-certs "$watch" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
test -n "$phone_cert"
test "$phone_cert" = "$watch_cert"
$aapt dump xmltree "$watch" AndroidManifest.xml > wear/build/outputs/apk/debug/wear-manifest.txt
printf '%s\n' "$watch_badging" | grep '^launchable-activity:' | grep -q "name='com.pimax.whisperflow.wear.WearMainActivity'"
{
  printf 'Commit: %s\nApplication ID (both): %s\nSigning SHA256 (both): %s\n' "${GITHUB_SHA:-local}" "$phone_id" "$phone_cert"
  sha256sum "$phone" "$watch"
} | tee app/build/outputs/apk/debug/build-identity.txt
