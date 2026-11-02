#!/usr/bin/env bash
# usage: case "desc" REF INPUT BUMP FORCED PREV  expect_version expect_code expect_channel
: "${WT_TMP:?run via run.sh}"; pass=0; fail=0
t() { local desc=$1; shift
  out=$(mktemp); GITHUB_OUTPUT=$out GITHUB_REF=$1 INPUT_VERSION=$2 BUMP_TYPE=$3 FORCED_CH=$4 PREV_TAG=$5 bash -e "$WT_TMP/calc.sh" >/dev/null 2>&1 || { echo "FAIL(crash) $desc"; fail=$((fail+1)); return; }
  v=$(grep ^version_name= $out|cut -d= -f2); c=$(grep ^version_code= $out|cut -d= -f2); ch=$(grep ^channel= $out|cut -d= -f2)
  if [[ "$v|$c|$ch" == "$6|$7|$8" ]]; then pass=$((pass+1)); echo "ok   $desc → $v $c $ch"; else fail=$((fail+1)); echo "FAIL $desc → got $v|$c|$ch want $6|$7|$8"; fi; }
t "tag stable"           refs/tags/v1.2.3        ""    ""        ""   ""            1.2.3          10203900 release
t "tag rc.2"             refs/tags/v1.2.3-rc.2   ""    ""        ""   ""            1.2.3-rc.2     10203602 rc
t "tag beta1 no dot"     refs/tags/v2.0.0-beta1  ""    ""        ""   ""            2.0.0-beta1    20000401 beta
t "dispatch explicit"    refs/heads/main 1.4.0-alpha.3 auto auto  v1.0.0       1.4.0-alpha.3  10400203 alpha
t "bootstrap no prev"    refs/heads/main auto    auto      auto   none          1.0.0-alpha.1  10000201 alpha
t "increment alpha"      refs/heads/main auto    increment auto   v1.2.3-alpha.2 1.2.3-alpha.3 10203203 alpha
t "increment from stable" refs/heads/main auto   auto      auto   v1.2.3        1.2.4-alpha.1  10204201 alpha
t "promote alpha→beta"   refs/heads/main auto    promote   auto   v1.2.3-alpha.4 1.2.3-beta.1  10203401 beta
t "promote rc→release"   refs/heads/main auto    promote   auto   v1.2.3-rc.1   1.2.3          10203900 release
t "minor bump"           refs/heads/main auto    minor     auto   v1.2.3-beta.2 1.3.0-alpha.1  10300201 alpha
t "major forced beta"    refs/heads/main auto    major     beta   v1.2.3        2.0.0-beta.1   20000401 beta
t "patch"                refs/heads/main auto    patch     auto   v1.2.3        1.2.4-alpha.1  10204201 alpha
t "pre_num capped 99"    refs/tags/v1.0.0-dev.150 "" ""      ""   ""            1.0.0-dev.150  10000199 dev
# ordering guarantees: rc.99 < stable, stable < next patch bleeding
[[ 10203699 -lt 10203900 && 10203900 -lt 10204000 ]] && echo "ok   ordering rc<stable<next-patch" || echo "FAIL ordering"
t "patch 100 overflows into minor" refs/tags/v1.0.100 "" "" "" "" 1.0.100 10100900 release
t "garbage tag"  refs/tags/vfoo "" "" "" "" foo 200 alpha
echo "== $pass passed, $fail failed"; [[ $fail -eq 0 ]]
