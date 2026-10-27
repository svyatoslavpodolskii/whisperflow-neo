param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../logs/watch-diagnostics')
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
& $Adb devices -l | Out-File (Join-Path $OutputDirectory 'devices.txt') -Encoding utf8
& $Adb -s $Serial shell getprop | Out-File (Join-Path $OutputDirectory 'properties.txt') -Encoding utf8
& $Adb -s $Serial shell pm list packages -f | Out-File (Join-Path $OutputDirectory 'packages.txt') -Encoding utf8
foreach ($package in @('com.pimax.whisperflow.wear', 'com.pimax.whisperflow')) {
    & $Adb -s $Serial shell dumpsys package $package | Out-File (Join-Path $OutputDirectory "$package-package.txt") -Encoding utf8
    & $Adb -s $Serial shell cmd package resolve-activity --brief $package | Out-File (Join-Path $OutputDirectory "$package-launcher.txt") -Encoding utf8
    & $Adb -s $Serial shell appops get $package RECORD_AUDIO | Out-File (Join-Path $OutputDirectory "$package-microphone.txt") -Encoding utf8
}
& $Adb -s $Serial shell dumpsys activity activities | Out-File (Join-Path $OutputDirectory 'activities.txt') -Encoding utf8
& $Adb -s $Serial shell wm size | Out-File (Join-Path $OutputDirectory 'display-size.txt') -Encoding utf8
& $Adb -s $Serial shell wm density | Out-File (Join-Path $OutputDirectory 'display-density.txt') -Encoding utf8
# Preserve the existing log buffer; do not clear evidence before collecting it.
& $Adb -s $Serial logcat -d -v threadtime | Out-File (Join-Path $OutputDirectory 'logcat.txt') -Encoding utf8
& $Adb -s $Serial shell uiautomator dump /sdcard/whisperflow-window.xml
& $Adb -s $Serial pull /sdcard/whisperflow-window.xml (Join-Path $OutputDirectory 'window.xml')
Write-Output "Diagnostics saved to $OutputDirectory"
