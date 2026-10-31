#!/usr/bin/env python3
"""Export a signed native-viewer APK, its checksums and runtime manifest.

This command does not publish a release. Binary redistribution must also satisfy
the licenses and corresponding-source requirements of the bundled runtime.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import subprocess
import zipfile
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]


def require(condition: bool, message: str):
    if not condition:
        raise SystemExit(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def export(args):
    apk = args.apk or PROJECT / 'app/build/outputs/apk/release/app-release.apk'
    require(apk.is_file(), 'Signed release APK not found. Create a signing key and run bash tools/build.sh first.')
    manifest_path = PROJECT / 'app/build/generated/standalone-assets/runtime.json'
    metadata = json.loads(manifest_path.read_text())
    subprocess.run(['apksigner', 'verify', '--verbose', str(apk)], check=True)
    with zipfile.ZipFile(apk) as package:
        for name, expected in metadata['nativeFiles'].items():
            require(hashlib.sha256(package.read('lib/arm64-v8a/' + name)).hexdigest() == expected,
                    f'Installed native binary changed during packaging: {name}')
        payload = package.read('assets/runtime.zip')
        require(hashlib.sha256(payload).hexdigest() == metadata['sha256'], 'Runtime payload checksum mismatch')
        expected_game = (PROJECT / 'runtime/COCKATRICE.sha256').read_text().split()[0]
        with zipfile.ZipFile(io.BytesIO(payload)) as runtime:
            actual_game = hashlib.sha256(runtime.read('usr/bin/cockatrice')).hexdigest()
        require(actual_game == expected_game, 'The pinned Cockatrice executable must not change')
        dex = b''.join(package.read(name) for name in package.namelist() if re.fullmatch(r'classes\d*\.dex', name))
        require(b'Landroid/webkit/WebView;' not in dex, 'The native launcher must not load WebView')
        require(b'NanoHTTPD' not in dex and b'LocalVncServer' not in dex, 'Unused browser bridge remains in the APK')
        require(not any(name.startswith('assets/web/') or 'debian_payload' in name for name in package.namelist()),
                'Legacy browser assets or old payloads remain in the APK')
        require(package.getinfo('assets/runtime.zip').compress_type == zipfile.ZIP_STORED,
                'Runtime ZIP should not be compressed a second time by APK packaging')
    badging = subprocess.check_output(['aapt', 'dump', 'badging', str(apk)], text=True)
    require("package: name='org.cockatrice.onetap'" in badging, 'Unexpected application ID')
    require('application-debuggable' not in badging, 'Release APK must not be debuggable')
    version = re.search(r"versionName='([^']+)'", badging).group(1)
    output = PROJECT / 'dist'
    output.mkdir(exist_ok=True)
    name = f'Cockatrice-Mobile-{version}-arm64.apk'
    target = output / name
    checksum = sha256(apk)
    require(not target.exists() or sha256(target) == checksum,
            'A different APK is already exported under this version. Preserve it and use a new version.')
    shutil.copyfile(apk, target)
    (output / (name + '.sha256')).write_text(f'{checksum}  {name}\n')
    information = {
        'applicationId': 'org.cockatrice.onetap', 'version': version,
        'architecture': 'arm64-v8a', 'minSdk': 26, 'targetSdk': 34,
        'apk': name, 'apkBytes': target.stat().st_size, 'apkSha256': checksum,
        'runtimeSha256': metadata['sha256'], 'cockatriceExecutableSha256': actual_game,
        'cockatriceVersion': metadata['cockatriceVersion'], 'cockatriceCommit': metadata['cockatriceCommit'],
        'display': 'native Android framebuffer viewer; no WebView',
    }
    (output / (name + '.json')).write_text(json.dumps(information, indent=2) + '\n')
    shutil.copyfile(manifest_path, output / f'Runtime-Manifest-{version}.json')
    if args.downloads:
        require(args.downloads.is_dir(), 'The requested Downloads directory does not exist')
        delivered = args.downloads / name
        require(not delivered.exists() or sha256(delivered) == checksum, 'Refusing to overwrite a different Downloads file')
        shutil.copyfile(target, delivered)
        shutil.copyfile(output / (name + '.sha256'), args.downloads / (name + '.sha256'))
        print(f'Copied to {delivered}')
    print(f'Exported {target} ({target.stat().st_size / 1024**2:.2f} MiB)')
    print(f'SHA-256 {checksum}; original Cockatrice executable unchanged')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, help='Signed APK to export; defaults to the Gradle release output')
    parser.add_argument('--downloads', type=Path, help='Optionally also copy the APK/checksum to an existing Downloads directory')
    export(parser.parse_args())
