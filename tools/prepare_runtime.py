#!/usr/bin/env python3
"""Build a relocatable, allowlisted runtime without changing the original rootfs.

No apt operations, user settings, cached pictures, shell history or private keys
are copied. ELF dependencies are resolved from the same Debian installation as
the selected Cockatrice binary; mixing the old Ubuntu binary into Debian was one
of the original standalone build's failures.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import posixpath
import re
import shutil
import struct
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]


def elf_info(data: bytes) -> dict | None:
    if not data.startswith(b"\x7fELF"):
        return None
    if data[4:6] != b"\x02\x01":
        raise ValueError("Only little-endian ELF64 is supported")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
    if header[2] != 183:
        raise ValueError(f"Expected AArch64, found ELF machine {header[2]}")
    segments = [struct.unpack_from("<IIQQQQQQ", data, header[5] + n * header[9])
                for n in range(header[10])]
    dynamic = []
    interpreter = None
    for typ, flags, offset, address, physical, size, memory, align in segments:
        if typ == 3:
            interpreter = data[offset:offset + size].rstrip(b"\0").decode()
        if typ == 2:
            for at in range(offset, offset + size, 16):
                tag, value = struct.unpack_from("<qQ", data, at)
                if tag == 0:
                    break
                dynamic.append((tag, value))
    string_address = next((v for t, v in dynamic if t == 5), None)
    string_offset = None
    if string_address is not None:
        for typ, flags, offset, address, physical, size, memory, align in segments:
            if typ == 1 and address <= string_address < address + size:
                string_offset = offset + string_address - address
                break
    strings = []
    if string_offset is not None:
        for tag, value in dynamic:
            if tag in {1, 14, 15, 29}:
                start = string_offset + value
                end = data.index(b"\0", start)
                strings.append((tag, data[start:end].decode(), start))
    return {"needed": [s for t, s, o in strings if t == 1],
            "paths": [s for t, s, o in strings if t in {15, 29}],
            "strings": strings, "interpreter": interpreter}


def patch_native(data: bytes, renames: dict[str, str]) -> bytes:
    """Shorten dynamic strings in place; never mutate an installed Termux file."""
    info = elf_info(data)
    result = bytearray(data)
    for tag, text, offset in info["strings"]:
        replacement = "$ORIGIN" if tag in {15, 29} else renames.get(text, text)
        if len(replacement) > len(text):
            raise ValueError(f"Replacement grows ELF string: {text}")
        result[offset:offset + len(text)] = replacement.encode().ljust(len(text), b"\0")
    return bytes(result)


class RuntimeBuilder:
    def __init__(self, source: Path, destination: Path):
        self.source = source.resolve()
        self.destination = destination
        self.files: dict[str, dict] = {}
        self.elf_seen: set[str] = set()
        self.source_paths: set[str] = set()

    def source_file(self, guest: str) -> tuple[Path, str]:
        """Resolve guest absolute links, including legacy PRoot .l2s links."""
        parts = posixpath.normpath("/" + guest.lstrip("/")).strip("/").split("/")
        resolved: list[str] = []
        hops = 0
        while parts:
            component = parts.pop(0)
            if component in {"", "."}:
                continue
            if component == "..":
                if resolved:
                    resolved.pop()
                continue
            candidate = self.source.joinpath(*resolved, component)
            if candidate.is_symlink():
                hops += 1
                if hops > 64:
                    raise ValueError(f"Symlink loop in {guest}")
                target = os.readlink(candidate)
                if target.startswith(str(self.source) + "/"):
                    target = target[len(str(self.source)):]
                elif "/installed-rootfs/debian/" in target:
                    target = "/" + target.split("/installed-rootfs/debian/", 1)[1]
                if target.startswith("/"):
                    resolved = []
                parts = target.split("/") + parts
            else:
                resolved.append(component)
        relative = "/".join(resolved)
        return self.source / relative, relative

    @staticmethod
    def output_name(guest: str) -> str:
        path = posixpath.normpath("/" + guest.lstrip("/")).lstrip("/")
        for alias in ("bin", "sbin", "lib"):
            if path.startswith(alias + "/"):
                return "usr/" + path
        return path

    def write(self, name: str, data: bytes, mode: int = 0o644, source: str | None = None):
        name = self.output_name(name)
        if name in self.files:
            if hashlib.sha256(data).hexdigest() != self.files[name]["sha256"]:
                raise ValueError(f"Conflicting runtime file {name}")
            return
        out = self.destination / name
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)
        out.chmod(mode)
        self.files[name] = {"size": len(data), "mode": mode,
                            "sha256": hashlib.sha256(data).hexdigest()}
        if source:
            self.source_paths.add(source)

    def add(self, guest: str, destination: str | None = None):
        path, original = self.source_file(guest)
        if not path.is_file():
            raise FileNotFoundError(f"Missing runtime input {guest} ({path})")
        data = path.read_bytes()
        mode = 0o755 if data.startswith((b"\x7fELF", b"#!")) else 0o644
        self.write(destination or guest, data, mode, original)

    def tree(self, guest: str, destination: str | None = None):
        path, original = self.source_file(guest)
        if not path.exists():
            raise FileNotFoundError(guest)
        destination = destination or guest
        for entry in sorted(path.iterdir()):
            src = guest.rstrip("/") + "/" + entry.name
            dest = destination.rstrip("/") + "/" + entry.name
            target, _ = self.source_file(src)
            if target.is_dir():
                self.tree(src, dest)
            elif target.is_file():
                self.add(src, dest)

    def add_elf(self, guest: str, destination: str | None = None):
        destination = self.output_name(destination or guest)
        self.add(guest, destination)
        if destination in self.elf_seen:
            return
        self.elf_seen.add(destination)
        source, _ = self.source_file(guest)
        info = elf_info(source.read_bytes())
        if info is None:
            return
        if info["interpreter"]:
            self.add_elf(info["interpreter"])
        folders = ["/usr/lib/aarch64-linux-gnu", "/usr/lib", "/usr/local/lib",
                   "/usr/lib/aarch64-linux-gnu/pulseaudio"]
        for group in info["paths"]:
            for folder in group.split(":"):
                folder = folder.replace("${ORIGIN}", posixpath.dirname(guest))
                folder = folder.replace("$ORIGIN", posixpath.dirname(guest))
                if folder.startswith("/"):
                    folders.insert(0, folder)
        for name in info["needed"]:
            for folder in folders:
                candidate = folder.rstrip("/") + "/" + name
                if self.source_file(candidate)[0].is_file():
                    self.add_elf(candidate)
                    break
            else:
                raise FileNotFoundError(f"{guest} needs missing library {name}")

    def resolve_all_elf(self):
        # Tree-copied Qt plugins also need their dlopen-only dependencies.
        for name in list(self.files):
            if name not in self.elf_seen and (self.destination / name).read_bytes()[:4] == b"\x7fELF":
                if self.source_file(name)[0].is_file():
                    self.add_elf(name)


def package_metadata(builder: RuntimeBuilder) -> list[dict]:
    info_dir = builder.source / "var/lib/dpkg/info"
    owners = set()
    for listing in info_dir.glob("*.list"):
        paths = {line.lstrip("/") for line in listing.read_text().splitlines()}
        if paths & builder.source_paths:
            owners.add(listing.name[:-5])
    status = (builder.source / "var/lib/dpkg/status").read_text()
    packages = []
    for block in status.split("\n\n"):
        fields = dict(line.split(": ", 1) for line in block.splitlines()
                      if not line.startswith(" ") and ": " in line)
        name = fields.get("Package", "")
        architecture = fields.get("Architecture", "")
        if name in owners or f"{name}:{architecture}" in owners:
            packages.append({k: fields[k] for k in ["Package", "Version", "Source", "Architecture"] if k in fields})
            copyright_path = f"/usr/share/doc/{name}/copyright"
            if builder.source_file(copyright_path)[0].is_file():
                builder.add(copyright_path)
    return sorted(packages, key=lambda p: p["Package"])


def build(args):
    assets = PROJECT / "app/build/generated/standalone-assets"
    natives = PROJECT / "app/build/generated/standalone-jni/arm64-v8a"
    root = PROJECT / "build/runtime-root"
    # Only generated output under this new project is replaced.
    for output in [assets, natives, root]:
        if output.exists():
            shutil.rmtree(output)
        output.mkdir(parents=True)
    builder = RuntimeBuilder(args.rootfs, root)
    builder.add_elf(args.cockatrice, "/usr/bin/cockatrice")
    expected_game = (PROJECT / "runtime/COCKATRICE.sha256").read_text().split()[0]
    if builder.files["usr/bin/cockatrice"]["sha256"] != expected_game:
        raise ValueError("Cockatrice differs from the owner's original executable; do not patch or rebuild the game")
    app_build = posixpath.dirname(args.cockatrice)
    builder.add_elf(posixpath.dirname(app_build) + "/oracle/oracle", "/usr/bin/oracle")
    for name in ["dash", "env", "true", "sleep", "mkdir", "rm", "chmod", "grep",
                 "Xvfb", "x11vnc", "fluxbox", "xdpyinfo", "xwininfo"]:
        builder.add_elf("/usr/bin/" + name)
    builder.add("/usr/bin/dash", "/usr/bin/sh")
    plugins = "/usr/lib/aarch64-linux-gnu/qt5/plugins"
    for plugin in ["platforms/libqxcb.so", "platforms/libqoffscreen.so"]:
        builder.add_elf(plugins + "/" + plugin)
    for folder in ["imageformats", "iconengines", "bearer", "printsupport"]:
        builder.tree(plugins + "/" + folder)
    for folder in ["/usr/share/X11/xkb", "/usr/share/fonts/X11/misc",
                   "/usr/share/fonts/X11/encodings", "/usr/share/fonts/truetype/dejavu",
                   "/usr/share/fluxbox", "/etc/X11/fluxbox", "/etc/fonts", "/usr/share/fontconfig",
                   "/usr/lib/locale/C.utf8"]:
        builder.tree(folder)
    for name in ["libnss_dns.so.2", "libnss_files.so.2", "libssl.so.3", "libcrypto.so.3"]:
        builder.add_elf("/usr/lib/aarch64-linux-gnu/" + name)
    builder.resolve_all_elf()
    # Xvfb compiles keyboard maps by launching xkbcomp.
    builder.add_elf("/usr/bin/xkbcomp")
    certs = builder.source / "usr/share/ca-certificates/mozilla"
    ca_data = b"".join(p.read_bytes() + b"\n" for p in sorted(certs.glob("*.crt")))
    if not ca_data:
        raise ValueError("No Mozilla CA certificates found")
    builder.write("/etc/ssl/certs/ca-certificates.crt", ca_data)
    builder.write("/usr/lib/ssl/cert.pem", ca_data)
    builder.write("/etc/passwd", b"root:x:0:0:Cockatrice:/root:/bin/sh\n")
    builder.write("/etc/group", b"root:x:0:\n")
    builder.write("/etc/nsswitch.conf", b"hosts: files dns\npasswd: files\ngroup: files\n")
    builder.write("/etc/hosts", b"127.0.0.1 localhost\n::1 localhost\n")
    builder.write("/etc/resolv.conf", b"nameserver 1.1.1.1\n")
    builder.write("/etc/machine-id", b"00000000000000000000000000000000\n")
    builder.write("/etc/localtime", (builder.source / "usr/share/zoneinfo/Etc/UTC").read_bytes())
    builder.write("/opt/cockatrice/start-session.sh", (PROJECT / "runtime/start-session.sh").read_bytes(), 0o755)
    builder.write("/opt/cockatrice/fluxbox/init", (PROJECT / "runtime/fluxbox-init").read_bytes())
    builder.write("/opt/cockatrice/fluxbox/apps", (PROJECT / "runtime/fluxbox-apps").read_bytes())
    builder.write("/opt/cockatrice/fluxbox/menu", b"[begin] (Cockatrice)\n[end]\n")
    for name in ["cards.xml", "tokens.xml"]:
        builder.add("/root/.local/share/Cockatrice/Cockatrice/" + name, "/opt/cockatrice/seed/" + name)
    version_source = builder.source_file(posixpath.dirname(app_build) + "/version_string.cpp")[0].read_text()
    version = re.search(r'VERSION_STRING = "([^"]+)"', version_source).group(1)
    defaults = ("[personal]\nclientversion=" + version + "\nstartupUpdateCheck=false\n"
                "startupCardUpdateCheckPromptForUpdate=false\nstartupCardUpdateCheckAlwaysUpdate=false\n"
                "alwaysEnableNewSets=true\nnewversionnotification=false\npixmapCacheSize=128\n"
                "[revert]\npixmapCacheSize=true\n[tipOfDay]\nshowTips=false\n[sound]\nenabled=false\n")
    builder.write("/opt/cockatrice/seed/settings/global.ini", defaults.encode())
    download_defaults = ("[downloads]\nurls=https://api.scryfall.com/cards/!set:uuid!?format=image&face=!prop:side!, "
                         "https://api.scryfall.com/cards/multiverse/!set:muid!?format=image, "
                         "https://gatherer.wizards.com/Handlers/Image.ashx?multiverseid=!set:muid!&type=card, "
                         "https://gatherer.wizards.com/Handlers/Image.ashx?name=!name!&type=card\n")
    builder.write("/opt/cockatrice/seed/settings/downloads.ini", download_defaults.encode())
    sets = []
    card_xml = root / "opt/cockatrice/seed/cards.xml"
    for event, element in ET.iterparse(card_xml, events=("end",)):
        if element.tag == "set":
            name = element.findtext("name")
            if name:
                if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
                    raise ValueError(f"Unsupported set code for INI seed: {name!r}")
                sets.append(name)
            element.clear()
        elif element.tag == "card":
            element.clear()
    set_defaults = "[sets]\n" + "".join(f"{name}\\enabled=true\n{name}\\isknown=true\n{name}\\sortkey={i}\n" for i, name in enumerate(sets))
    builder.write("/opt/cockatrice/seed/settings/cardDatabase.ini", set_defaults.encode())
    # Only shipped application resources, never the developer's user data.
    for name in ["sounds", "themes"]:
        builder.tree(app_build + "/" + name, "/usr/share/cockatrice/" + name)
    for translation in sorted(builder.source_file(app_build)[0].glob("*.qm")):
        builder.add(app_build + "/" + translation.name, "/usr/share/cockatrice/translations/" + translation.name)
    builder.add("/root/Cockatrice/LICENSE", "/usr/share/doc/cockatrice/LICENSE")
    packages = package_metadata(builder)

    renames = {"libtalloc.so.2": "libtalloc.so"}
    native_sources = {"libproot.so": args.prefix / "bin/proot",
                      "libloader.so": args.prefix / "libexec/proot/loader",
                      "libtalloc.so": args.prefix / "lib/libtalloc.so",
                      "libandroid-shmem.so": args.prefix / "lib/libandroid-shmem.so"}
    native_hashes = {}
    for name, source in native_sources.items():
        data = patch_native(source.read_bytes(), renames)
        target = natives / name
        target.write_bytes(data)
        target.chmod(0o755)
        native_hashes[name] = hashlib.sha256(data).hexdigest()
        info = elf_info(data)
        missing = set(info["needed"]) - set(native_sources) - {"libc.so", "liblog.so", "libdl.so", "libm.so"}
        if missing:
            raise ValueError(f"Unbundled native dependencies for {name}: {missing}")

    symlinks = {"bin": "usr/bin", "sbin": "usr/sbin", "lib": "usr/lib"}
    directories = ["dev", "proc", "sys", "run", "run/user", "tmp", "tmp/.X11-unix", "root", "usr/sbin"]
    for name in directories:
        (root / name).mkdir(parents=True, exist_ok=True)
    for name, target in symlinks.items():
        (root / name).symlink_to(target)
    archive = assets / "runtime.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for name in sorted(builder.files):
            entry = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            entry.external_attr = (0o100000 | builder.files[name]["mode"]) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(entry, (root / name).read_bytes(), compresslevel=9)
    metadata = {"format": 1, "architecture": "arm64-v8a", "version": "onetap-1",
                "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                "unpackedBytes": sum(f["size"] for f in builder.files.values()),
                "fileCount": len(builder.files), "directories": directories,
                "symlinks": symlinks, "files": builder.files, "nativeFiles": native_hashes,
                "cockatriceVersion": version, "seededCardSets": len(sets),
                "cockatriceCommit": subprocess.check_output(["git", "-C", str(builder.source / "root/Cockatrice"), "rev-parse", "HEAD"], text=True).strip(),
                "debianPackages": packages}
    (assets / "runtime.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    print(f"Runtime: {len(builder.files)} files, {metadata['unpackedBytes']/1024**2:.1f} MiB unpacked, {archive.stat().st_size/1024**2:.1f} MiB compressed")
    print(f"Bundled {len(packages)} Debian package copyright records; no personal settings or images")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rootfs", type=Path, default=os.environ.get("COCKATRICE_ROOTFS"), help="Prepared ARM64 Debian rootfs, or COCKATRICE_ROOTFS")
    parser.add_argument("--cockatrice", default="/root/Cockatrice/build/cockatrice/cockatrice")
    parser.add_argument("--prefix", type=Path, default=os.environ.get("COCKATRICE_TERMUX_PREFIX", os.environ.get("PREFIX")), help="ARM64 Termux binary prefix, or COCKATRICE_TERMUX_PREFIX/PREFIX")
    args = parser.parse_args()
    if args.rootfs is None or not args.rootfs.is_dir():
        parser.error("Supply an existing --rootfs or set COCKATRICE_ROOTFS")
    if args.prefix is None or not (args.prefix / "bin/proot").is_file():
        parser.error("Supply an ARM64 Termux --prefix or set COCKATRICE_TERMUX_PREFIX/PREFIX")
    build(args)
