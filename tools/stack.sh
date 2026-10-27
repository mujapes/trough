#!/bin/bash
# Build the Forge stack payload for Trough: python3.13 + stdlib + native libs + forged's
# package closure + the forge repo (forged/, forge-ui/, tools/), laid out exactly as it will
# sit under /data/data/t.t, every ELF pointed at texec. Output: a zip whose entries are paths
# relative to /data/data/t.t, plus exec.txt listing which entries need the exec bit.
#   tools/stack.sh <out.zip> [stage-dir]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(realpath -m "${1:?usage: stack.sh <out.zip> [stage]}")"
S="${2:-$(mktemp -d)}"
FORGE="${FORGE_REPO:-$HOME/forge}"
VENV_SITE="$FORGE/.venv/lib/python3.13/site-packages"
PY_LIB=/usr/lib/python3.13
SYSLIB=/lib/aarch64-linux-gnu
rm -rf "$S" && mkdir -p "$S/py/bin" "$S/py/lib" "$S/d/usr/lib/aarch64-linux-gnu" "$S/etc/ssl" "$S/forge"

# Interpreter + stdlib (minus tests and GUI toolkits nothing here uses).
cp /usr/bin/python3.13 "$S/py/bin/python3"
tar -C /usr/lib --exclude=test --exclude=tests --exclude=idlelib --exclude=tkinter --exclude=turtledemo \
    --exclude=__pycache__ --exclude=site-packages --exclude=dist-packages -cf - python3.13 | tar -C "$S/py/lib" -xf -

# Native libraries the interpreter and its extension modules link (glibc itself excluded:
# Trough ships its own patched copy in l/).
{ ldd /usr/bin/python3.13; for f in "$PY_LIB"/lib-dynload/*.so; do ldd "$f"; done; } 2>/dev/null \
    | awk '/=>/{print $3}' | sort -u | grep -vE '/(libc|libm|libdl|librt|libpthread)\.so|ld-linux' \
    | while read -r lib; do cp -L "$lib" "$S/d/usr/lib/aarch64-linux-gnu/"; done

# forged's dependency closure, from the venv it runs in today. claude_agent_sdk's bundled CLI
# (208 MB) is dropped: Trough's own claude is on PATH via FORGE_CLAUDE_CLI.
# Debian's python searches dist-packages (site.getsitepackages() under the prefix), not site-packages.
SITE="$S/py/lib/python3/dist-packages"; mkdir -p "$SITE"
# The closure is what forged ACTUALLY imports: metadata alone missed mcp -> cryptography
# (imported, not declared). Import every forged module in the real venv and map the loaded
# modules back to their distributions.
(cd "$FORGE" && "$FORGE/.venv/bin/python" - "$VENV_SITE" "$SITE") <<'EOF'
import importlib, importlib.metadata as m, pkgutil, shutil, sys
from pathlib import Path
src, dst = Path(sys.argv[1]), Path(sys.argv[2])
import forged
for mod in pkgutil.iter_modules(forged.__path__):
    try: importlib.import_module(f"forged.{mod.name}")
    except Exception as e: print(f"  (forged.{mod.name} import failed in VM: {e})", file=sys.stderr)
top = {name.split(".")[0] for name in list(sys.modules)}
owners = m.packages_distributions()
dists = sorted({d for t in top for d in owners.get(t, [])})
copied = 0
for n in dists:
    dist = m.distribution(n)
    for f in dist.files or []:
        p = Path(dist.locate_file(f))
        if not p.is_file() or not p.is_relative_to(src): continue
        rel = p.relative_to(src)
        if "__pycache__" in rel.parts or rel.parts[:2] == ("claude_agent_sdk", "_bundled"): continue
        (dst / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, dst / rel); copied += 1
print(f"site-packages: {len(dists)} distributions actually imported, {copied} files: {' '.join(dists)}", file=sys.stderr)
EOF

# CA roots for Python's ssl (Android has none where OpenSSL looks); SSL_CERT_FILE points here.
cp /etc/ssl/certs/ca-certificates.crt "$S/etc/ssl/ca.crt"

# The forge repo: daemon, UI, tools. No .git, venv, build trees or large binaries.
tar -C "$FORGE" --exclude=__pycache__ -cf - forged forge-ui | tar -C "$S/forge" -xf -
(cd "$FORGE" && find tools -type f -size -5M ! -path '*/__pycache__/*' -print0 | tar --null -T - -cf -) | tar -C "$S/forge" -xf -
cp "$FORGE"/*.md "$S/forge/" 2>/dev/null || true

# Point every ELF at texec, in place; record what needs the exec bit.
python3 "$ROOT/tools/tfix.py" "$S" >&2
(cd "$S" && { echo py/bin/python3; find forge/tools -maxdepth 1 -type f -perm -u+x; } > exec.txt)
rm -f "$OUT"; (cd "$S" && zip -q -r -X "$OUT" .)
echo "stack: $(du -sh "$S" | cut -f1) staged -> $(du -h "$OUT" | cut -f1) zip ($OUT)"
