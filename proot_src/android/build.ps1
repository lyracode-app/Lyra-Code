param(
    [Parameter(Mandatory = $true)][string]$NdkPath,
    [string]$CMake = 'cmake',
    [string]$Ninja = 'ninja',
    [string[]]$Abis = @('arm64-v8a', 'x86_64')
)
$ErrorActionPreference = 'Stop'
$source = Split-Path $PSScriptRoot -Parent
foreach ($abi in $Abis) {
    if ($abi -notin @('arm64-v8a', 'x86_64')) { throw "Unsupported ABI: $abi" }
    $build = Join-Path $source "build/android-$abi"
    & $CMake -S $PSScriptRoot -B $build -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$Ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$NdkPath/build/cmake/android.toolchain.cmake" `
        "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-24 `
        "-DPROOT_VERSION=5.1.107.91-lyra.2"
    if ($LASTEXITCODE -ne 0) { throw "Configure failed: $abi" }
    & $CMake --build $build --parallel 8
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $abi" }
    Get-FileHash "$build/dist/*.so" -Algorithm SHA256
}
