param(
    [Parameter(Mandatory = $true)][string]$NdkPath,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
function Invoke-Adb {
    & $Adb -s $Serial @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
}
$abi = (Invoke-Adb shell getprop ro.product.cpu.abi).Trim()
if ([int](Invoke-Adb shell getprop ro.build.version.sdk) -lt 28) {
    throw 'The posix_spawn test helper requires Android API 28 or newer (PRoot itself targets API 24).'
}
$target = switch ($abi) {
    'arm64-v8a' { 'aarch64-linux-android28' }
    'x86_64' { 'x86_64-linux-android28' }
    default { throw "Unsupported ABI: $abi" }
}
$source = Split-Path $PSScriptRoot -Parent
$build = Join-Path $source "build/android-$abi"
$helpers = Join-Path $build 'runtime-tests'
New-Item -ItemType Directory -Force $helpers | Out-Null
$compiler = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
foreach ($name in @('puts_proc_self_exe', 'execveat', 'open_nofollow', 'test-fork')) {
    $inputFile = if ($name -eq 'test-fork') { "$PSScriptRoot/$name.c" } else { "$source/tests/$name.c" }
    & $compiler "--target=$target" -O2 -fPIE -pie $inputFile -o "$helpers/$name"
    if ($LASTEXITCODE -ne 0) { throw "Compile failed: $name" }
}
$remote = '/data/local/tmp/lyra-proot-' + [Guid]::NewGuid().ToString('N')
Invoke-Adb shell mkdir -p $remote
foreach ($file in @("$build/dist/libproot_exec.so", "$build/dist/libproot_loader.so",
    "$helpers/puts_proc_self_exe", "$helpers/execveat", "$helpers/open_nofollow", "$helpers/test-fork")) {
    Invoke-Adb push $file "$remote/"
}
# Git on Windows may check shell files out as CRLF.
$script = [IO.File]::ReadAllText("$PSScriptRoot/test-runtime.sh").Replace("`r`n", "`n")
[IO.File]::WriteAllText("$helpers/test-runtime.sh", $script, [Text.UTF8Encoding]::new($false))
Invoke-Adb push "$helpers/test-runtime.sh" "$remote/"
Invoke-Adb shell "chmod 755 $remote/*"
Invoke-Adb shell "timeout 90 /system/bin/sh $remote/test-runtime.sh"
Write-Output "Runtime test files retained for inspection: $remote"
