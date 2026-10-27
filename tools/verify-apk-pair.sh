#!/usr/bin/env bash
set -euo pipefail
phone=app/build/outputs/apk/release/whisperflow-release.apk
watch=wear/build/outputs/apk/release/whisperflow-wear-release.apk
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$android_sdk"
# Match AGP's build tools, rather than whichever preview SDK happens to be newest
# on a hosted runner (newer tools may need a newer JDK or omit legacy aapt).
signer="$android_sdk/build-tools/35.0.0/apksigner"
aapt="$android_sdk/build-tools/35.0.0/aapt"
test -x "$signer"
test -x "$aapt"
phase="${1:-all}"
if [ "$phase" = all ] || [ "$phase" = identity ]; then
phone_badging="$($aapt dump badging "$phone")"
watch_badging="$($aapt dump badging "$watch")"
phone_id="$(printf '%s\n' "$phone_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
watch_id="$(printf '%s\n' "$watch_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
test "$phone_id" = com.pimax.whisperflow
test "$phone_id" = "$watch_id"
fi
if [ "$phase" = all ] || [ "$phase" = signing ]; then
phone_cert="$($signer verify --print-certs "$phone" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
watch_cert="$($signer verify --print-certs "$watch" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
test -n "$phone_cert"
test "$phone_cert" = "$watch_cert"
test "$phone_cert" = "$(tr -d '\r\n ' < tools/development-signing.sha256)"
fi
if [ "$phase" = all ] || [ "$phase" = launcher ]; then
watch_badging="$($aapt dump badging "$watch")"
$aapt dump xmltree "$watch" AndroidManifest.xml > wear/build/outputs/apk/release/wear-manifest.txt
printf '%s\n' "$watch_badging" | grep '^launchable-activity:' | grep -q "name='com.pimax.whisperflow.wear.WearMainActivity'"
fi
if [ "$phase" != all ]; then exit 0; fi
{
  printf 'Commit: %s\nApplication ID (both): %s\nSigning SHA256 (both): %s\n' "${GITHUB_SHA:-local}" "$phone_id" "$phone_cert"
  sha256sum "$phone" "$watch"
} | tee app/build/outputs/apk/release/build-identity.txt
