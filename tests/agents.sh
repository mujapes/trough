#!/bin/bash
# Agent backends against the real CLIs, on any JVM (the backends use only java.*). Real model
# turns, but every tool request is denied/declined, so nothing the model asks for runs.
#   tests/agents.sh [claude|codex ...]      (default: both that are installed)
# Codex runs in a throwaway CODEX_HOME holding only a copy of this machine's auth.json.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
H="$ROOT/hot/src/com/trough/hot"
javac -nowarn -d "$W/c" "$H/Json.java" "$H/Agent.java" "$H/ClaudeAgent.java" "$H/CodexAgent.java" \
    "$ROOT/tests/jvm/ClaudeAgentTest.java" "$ROOT/tests/jvm/CodexAgentTest.java" || exit 1
WANT="${*:-claude codex}"; F=0
CLAUDE_BIN="${CLAUDE_BIN:-$(readlink -f "$HOME/.local/bin/claude" 2>/dev/null)}"
CODEX_BIN="${CODEX_BIN:-$(ls /usr/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex 2>/dev/null)}"
for a in $WANT; do
    mkdir -p "$W/$a/cwd"
    case $a in
        claude) [ -f "$CLAUDE_BIN" ] || continue
                echo "== claude"; java -cp "$W/c" ClaudeAgentTest "$CLAUDE_BIN" "$W/$a/cwd" || F=$((F+$?)) ;;
        codex)  [ -f "$CODEX_BIN" ] && [ -f "$HOME/.codex/auth.json" ] || continue
                mkdir -p "$W/$a/home/.codex" && cp "$HOME/.codex/auth.json" "$W/$a/home/.codex/" && chmod 600 "$W/$a/home/.codex/auth.json"
                echo "== codex"; java -cp "$W/c" CodexAgentTest "$CODEX_BIN" "$W/$a/cwd" "$W/$a/home/.codex" || F=$((F+$?)) ;;
    esac
done
echo "== $F failed"; exit $F
