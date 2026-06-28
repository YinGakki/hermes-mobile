#!/data/data/com.nous.hermes.mobile/files/usr/bin/sh
#
# First-run setup script for Hermes Agent inside the Termux bootstrap
# environment. Called by the Android app after bootstrap extraction,
# or can be run manually from a shell inside the prefix.
#
# Mirrors the official Hermes Termux install guide:
#   https://hermes-agent.nousresearch.com/docs/getting-started/termux
#
# Exit codes:
#   0 = success
#   1 = package install failure
#   2 = pip install failure
#   3 = hermes clone failure

set -eu

PREFIX="${PREFIX:-/data/data/com.nous.hermes.mobile/files/usr}"
HOME_DIR="${HOME:-/data/data/com.nous.hermes.mobile/files/home}"

echo "[setup] Updating package index..."
apt-get update -y || {
    echo "[setup] WARNING: apt-get update failed, continuing anyway"
}

echo "[setup] Installing Hermes system packages..."
apt-get install -y \
    git python clang rust make pkg-config libffi openssl \
    nodejs ripgrep ffmpeg \
    || {
    echo "[setup] ERROR: Failed to install Hermes system packages"
    exit 1
}

echo "[setup] Python version: $(python --version 2>&1)"
echo "[setup] cargo version: $(cargo --version 2>&1)"

echo "[setup] Cloning Hermes Agent..."
if [ ! -d "$HOME_DIR/hermes-agent" ]; then
    git clone --depth 1 https://github.com/NousResearch/hermes-agent.git "$HOME_DIR/hermes-agent" || {
        echo "[setup] ERROR: Failed to clone hermes-agent"
        exit 3
    }
fi

cd "$HOME_DIR/hermes-agent"

echo "[setup] Creating Python venv..."
python -m venv .venv
. .venv/bin/activate

echo "[setup] Upgrading pip..."
pip install --upgrade pip

echo "[setup] Installing Hermes (editable + termux extras)..."
pip install -e '.[termux]' -c constraints-termux.txt || {
    echo "[setup] ERROR: pip install hermes failed"
    exit 2
}

echo "[setup] Hermes version: $(hermes --version 2>&1 || echo 'installed')"

# Link hermes onto prefix PATH
if [ ! -f "$PREFIX/bin/hermes" ]; then
    HERMES_BIN="$(which hermes 2>/dev/null)"
    if [ -n "$HERMES_BIN" ]; then
        cat > "$PREFIX/bin/hermes" << WEOF
#!/data/data/com.nous.hermes.mobile/files/usr/bin/sh
exec $HERMES_BIN "\$@"
WEOF
        chmod 700 "$PREFIX/bin/hermes"
    fi
fi

echo "[setup] Setup complete!"
echo ""
echo "Next steps:"
echo "  1. Run: hermes setup --portal   (free Nous Portal OAuth login)"
echo "  2. Then: hermes"
