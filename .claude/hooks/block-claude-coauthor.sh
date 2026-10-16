#!/usr/bin/env bash
cmd=$(jq -r '.tool_input.command // ""')
case "$cmd" in *commit*) ;; *) exit 0 ;; esac
printf '%s' "$cmd" | grep -qiE 'co-authored-by:[[:space:]]*claude' || exit 0
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Запрещено: трейлер Co-Authored-By: Claude в коммите. Убери строку из сообщения и повтори."}}'
