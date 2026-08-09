#!/bin/sh
set -eu

PROFILE_NAME=uldesk-plugin-runner
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE="$SCRIPT_DIR/$PROFILE_NAME.apparmor"
TARGET="/etc/apparmor.d/$PROFILE_NAME"

if [ "$(id -u)" -ne 0 ]; then
  exec sudo "$0" "$@"
fi

if [ ! -e /sys/module/apparmor/parameters/enabled ] || ! grep -q '^Y' /sys/module/apparmor/parameters/enabled; then
  echo "AppArmor is not enabled on this host; refusing to silently run the runner without the dedicated LSM profile." >&2
  exit 1
fi

if ! command -v apparmor_parser >/dev/null 2>&1; then
  echo "apparmor_parser is required. Install the host AppArmor userspace package first." >&2
  exit 1
fi

# Fail before replacing the installed profile if this host cannot parse the
# userns/mount rules required for the dedicated runner.
apparmor_parser -Q "$SOURCE"

install -o root -g root -m 0644 "$SOURCE" "$TARGET"
apparmor_parser -r -W "$TARGET"

if [ -r /sys/kernel/security/apparmor/profiles ]; then
  if ! grep -q "^$PROFILE_NAME " /sys/kernel/security/apparmor/profiles; then
    echo "Profile was parsed but is not visible in the AppArmor profile list." >&2
    exit 1
  fi
fi

echo "Loaded AppArmor profile: $PROFILE_NAME"
