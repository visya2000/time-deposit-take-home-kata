#!/usr/bin/env bash
# Stop: before Claude ends its turn, unit tests must pass if the java module has uncommitted changes.
# Exit 2 = tests failed; Claude is prevented from stopping and receives the failure summary.
# Testcontainers tests (*IT.java) are intentionally excluded — they run in `./mvnw verify` / CI.
input=$(cat)
java_dir="$CLAUDE_PROJECT_DIR/java"

# Already continuing because of this hook: do not loop forever.
printf '%s' "$input" | grep -qE '"stop_hook_active"[[:space:]]*:[[:space:]]*true' && exit 0
# No-op until the Maven Wrapper exists (PLAN.md step 2).
[ -f "$java_dir/mvnw" ] || exit 0
# Nothing changed in the java module: nothing to verify.
[ -n "$(git -C "$CLAUDE_PROJECT_DIR" status --porcelain -- java)" ] || exit 0

cd "$java_dir" || exit 0
if ! output=$(./mvnw -q -B test 2>&1); then
  echo "Unit tests are failing — fix them before finishing:" >&2
  printf '%s\n' "$output" | grep -E 'FAIL|ERROR|Tests run:|expected|but was' | head -n 40 >&2
  exit 2
fi
exit 0
