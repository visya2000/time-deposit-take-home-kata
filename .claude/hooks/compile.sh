#!/usr/bin/env bash
# PostToolUse (Edit|Write|MultiEdit): compile the java module after a .java file in it changed.
# Exit 2 = compilation failed; the compiler output is fed back to Claude.
input=$(cat)
java_dir="$CLAUDE_PROJECT_DIR/java"

# Only react to .java files inside the java module.
printf '%s' "$input" | grep -qE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*[\\/]java[\\/][^"]*\.java"' || exit 0
# No-op until the Maven Wrapper exists (PLAN.md step 2).
[ -x "$java_dir/mvnw" ] || [ -f "$java_dir/mvnw" ] || exit 0

cd "$java_dir" || exit 0
if ! output=$(./mvnw -q -B test-compile 2>&1); then
  echo "Compilation failed after this edit:" >&2
  printf '%s\n' "$output" | grep -E 'ERROR|error:' | head -n 30 >&2
  exit 2
fi
exit 0
