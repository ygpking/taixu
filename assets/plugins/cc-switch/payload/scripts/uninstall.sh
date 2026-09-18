#!/bin/sh
set -e

TOOL_DIR="${TAIXU_TOOL_DIR:-/opt/taixu/tools/cc-switch}"
echo "[*] Uninstalling CC-Switch Agent Hub..."
rm -f "$TOOL_DIR/bin/cc-switch-daemon"
rm -f "$TOOL_DIR/lib/cc-switch-server"
echo "[+] CC-Switch Agent Hub uninstalled."
