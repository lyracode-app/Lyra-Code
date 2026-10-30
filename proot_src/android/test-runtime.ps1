param(
    [Parameter(Mandatory = $true)][string]$NdkPath,
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'adb',
    [string]$RunAs = ''
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
$names = @('puts_proc_self_exe', 'execveat', 'open_nofollow', 'test-fork',
    'test-upstream', 'test-latest', 'fork_regs', 'fchmodat2', 'open_tree', 'test-571fb953',
    'test-f6b33793', 'getdents', 'link', 'renameat2', 'statx', 'unix_socket')
foreach ($name in $names) {
    $inputFile = if ($name -in @('test-fork', 'test-upstream', 'test-latest')) { "$PSScriptRoot/$name.c" } else { "$source/tests/$name.c" }
    & $compiler "--target=$target" -O2 -fPIE -pie $inputFile -o "$helpers/$name"
    if ($LASTEXITCODE -ne 0) { throw "Compile failed: $name" }
}
$remote = '/data/local/tmp/lyra-proot-' + [Guid]::NewGuid().ToString('N')
Invoke-Adb shell mkdir -p $remote
foreach ($file in (@("$build/dist/libproot_exec.so", "$build/dist/libproot_loader.so") +
    @($names | ForEach-Object { "$helpers/$_" }))) {
    Invoke-Adb push $file "$remote/"
}
# Git on Windows may check shell files out as CRLF.
$script = [IO.File]::ReadAllText("$PSScriptRoot/test-runtime.sh").Replace("`r`n", "`n")
[IO.File]::WriteAllText("$helpers/test-runtime.sh", $script, [Text.UTF8Encoding]::new($false))
Invoke-Adb push "$helpers/test-runtime.sh" "$remote/"
Invoke-Adb shell "chmod 755 $remote/*"
if ($RunAs) {
    if ($RunAs -notmatch '^[A-Za-z0-9_.]+$') { throw 'Invalid RunAs package name.' }
    $apk = (Invoke-Adb shell pm path $RunAs | Select-Object -First 1).Trim()
    if (-not $apk.StartsWith('package:')) { throw "Package not installed: $RunAs" }
    $packageDir = $apk.Substring(8) -replace '/[^/]+$', ''
    $nativeAbi = if ($abi -eq 'arm64-v8a') { 'arm64' } else { 'x86_64' }
    $nativeDir = "$packageDir/lib/$nativeAbi"
    foreach ($name in @('libproot_exec.so', 'libproot_loader.so')) {
        $installedHash = (Invoke-Adb shell "run-as $RunAs sha256sum $nativeDir/$name").Split(' ')[0]
        $builtHash = (Get-FileHash "$build/dist/$name" -Algorithm SHA256).Hash
        if ($installedHash -ne $builtHash) { throw "Installed runtime does not match this build: $name" }
    }
    # Leave room for rootfs paths and temporary Unix sockets (sun_path is 108 bytes).
    $appRemote = 'files/pt-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    Invoke-Adb shell "run-as $RunAs mkdir -p $appRemote"
    $archive = Join-Path $helpers 'runtime-tests.tar'
    & tar.exe -cf $archive -C $helpers @names test-runtime.sh
    if ($LASTEXITCODE -ne 0) { throw 'Archiving runtime helpers failed.' }
    $start = [Diagnostics.ProcessStartInfo]::new($Adb)
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    foreach ($argument in @('-s', $Serial, 'exec-in', 'run-as', $RunAs, 'tar', '-C', $appRemote, '-xf', '-')) {
        $start.ArgumentList.Add($argument)
    }
    $copy = [Diagnostics.Process]::Start($start)
    $inputArchive = [IO.File]::OpenRead($archive)
    try { $inputArchive.CopyTo($copy.StandardInput.BaseStream) }
    finally { $inputArchive.Dispose(); $copy.StandardInput.Close() }
    $copy.WaitForExit()
    if ($copy.ExitCode -ne 0) { throw 'Copying runtime helpers into the application failed.' }
    $appFiles = (@($names) + @('test-runtime.sh') | ForEach-Object { "$appRemote/$_" }) -join ' '
    Invoke-Adb shell "run-as $RunAs chmod 755 $appFiles"
    Invoke-Adb shell "run-as $RunAs env PROOT_EXEC=$nativeDir/libproot_exec.so PROOT_LOADER=$nativeDir/libproot_loader.so timeout 180 /system/bin/sh $appRemote/test-runtime.sh"
    Write-Output "Application runtime test files retained for inspection: $RunAs/$appRemote"
} else {
    Invoke-Adb shell "timeout 180 /system/bin/sh $remote/test-runtime.sh"
}
Write-Output "Runtime test files retained for inspection: $remote"
