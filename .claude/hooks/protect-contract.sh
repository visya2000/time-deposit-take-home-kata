#!/usr/bin/env bash
# PreToolUse (Edit|Write|MultiEdit): block any modification of the frozen TimeDeposit contract.
# Input: hook JSON on stdin. Exit 2 = block; stderr is fed back to Claude.
input=$(cat)

# Match the exact file name only (not TimeDepositCalculator.java etc.), with / or \ separators.
if printf '%s' "$input" | grep -qE '"file_path"[[:space:]]*:[[:space:]]*"[^"]*[\\/]TimeDeposit\.java"'; then
  echo "Blocked: TimeDeposit.java is a frozen shared contract (README: no breaking changes to TimeDeposit)." >&2
  echo "Adapt around it instead (adapters, mappers, read models)." >&2
  exit 2
fi
exit 0
