#!/bin/bash
# Install git hooks for Luciferase
#
# This script installs the pre-push hook that:
# 1. Syncs beads (via bd hooks)
# 2. Runs full test suite before push
#
# Usage:
#   ./git-hooks/install.sh

set -e

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOKS_DIR="$REPO_ROOT/.git/hooks"
SOURCE_HOOKS_DIR="$REPO_ROOT/git-hooks"

echo "======================================"
echo "Installing Luciferase git hooks"
echo "======================================"

# Check if we're in a git repository
if [ ! -d "$HOOKS_DIR" ]; then
    echo "Error: Not in a git repository or .git/hooks not found" >&2
    exit 1
fi

# Backup existing pre-push hook if it exists and isn't our hook
if [ -f "$HOOKS_DIR/pre-push" ]; then
    if ! grep -q "Pre-push hook: Runs bd sync and tests before push" "$HOOKS_DIR/pre-push" 2>/dev/null; then
        echo "Backing up existing pre-push hook to pre-push.backup.$(date +%Y%m%d-%H%M%S)"
        cp "$HOOKS_DIR/pre-push" "$HOOKS_DIR/pre-push.backup.$(date +%Y%m%d-%H%M%S)"
    fi
fi

# Install pre-push hook
echo "Installing pre-push hook..."
cp "$SOURCE_HOOKS_DIR/pre-push" "$HOOKS_DIR/pre-push"
chmod +x "$HOOKS_DIR/pre-push"

echo ""
echo "✅ Git hooks installed successfully"
echo ""
echo "Pre-push hook will now:"
echo "  1. Sync beads (bd hooks)"
echo "  2. Run full test suite (~12 minutes with parallel CI)"
echo ""
echo "To bypass hook (emergency only): git push --no-verify"
echo "======================================"
