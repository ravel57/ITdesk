#!/bin/sh
set -eu

cd "$(dirname "$0")/.."

if [ -n "${MOBY_SECCOMP_INPUT:-}" ]; then
  python3 security/generate-plugin-runner-seccomp.py --input "$MOBY_SECCOMP_INPUT"
else
  python3 security/generate-plugin-runner-seccomp.py
fi
security/install-plugin-runner-apparmor.sh

mkdir -p plugins logs webapp-logs
if [ "$(id -u)" -eq 0 ]; then
  CHOWN="chown"
else
  CHOWN="sudo chown"
fi
$CHOWN -R 10001:10000 plugins logs webapp-logs

printf '%s\n' "Prepared plugin runtime host directories, runner seccomp and AppArmor profiles."
printf '%s\n' "Next: docker compose build --no-cache webapp plugin-runner plugin-ipc-init"
printf '%s\n' "Then: docker compose up -d"
