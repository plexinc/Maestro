#!/bin/bash
# Uninstalls a maestro CLI installed via scripts/install.sh. Mirrors that script's
# layout: removes $MAESTRO_DIR and strips the PATH export lines it appended to the
# shell profiles. Homebrew-managed installs are left alone.

which_maestro=$(which maestro 2>/dev/null)
if [[ "$which_maestro" == "/usr/local"* || $which_maestro == "/opt/homebrew"* || $which_maestro == "/home/linuxbrew"* ]]; then
  echo "Your maestro installation is managed by Homebrew."
  echo ""
  echo "Uninstall it with:"
  echo ""
  echo "    brew uninstall maestro"
  echo ""
  exit 1
fi

if [ -z "$MAESTRO_DIR" ]; then
    MAESTRO_DIR="$HOME/.maestro"
    MAESTRO_BIN_DIR_RAW='$HOME/.maestro/bin'
else
    MAESTRO_BIN_DIR_RAW="$MAESTRO_DIR/bin"
fi

maestro_bash_profile="${HOME}/.bash_profile"
maestro_bashrc="${HOME}/.bashrc"
maestro_zshrc="${ZDOTDIR:-${HOME}}/.zshrc"

# Remove the installation directory.
if [[ -d "$MAESTRO_DIR" ]]; then
  echo "* Removing $MAESTRO_DIR..."
  rm -rf "${MAESTRO_DIR:?}"
else
  echo "* No installation found at $MAESTRO_DIR"
fi

# Strip the PATH export line install.sh appended (`export PATH=$PATH:<bin dir>`).
path_line="export PATH=\$PATH:${MAESTRO_BIN_DIR_RAW}"
for profile in "$maestro_bash_profile" "$maestro_bashrc" "$maestro_zshrc"; do
  if [[ -f "$profile" ]] && grep -qF "$path_line" "$profile"; then
    echo "* Removing maestro from your PATH in $profile"
    tmp="${profile}.maestro.tmp"
    grep -vF "$path_line" "$profile" > "$tmp" && mv "$tmp" "$profile"
  fi
done

echo ""
echo "Uninstall complete. Open a new terminal for the PATH change to take effect."
