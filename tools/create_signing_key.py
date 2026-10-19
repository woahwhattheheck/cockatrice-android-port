#!/usr/bin/env python3
"""Create a NEW private signing identity, never touch any previous build's keys."""
import os
import secrets
import subprocess
from pathlib import Path

project = Path(__file__).resolve().parents[1]
directory = project / 'signing'
directory.mkdir(mode=0o700, exist_ok=True)
keystore = directory / 'onetap.jks'
properties = directory / 'keystore.properties'
if keystore.exists() or properties.exists():
    raise SystemExit('Signing files already exist; preserving them unchanged.')
password = secrets.token_urlsafe(36)
environment = dict(os.environ, COCKATRICE_SIGNING_PASSWORD=password)
subprocess.run(['keytool', '-genkeypair', '-noprompt', '-keystore', str(keystore),
                '-storetype', 'JKS', '-storepass:env', 'COCKATRICE_SIGNING_PASSWORD',
                '-keypass:env', 'COCKATRICE_SIGNING_PASSWORD', '-alias', 'onetap',
                '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                '-dname', 'CN=Cockatrice One Tap Unofficial'], env=environment, check=True)
keystore.chmod(0o600)
properties.write_text('storeFile=signing/onetap.jks\nkeyAlias=onetap\n'
                      f'storePassword={password}\nkeyPassword={password}\n')
properties.chmod(0o600)
print('New signing key created privately in signing/. Keep a secure backup; do not share it.')
