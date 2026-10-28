#!/usr/bin/env python3
"""Run a named Trough scenario and verify its device-signed, request-bound evidence.

The first device public key must be pinned explicitly with --pin-device-key. It is the
existing app key used by the credential transport; first-use pinning is not hardware
attestation. Subsequent results must verify against that local pin. No private key is copied
into shared storage. Runtime discovery uses only the read-only app.observe scenario.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import uuid
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding


class BuildChanged(Exception):
    pass


def atomic(path, data):
    temp = path.with_name(path.name + '.new')
    with temp.open('wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    temp.replace(path)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('scenario', choices=['app.observe', 'core.publication', 'core.resources', 'core.replacement', 'core.store', 'core.documents', 'core.streams', 'core.tasks', 'core.workspace', 'core.drafts', 'core.text', 'core.keeper', 'core.processes', 'build.native'])
    ap.add_argument('--shared', type=Path, default=Path('/mnt/shared/Download/trough'))
    ap.add_argument('--key', type=Path, default=Path.home()/'.trough/hot-key.pem')
    ap.add_argument('--device-key', type=Path, default=Path.home()/'.trough/device-pubkey.der')
    ap.add_argument('--pin-device-key', action='store_true')
    ap.add_argument('--timeout', type=int, help='seconds (default: 240 for build.native, otherwise 45)')
    ap.add_argument('--out', type=Path)
    args = ap.parse_args()
    if args.timeout is None:
        args.timeout = 240 if args.scenario == 'build.native' else 45
    if not 1 <= args.timeout <= 290:
        ap.error('timeout must be 1..290 seconds')
    if not args.device_key.exists():
        if not args.pin_device_key:
            ap.error('device key is not pinned; use --pin-device-key for initial pairing')
        raw = (args.shared/'app-pubkey.der').read_bytes()
        serialization.load_der_public_key(raw)
        args.device_key.parent.mkdir(parents=True, exist_ok=True)
        atomic(args.device_key, raw)
        print('Pinned existing device public key SHA256 ' + hashlib.sha256(raw).hexdigest(), file=__import__('sys').stderr)
    public = serialization.load_der_public_key(args.device_key.read_bytes())
    private = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
    transport = args.shared/'control'
    transport.mkdir(parents=True, exist_ok=True)

    def run(name, bundle='*', runtime='*'):
        now = int(time.time()*1000)
        request = {'version': 1, 'id': uuid.uuid4().hex, 'scenario': name, 'bundle': bundle,
                   'runtime': runtime, 'issued_ms': now, 'expires_ms': now+(args.timeout+10)*1000}
        raw = json.dumps(request, separators=(',', ':')).encode()
        sig = private.sign(raw, ec.ECDSA(hashes.SHA256()))
        atomic(transport/'request.sig', sig)
        atomic(transport/'request.json', raw)
        end = time.monotonic()+args.timeout
        while time.monotonic()<end:
            try:
                # Reject mixed generations during atomic pair publication, then retry.
                body = (transport/'result.json').read_bytes()
                if len(body)>131072:
                    raise ValueError('oversized result')
                proof = (transport/'result.sig').read_bytes()
                public.verify(proof, body, padding.PKCS1v15(), hashes.SHA256())
                result = json.loads(body)
                if result.get('id') != request['id']:
                    time.sleep(.25)
                    continue
                if result.get('request_sha256') != hashlib.sha256(raw).hexdigest():
                    raise ValueError('result/request digest mismatch')
                if result.get('scenario') != name:
                    raise ValueError('result scenario mismatch')
                if name != 'app.observe' and (result.get('bundle') != bundle or result.get('runtime') != runtime):
                    if result.get('status') == 'rejected' and result.get('error') == 'build mismatch':
                        raise BuildChanged()
                    raise ValueError('result build mismatch')
                return result, body, proof
            except (FileNotFoundError, json.JSONDecodeError, __import__('cryptography.exceptions', fromlist=['InvalidSignature']).InvalidSignature):
                pass
            time.sleep(.25)
        raise TimeoutError('no matching authenticated result; request id '+request['id'])

    import fcntl
    # Local writers serialize the single development transport slot. The app is independently
    # serialized and its replay journal persists across reloads.
    lock_path = args.device_key.parent/'scenario.lock'
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.scenario == 'app.observe':
            result, body, proof = run('app.observe')
        else:
            # A reload can land between discovery and the request; the device then rejects the
            # request without executing it, so rediscovering and retrying is safe.
            for attempt in range(5):
                observation, _, _ = run('app.observe')
                if observation.get('status') != 'done':
                    raise RuntimeError('identity discovery failed: '+str(observation.get('status')))
                try:
                    result, body, proof = run(args.scenario, observation['bundle'], observation['runtime'])
                    break
                except BuildChanged:
                    time.sleep(2)
            else:
                raise RuntimeError('running build kept changing; no stable target')
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        atomic(args.out, body)
        atomic(args.out.with_suffix(args.out.suffix+'.sig'), proof)
    print(json.dumps(result, indent=2))
    return 0 if result.get('status') == 'done' else 1


if __name__ == '__main__':
    raise SystemExit(main())
