# OpenManager Native Build Script for C / C++ ImGui Test Binary
param (
    [string]$NdkPath = "C:\ndk",
    [string]$ApiLevel = "30"
)

$ErrorActionPreference = "Continue"

if (-not (Test-Path $NdkPath)) {
    if (Test-Path "$env:LOCALAPPDATA\Android\Sdk\ndk") {
        $found = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk" | Select-Object -First 1
        if ($found) { $NdkPath = $found.FullName }
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   Compiling OpenManager C / C++ ImGui Test Script        " -ForegroundColor Green
Write-Host "   NDK Root : $NdkPath" -ForegroundColor Yellow
Write-Host "   Target API : Android $ApiLevel" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

$ToolchainBin = Join-Path $NdkPath "toolchains\llvm\prebuilt\windows-x86_64\bin"
if (-not (Test-Path $ToolchainBin)) {
    throw "LLVM toolchain not found at $ToolchainBin"
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $ScriptDir

try {
    $CommonFlags = @(
        "-O3",
        "-fPIE",
        "-pie",
        "-static-libstdc++",
        "-Isrc",
        "-Iimgui",
        "-DIMGUI_DISABLE_FILE_FUNCTIONS=0",
        "-Wno-macro-redefined"
    )

    $Targets = @(
        @{ Arch = "arm64-v8a"; CCompiler = "aarch64-linux-android$ApiLevel-clang.cmd"; CxxCompiler = "aarch64-linux-android$ApiLevel-clang++.cmd"; OutDir = "bin\arm64-v8a" },
        @{ Arch = "x86_64";    CCompiler = "x86_64-linux-android$ApiLevel-clang.cmd";    CxxCompiler = "x86_64-linux-android$ApiLevel-clang++.cmd";    OutDir = "bin\x86_64" },
        @{ Arch = "armeabi-v7a"; CCompiler = "armv7a-linux-androideabi$ApiLevel-clang.cmd"; CxxCompiler = "armv7a-linux-androideabi$ApiLevel-clang++.cmd"; OutDir = "bin\armeabi-v7a" },
        @{ Arch = "x86";       CCompiler = "i686-linux-android$ApiLevel-clang.cmd";       CxxCompiler = "i686-linux-android$ApiLevel-clang++.cmd";       OutDir = "bin\x86" }
    )

    foreach ($target in $Targets) {
        $arch = $target.Arch
        $cCompiler = Join-Path $ToolchainBin $target.CCompiler
        $cxxCompiler = Join-Path $ToolchainBin $target.CxxCompiler
        $outDir = Join-Path $ScriptDir $target.OutDir
        $outFile = Join-Path $outDir "test_script"
        $objDir = Join-Path $outDir "obj"

        Write-Host "`n[*] Compiling target: $arch..." -ForegroundColor Cyan
        if (-not (Test-Path $objDir)) {
            New-Item -ItemType Directory -Path $objDir -Force | Out-Null
        }

        if (-not (Test-Path $cxxCompiler)) {
            Write-Warning "Compiler not found for $arch ($cxxCompiler), skipping."
            continue
        }

        # 1. Compile C syscall probe
        $cObj = Join-Path $objDir "syscall_probe.o"
        $cArgs = @("-c", "src\syscall_probe.c", "-o", $cObj, "-O3", "-fPIE", "-Isrc")
        & $cCompiler $cArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Failed compiling syscall_probe.c for $arch (Exit code: $LASTEXITCODE)"
        }

        # 2. Compile C++ and link
        $cppSources = @(
            "src\main.cpp",
            "imgui\imgui.cpp",
            "imgui\imgui_draw.cpp",
            "imgui\imgui_tables.cpp",
            "imgui\imgui_widgets.cpp"
        )

        $cmdArgs = @()
        $cmdArgs += "-std=c++17"
        $cmdArgs += $CommonFlags
        $cmdArgs += $cObj
        $cmdArgs += $cppSources
        $cmdArgs += "-o"
        $cmdArgs += $outFile

        & $cxxCompiler $cmdArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Link failed for $arch (Exit code: $LASTEXITCODE)!"
        }

        if (Test-Path $outFile) {
            $fileSize = (Get-Item $outFile).Length
            Write-Host "    [SUCCESS] Built: $outFile ($fileSize bytes)" -ForegroundColor Green
        }
    }

    Write-Host "`n==========================================================" -ForegroundColor Cyan
    Write-Host "   Compilation Completed for All Supported ABIs!          " -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Cyan
} finally {
    Pop-Location
}
