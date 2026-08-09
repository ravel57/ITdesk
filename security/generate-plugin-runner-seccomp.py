#!/usr/bin/env python3
"""
Build a runner-only seccomp profile from the pinned upstream Moby profile.

Changes are deliberately small:
  * allow the small namespace/mount syscall set bubblewrap needs without
    CAP_SYS_ADMIN so an unprivileged bubblewrap can build its own sandbox;
  * keep defaultAction=SCMP_ACT_ERRNO;
  * block legacy socketcall while preserving the upstream AF_ALG/socket policy.

This profile is applied ONLY to plugin-runner. webapp keeps Docker's builtin
profile unchanged.
"""
import argparse
import hashlib
import json
import os
import pathlib
import tempfile
import urllib.request

PINNED_URL = "https://raw.githubusercontent.com/moby/profiles/refs/tags/seccomp/v0.2.3/seccomp/default.json"
MAX_INPUT = 2 * 1024 * 1024
BWRAP_SYSCALLS = {"clone", "mount", "umount", "umount2", "unshare", "pivot_root", "setns"}


def load_bytes(args):
    if args.input:
        data = pathlib.Path(args.input).read_bytes()
    else:
        req = urllib.request.Request(args.url, headers={"User-Agent": "ULDesk-seccomp-generator/1"})
        with urllib.request.urlopen(req, timeout=20) as response:
            if response.status != 200:
                raise SystemExit(f"HTTP {response.status} while downloading seccomp baseline")
            data = response.read(MAX_INPUT + 1)
    if len(data) > MAX_INPUT:
        raise SystemExit("seccomp baseline is unexpectedly large")
    return data


def unconditional_allow(rule):
    return (
        rule.get("action") == "SCMP_ACT_ALLOW"
        and not rule.get("args")
        and not rule.get("includes")
        and not rule.get("excludes")
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", help="use a local upstream default.json instead of downloading")
    parser.add_argument("--url", default=PINNED_URL)
    parser.add_argument("--output", default="security/plugin-runner-seccomp.json")
    args = parser.parse_args()

    if not args.input and not args.url.startswith("https://"):
        raise SystemExit("refusing to download a seccomp profile over non-HTTPS URL")

    raw = load_bytes(args)
    profile = json.loads(raw)
    if profile.get("defaultAction") != "SCMP_ACT_ERRNO":
        raise SystemExit("unexpected upstream profile: defaultAction is not SCMP_ACT_ERRNO")
    rules = profile.get("syscalls")
    if not isinstance(rules, list):
        raise SystemExit("unexpected upstream profile: syscalls is not a list")

    base_allow = next((r for r in rules if unconditional_allow(r)), None)
    if base_allow is None or not isinstance(base_allow.get("names"), list):
        raise SystemExit("cannot find the unconditional allowlist in upstream profile")

    # Keep Moby's current socket-domain filtering, but remove the legacy
    # socketcall multiplexer so the AF_ALG deny cannot be bypassed on compat
    # paths. The container itself has network_mode:none and each plugin gets a
    # second, private network namespace from bubblewrap.
    filtered_rules = []
    for rule in rules:
        names = rule.get("names")
        if isinstance(names, list):
            names = [n for n in names if n != "socketcall"]
            rule["names"] = names
            if not names:
                continue
        filtered_rules.append(rule)
    profile["syscalls"] = filtered_rules

    base_allow = next(r for r in filtered_rules if unconditional_allow(r))
    names = set(base_allow["names"])
    names.update(BWRAP_SYSCALLS)
    base_allow["names"] = sorted(names)

    all_unconditional = set()
    for rule in filtered_rules:
        if unconditional_allow(rule):
            all_unconditional.update(rule.get("names", []))
    missing = BWRAP_SYSCALLS - all_unconditional
    if missing:
        raise SystemExit(f"generator bug: required bwrap syscalls are not unconditional: {sorted(missing)}")
    if any("socketcall" in r.get("names", []) for r in filtered_rules):
        raise SystemExit("generator bug: socketcall is still allowed")

    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(profile, ensure_ascii=False, indent=2, sort_keys=False) + "\n").encode()
    with tempfile.NamedTemporaryFile(dir=output.parent, prefix=output.name + ".", delete=False) as tmp:
        tmp.write(encoded)
        tmp.flush()
        os.fsync(tmp.fileno())
        tmp_name = tmp.name
    os.chmod(tmp_name, 0o644)
    os.replace(tmp_name, output)

    print(f"source-sha256={hashlib.sha256(raw).hexdigest()}")
    print(f"output-sha256={hashlib.sha256(encoded).hexdigest()}")
    print(f"wrote {output}")


if __name__ == "__main__":
    main()
