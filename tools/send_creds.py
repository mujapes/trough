#!/usr/bin/env python3
"""Encrypt the Claude login to Trough's public key (format: hot/.../Creds.java).

Only claudeAiOauth is sent -- not the other tokens .credentials.json may hold (MCP servers).
Reads Download/trough/app-pubkey.der (published by Trough), writes Download/trough/creds.bin;
Trough imports it and deletes the shared copy.

Usage: send_creds.py [--shared DIR] [--creds FILE]
"""
import argparse
import json
import os
import struct
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ap = argparse.ArgumentParser()
ap.add_argument("--shared", default="/mnt/shared/Download/trough")
ap.add_argument("--creds", default=str(Path.home() / ".claude/.credentials.json"))
a = ap.parse_args()

pub = serialization.load_der_public_key(Path(a.shared, "app-pubkey.der").read_bytes())
login = {"claudeAiOauth": json.loads(Path(a.creds).read_text())["claudeAiOauth"]}
plain = json.dumps(login).encode()

aes = AESGCM.generate_key(bit_length=256)
iv = os.urandom(12)
wrapped = pub.encrypt(aes, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
blob = struct.pack(">H", len(wrapped)) + wrapped + iv + AESGCM(aes).encrypt(iv, plain, None)

tmp = Path(a.shared, ".creds.bin.tmp")
tmp.write_bytes(blob)
tmp.rename(Path(a.shared, "creds.bin"))
print(f"wrote creds.bin ({len(blob)} bytes, {len(plain)} plaintext) for Trough's key")
