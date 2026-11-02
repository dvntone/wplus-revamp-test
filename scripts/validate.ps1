# =============================================================================
# wscan+ pre-push / local quality validator (PowerShell)
# =============================================================================
# Runs the same checks as the Code Quality Gate CI workflow so you can catch
# failures BEFORE pushing.  Use this on Windows where bash is not available.
#
# Usage:
#   .\scripts\validate.ps1              # both platforms
#   .\scripts\validate.ps1 -Android    # Android only
#   .\scripts\validate.ps1 -Windows    # Windows only
#   .\scripts\validate.ps1 -Fix        # apply ktlint auto-fixes then check
#
# Exit code 0 = all checks passed, non-zero = one or more checks failed.
# =============================================================================

[CmdletBinding()]
param(
    [switch]$Android,
    [switch]$Windows,
    [switch]$Fix
)

$ErrorActionPreference = "Continue"
$Root        = Split-Path $PSScriptRoot -Parent
$AndroidDir  = $Root
$WindowsDir  = Join-Path $Root "windows"

$RunAndroid  = -not $Windows -or $Android
$RunWindows  = -not $Android -or $Windows
$Failed      = [System.Collections.Generic.List[string]]::new()

function Write-Pass($msg) { Write-Host "✅ $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "❌ $msg" -ForegroundColor Red; $Failed.Add($msg) }
function Write-Warn($msg) { Write-Host "⚠  $msg" -ForegroundColor Yellow }
function Write-Header($msg) { Write-Host "`n═══ $msg ═══" -ForegroundColor Cyan }

# ══════════════════════════════════════════════════════════════════════════════
# ANDROID CHECKS
# ══════════════════════════════════════════════════════════════════════════════
if ($RunAndroid) {
    Write-Header "Android Checks"

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Write-Warn "Java not found. Skipping Android checks. Install JDK 17+ to enable them."
    } else {
        Push-Location $AndroidDir

        # 1. Kotlin compile
        Write-Host "1/4  Kotlin compile…"
        & .\gradlew.bat compileDebugKotlin --no-daemon -q
        if ($LASTEXITCODE -eq 0) { Write-Pass "Kotlin compile" }
        else { Write-Fail "Kotlin compile — fix errors above before pushing" }

        # 2. Android Lint
        Write-Host "2/4  Android Lint…"
        & .\gradlew.bat lintDebug --no-daemon -q
        if ($LASTEXITCODE -eq 0) { Write-Pass "Android Lint" }
        else { Write-Warn "Android Lint produced warnings (see app/build/reports/lint-results-debug.html)" }

        # 3. ktlint
        Write-Host "3/4  ktlint style check…"
        if (-not (Get-Command ktlint -ErrorAction SilentlyContinue)) {
            Write-Warn "ktlint not installed. Install: scoop install ktlint"
        } else {
            $ktArgs = @("--reporter=plain")
            if ($Fix) {
                $ktArgs += "--format"
                Write-Host "      (--Fix mode: auto-formatting Kotlin sources)"
            }
            $ktArgs += "app/src/main/java/**/*.kt"
            $ktArgs += "app/src/test/java/**/*.kt"
            & ktlint @ktArgs
            if ($LASTEXITCODE -eq 0) { Write-Pass "ktlint" }
            elseif ($Fix) { Write-Warn "ktlint formatted files; review changes" }
            else { Write-Fail "ktlint style — run with -Fix to auto-format" }
        }

        # 4. Unit tests
        Write-Host "4/4  Unit tests…"
        & .\gradlew.bat test --no-daemon -q
        if ($LASTEXITCODE -eq 0) { Write-Pass "Unit tests" }
        else { Write-Fail "Unit tests — check app/build/reports/tests/ for details" }

        Pop-Location
    }
}

# ══════════════════════════════════════════════════════════════════════════════
# WINDOWS CHECKS
# ══════════════════════════════════════════════════════════════════════════════
if ($RunWindows) {
    Write-Header "Windows Checks"

    if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) {
        Write-Warn "dotnet SDK not found. Install .NET 8 SDK to enable Windows checks."
    } else {
        Push-Location $WindowsDir

        # 1. NuGet restore
        Write-Host "1/3  NuGet restore…"
        dotnet restore WscanPlus.sln -v q
        if ($LASTEXITCODE -eq 0) { Write-Pass "NuGet restore" }
        else { Write-Fail "NuGet restore" }

        # 2. Build
        Write-Host "2/3  .NET build (WarningLevel 5)…"
        dotnet build WscanPlus.sln -c Debug --no-restore /p:WarningLevel=5 /p:TreatWarningsAsErrors=false -v q
        if ($LASTEXITCODE -eq 0) { Write-Pass ".NET build" }
        else { Write-Fail ".NET build — fix compiler errors above" }

        # 3. Tests
        Write-Host "3/3  xUnit tests…"
        dotnet test WscanPlus.Tests/WscanPlus.Tests.csproj -c Debug --no-build --logger "console;verbosity=minimal"
        if ($LASTEXITCODE -eq 0) { Write-Pass "xUnit tests" }
        else { Write-Fail "xUnit tests" }

        Pop-Location
    }
}

# ══════════════════════════════════════════════════════════════════════════════
# RESULT SUMMARY
# ══════════════════════════════════════════════════════════════════════════════
Write-Header "Summary"

if ($Failed.Count -eq 0) {
    Write-Host "All checks passed! Safe to push. 🎉" -ForegroundColor Green
    exit 0
} else {
    Write-Host "$($Failed.Count) check(s) failed:" -ForegroundColor Red
    $Failed | ForEach-Object { Write-Host "  • $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Fix the issues above before pushing to avoid CI failures." -ForegroundColor Yellow
    exit 1
}
