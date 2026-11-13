param(
    [string]$Sdk = (Join-Path $env:LOCALAPPDATA 'RPlayerEmbySdk')
)

$ErrorActionPreference = 'Stop'
$buildTools = Join-Path $Sdk 'build-tools-35.0.0/android-15'
$androidJar = Join-Path $Sdk 'platforms-android-35/android-35/android.jar'
$work = Join-Path $Sdk 'bridge-build'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$resources = Join-Path $work 'resources.zip'
$unsigned = Join-Path $work 'unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
$signed = Join-Path $work 'signed.apk'
$keystore = Join-Path $Sdk 'rplayer-emby.p12'
$output = Join-Path (Split-Path -Parent $PSScriptRoot) 'rPlayer-Emby.apk'

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable exited with code $LASTEXITCODE"
    }
}

New-Item -ItemType Directory -Force $classes, $dex | Out-Null
foreach ($file in @($resources, $unsigned, $aligned, $signed)) {
    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
}

Invoke-BuildTool "$buildTools/aapt2.exe" @(
    'compile', '--dir', "$PSScriptRoot/res", '-o', $resources
)
Invoke-BuildTool "$buildTools/aapt2.exe" @(
    'link', '-o', $unsigned, '-I', $androidJar,
    '--manifest', "$PSScriptRoot/AndroidManifest.xml", $resources
)
Invoke-BuildTool 'javac' @(
    '--release', '8', '-encoding', 'UTF-8', '-classpath', $androidJar,
    '-d', $classes, "$PSScriptRoot/src/PlaybackActivity.java", "$PSScriptRoot/src/EmbyStream.java"
)
Invoke-BuildTool "$buildTools/d8.bat" @(
    '--min-api', '27', '--lib', $androidJar, '--output', $dex,
    "$classes/com/rplayer/embybridge/PlaybackActivity.class",
    "$classes/com/rplayer/embybridge/EmbyStream.class"
)
Invoke-BuildTool 'jar' @('--update', '--file', $unsigned, '-C', $dex, 'classes.dex')
Invoke-BuildTool "$buildTools/zipalign.exe" @('-f', '4', $unsigned, $aligned)

if (!(Test-Path -LiteralPath $keystore)) {
    Invoke-BuildTool 'keytool' @(
        '-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
        '-storepass', 'android', '-alias', 'rplayer-emby', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000', '-dname', 'CN=rPlayer Emby Local Build'
    )
}

Invoke-BuildTool "$buildTools/apksigner.bat" @(
    'sign', '--ks', $keystore, '--ks-key-alias', 'rplayer-emby',
    '--ks-pass', 'pass:android', '--out', $signed, $aligned
)
Invoke-BuildTool "$buildTools/apksigner.bat" @('verify', '--verbose', $signed)
Copy-Item -LiteralPath $signed -Destination $output -Force
Get-Item -LiteralPath $output
