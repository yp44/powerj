#!/usr/bin/env bash
# The cases of ci/check-commit-subjects.sh: what passes and what fails it (#184). No repository needed.
set -euo pipefail

check="$(dirname "$0")/check-commit-subjects.sh"
failures=0

expect() {
    local verdict="$1" subject="$2" got
    if printf '%s\n' "$subject" | "$check" - > /dev/null 2>&1; then got=pass; else got=fail; fi
    if [ "$got" = "$verdict" ]; then
        echo "ok: $verdict: $subject"
    else
        echo "WRONG: expected $verdict, got $got: $subject" >&2
        failures=$((failures + 1))
    fi
}

expect pass 'fix(fx): grid reports a toolkit that cannot start as an error (#182)'
expect pass 'feat!: the shell is case-sensitive'
expect pass 'feat(api)!: Row refuses a null column name'
expect pass 'refactor(api,cmdlets): one Messages class'
expect pass 'build(deps): bump the maven-plugins group with 3 updates'
expect pass 'build(deps-dev): bump org.junit:junit-bom from 6.0.0 to 6.0.1'
expect pass 'docs: tick #182 in the audit v2 table'
expect pass 'perf(core): cache the method table'
expect pass 'chore: remove a stray file'
expect pass 'style(core): formatting'
expect pass 'Merge pull request #188 from yp44/claude/serene-franklin-nnlyi5'
expect pass "Merge branch 'claude/audit_phase_5' into fix/182-grid"
expect pass 'Revert "fix(fx): x"'
expect fail 'shell: x'
expect fail 'select: keep the order of the properties'
expect fail 'README: link the audit'
expect fail 'Fix the build'
expect fail 'Update CONTRIBUTING.md'
expect fail 'feature(core): x'
expect fail 'fix(Core): x'
expect fail 'fix (core): x'
expect fail 'fix(core) x'
expect fail 'fix(core):x'
expect fail 'fix:  two spaces'
expect fail 'fixup! fix(core): x'

# Several subjects: one wrong is enough.
if printf '%s\n' 'fix(fx): a' 'shell: b' | "$check" - > /dev/null 2>&1; then
    echo "WRONG: one wrong subject among several passed" >&2
    failures=$((failures + 1))
fi

[ "$failures" -eq 0 ] || { echo "$failures case(s) of the commit subject check failed" >&2; exit 1; }
echo "commit subject check: OK"
