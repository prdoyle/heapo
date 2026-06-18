#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

BINARY="$SCRIPT_DIR/cli/build/install/heapo/bin/heapo"
BIN_DIR="$HOME/.local/bin"
LINK="$BIN_DIR/heapo"
SKILL_DIR="$HOME/.claude/skills/heapo"
SKILL_FILE="$SKILL_DIR/SKILL.md"
CLAUDE_SETTINGS="$HOME/.claude/settings.json"
PERMISSION_RULE='Bash(heapo *)'

check_built()      { [ -x "$BINARY" ]; }
check_linked()     { [ -L "$LINK" ] && [ "$(readlink "$LINK")" = "$BINARY" ]; }
check_skill()      { check_linked && [ -f "$SKILL_FILE" ] && \
                     "$BINARY" skill | diff - "$SKILL_FILE" &>/dev/null; }
check_path()       { command -v heapo &>/dev/null; }
check_permission() {
    [ -f "$CLAUDE_SETTINGS" ] || return 1
    python3 - "$CLAUDE_SETTINGS" "$PERMISSION_RULE" <<'EOF'
import json, sys
try:
    s = json.load(open(sys.argv[1]))
    sys.exit(0 if sys.argv[2] in s.get('permissions', {}).get('allow', []) else 1)
except Exception:
    sys.exit(1)
EOF
}

print_status() {
    local all_ok=1
    check_built      && echo "[✓] Binary built (cli/build/install/heapo/bin/heapo)" \
                     || { echo "[✗] Binary built (cli/build/install/heapo/bin/heapo)";                    all_ok=0; }
    check_linked     && echo "[✓] Binary linked (~/.local/bin/heapo)" \
                     || { echo "[✗] Binary linked (~/.local/bin/heapo)";                                  all_ok=0; }
    check_skill      && echo "[✓] Claude skill installed (~/.claude/skills/heapo/SKILL.md)" \
                     || { echo "[✗] Claude skill installed (~/.claude/skills/heapo/SKILL.md)";            all_ok=0; }
    check_path       && echo "[✓] On PATH (heapo command available)" \
                     || { echo "[✗] On PATH (heapo command available)";                                   all_ok=0; }
    check_permission && echo "[✓] Claude Code allowlist entry present (~/.claude/settings.json)" \
                     || { echo "[✗] Claude Code allowlist entry present (~/.claude/settings.json)";       all_ok=0; }
    if [ "$all_ok" -eq 1 ]; then
        echo "Status: ready"
    else
        echo "Status: INCOMPLETE"
    fi
}

print_help() {
    echo "heapo installer"
    echo ""
    echo "Usage:"
    echo "  ./install.sh           Show this help and the current status"
    echo "  ./install.sh --go      Install or update heapo (safe to re-run;"
    echo "                         already-completed steps are skipped)"
    echo "  ./install.sh --status  Show what's installed and what remains"
    echo "  ./install.sh --help    Show this help and the current status"
    echo ""
    echo "See README.md for setup and usage instructions."
}

case "${1:-}" in
    --go)
        : # fall through to install steps below
        ;;
    --status)
        print_status
        exit 0
        ;;
    --help|-h|"")
        print_help
        echo ""
        echo "Currently installed:"
        print_status
        exit 0
        ;;
    *)
        print_help
        echo ""
        echo "Currently installed:"
        print_status
        exit 1
        ;;
esac

# ── Install steps ─────────────────────────────────────────────────────────────

if check_built; then
    echo "[✓] Binary already built"
else
    echo "[ ] Building heapo..."
    ./gradlew :cli:installDist
    echo "[✓] Binary built"
fi

mkdir -p "$BIN_DIR"
if check_linked; then
    echo "[✓] Binary already linked"
else
    ln -sf "$BINARY" "$LINK"
    echo "[✓] Binary linked to ~/.local/bin/heapo"
fi

mkdir -p "$SKILL_DIR"
if check_skill; then
    echo "[✓] Claude skill already up to date"
else
    "$BINARY" skill > "$SKILL_FILE"
    echo "[✓] Claude skill installed"
fi

if check_permission; then
    echo "[✓] Claude Code allowlist entry already present"
else
    python3 - "$CLAUDE_SETTINGS" "$PERMISSION_RULE" <<'EOF'
import json, pathlib, sys
settings_path = pathlib.Path(sys.argv[1])
rule = sys.argv[2]
settings = json.loads(settings_path.read_text()) if settings_path.exists() else {}
allow = settings.setdefault('permissions', {}).setdefault('allow', [])
allow.append(rule)
settings_path.parent.mkdir(parents=True, exist_ok=True)
settings_path.write_text(json.dumps(settings, indent=2) + '\n')
EOF
    echo "[✓] Claude Code allowlist entry added (~/.claude/settings.json)"
fi

if check_path; then
    echo "[✓] heapo is on your PATH"
else
    echo ""
    echo "Installed to ~/.local/bin/heapo"
    echo "Add this to ~/.zshrc (or ~/.bashrc) to put it on your PATH:"
    echo ""
    echo '  export PATH="$HOME/.local/bin:$PATH"'
    echo ""
    echo "Then run: source ~/.zshrc or start a fresh terminal window"
fi
