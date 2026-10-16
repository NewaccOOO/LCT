#!/usr/bin/env bash
# Запрещает коммит с трейлером Co-Authored-By: Claude (облачные сессии берут хук из репозитория).
in=$(cat)
if command -v jq > /dev/null 2>&1; then
  cmd=$(printf '%s' "$in" | jq -r '.tool_input.command // ""')
else
  cmd=$(printf '%s' "$in" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("tool_input",{}).get("command",""))')
fi
case "$cmd" in *commit*) ;; *) exit 0 ;; esac
printf '%s' "$cmd" | grep -qiE 'co-authored-by:[[:space:]]*claude' || exit 0
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Запрещено: трейлер Co-Authored-By: Claude в коммите. Убери строку из сообщения и повтори."}}'
