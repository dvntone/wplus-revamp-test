#!/usr/bin/env bash
# =============================================================================
# wscan+ pre-push / local quality validator
# =============================================================================
# Runs the same checks as the Code Quality Gate CI workflow so you can catch
# failures BEFORE pushing.
#
# Usage:
#   ./scripts/validate.sh              # both platforms
#   ./scripts/validate.sh --android    # Android only
#   ./scripts/validate.sh --windows    # Windows only (requires .NET SDK)
#   ./scripts/validate.sh --fix        # apply ktlint auto-fixes then check
#
# Exit code 0 = all checks passed, non-zero = one or more checks failed.
# =============================================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BOLD='\033[1m'; RESET='\033[0m'

pass() { echo -e "${GREEN}✅ $1${RESET}"; }
fail() { echo -e "${RED}❌ $1${RESET}"; FAILED+=("$1"); }
warn() { echo -e "${YELLOW}⚠  $1${RESET}"; }
header() { echo -e "\n${BOLD}═══ $1 ═══${RESET}"; }

FAILED=()
RUN_ANDROID=true
RUN_WINDOWS=true
FIX_MODE=false

for arg in "$@"; do
  case "$arg" in
    --android) RUN_WINDOWS=false ;;
    --windows) RUN_ANDROID=false ;;
    --fix)     FIX_MODE=true ;;
    -h|--help)
      echo "Usage: $0 [--android] [--windows] [--fix]"
      exit 0 ;;
  esac
done

# ══════════════════════════════════════════════════════════════════════════════
# ANDROID CHECKS
# ══════════════════════════════════════════════════════════════════════════════
if $RUN_ANDROID; then
  header "Android Checks"
  ANDROID="$ROOT"

  # ── Prerequisite: Java ───────────────────────────────────────────────────
  if ! command -v java &>/dev/null; then
    warn "Java not found. Skipping Android checks. Install JDK 17+ to enable them."
  else
    cd "$ANDROID"

    # ── 1. Kotlin compile ────────────────────────────────────────────────────
    echo "1/4  Kotlin compile…"
    if ./gradlew compileDebugKotlin --no-daemon -q 2>&1; then
      pass "Kotlin compile"
    else
      fail "Kotlin compile — fix errors above before pushing"
    fi

    # ── 2. Android Lint ──────────────────────────────────────────────────────
    echo "2/4  Android Lint…"
    if ./gradlew lintDebug --no-daemon -q 2>&1; then
      pass "Android Lint"
    else
      warn "Android Lint produced warnings/errors (see app/build/reports/lint-results-debug.html)"
    fi

    # ── 3. ktlint ────────────────────────────────────────────────────────────
    echo "3/4  ktlint style check…"
    if ! command -v ktlint &>/dev/null; then
      warn "ktlint not installed. Install: brew install ktlint  or  scoop install ktlint"
    else
      KTLINT_FLAGS="--reporter=plain"
      if $FIX_MODE; then
        KTLINT_FLAGS="$KTLINT_FLAGS --format"
        echo "      (--fix mode: auto-formatting Kotlin sources)"
      fi
      if ktlint $KTLINT_FLAGS \
          "app/src/main/java/**/*.kt" \
          "app/src/test/java/**/*.kt" 2>&1; then
        pass "ktlint"
      else
        if $FIX_MODE; then
          warn "ktlint formatted files; review changes before committing"
        else
          fail "ktlint style — run with --fix to auto-format, or fix manually"
        fi
      fi
    fi

    # ── 4. Unit tests ────────────────────────────────────────────────────────
    echo "4/4  Unit tests…"
    if ./gradlew test --no-daemon -q 2>&1; then
      pass "Unit tests"
    else
      fail "Unit tests — check app/build/reports/tests/ for details"
    fi

    cd "$ROOT"
  fi
fi

# ══════════════════════════════════════════════════════════════════════════════
# WINDOWS CHECKS
# ══════════════════════════════════════════════════════════════════════════════
if $RUN_WINDOWS; then
  header "Windows Checks"
  WINDOWS="$ROOT/windows"

  if ! command -v dotnet &>/dev/null; then
    warn "dotnet SDK not found. Skipping Windows checks. Install .NET 8 SDK to enable them."
  else
    cd "$WINDOWS"

    # ── 1. Restore ───────────────────────────────────────────────────────────
    echo "1/3  NuGet restore…"
    if dotnet restore WscanPlus.sln -v q 2>&1; then
      pass "NuGet restore"
    else
      fail "NuGet restore — check your internet connection or package sources"
    fi

    # ── 2. Build with high warning level ────────────────────────────────────
    echo "2/3  .NET build (WarningLevel 5)…"
    if dotnet build WscanPlus.sln -c Debug --no-restore \
        /p:WarningLevel=5 \
        /p:TreatWarningsAsErrors=false \
        -v q 2>&1; then
      pass ".NET build"
    else
      fail ".NET build — fix compiler errors above before pushing"
    fi

    # ── 3. xUnit tests ───────────────────────────────────────────────────────
    echo "3/3  xUnit tests…"
    if dotnet test WscanPlus.Tests/WscanPlus.Tests.csproj \
        -c Debug --no-build \
        --logger "console;verbosity=minimal" 2>&1; then
      pass "xUnit tests"
    else
      fail "xUnit tests — check test output above for failures"
    fi

    cd "$ROOT"
  fi
fi

# ══════════════════════════════════════════════════════════════════════════════
# RESULT SUMMARY
# ══════════════════════════════════════════════════════════════════════════════
header "Summary"

if [ ${#FAILED[@]} -eq 0 ]; then
  echo -e "${GREEN}${BOLD}All checks passed! Safe to push. 🎉${RESET}"
  exit 0
else
  echo -e "${RED}${BOLD}${#FAILED[@]} check(s) failed:${RESET}"
  for f in "${FAILED[@]}"; do
    echo -e "  ${RED}• $f${RESET}"
  done
  echo ""
  echo -e "${YELLOW}Fix the issues above before pushing to avoid CI failures.${RESET}"
  exit 1
fi
