"""Build a scoped v2 offline bundle from operator-supplied podspec JSON files. No network."""
import argparse
import hashlib
import json
import re
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit


def build(manifest_path, output):
    manifest_path = Path(manifest_path).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(manifest, list) or not 1 <= len(manifest) <= 1000:
        raise ValueError("Manifest must contain 1–1000 spec entries")
    records, identities = [], set()
    for item in manifest:
        spec_path = manifest_path.parent / item["file"]
        with spec_path.open("rb") as source:
            raw = source.read(65537)
        if len(raw) > 65536:
            raise ValueError("Spec exceeds 64 KiB")
        text = raw.decode("utf-8")
        spec = json.loads(text)
        name, version, origin = spec["name"], spec["version"], item["origin"]
        for value, maximum in [(name, 200), (version, 100)]:
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_+.-]{1," + str(maximum) + "}", value) or value in (".", ".."):
                raise ValueError("Invalid spec identity")
        url = urlsplit(origin)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment or len(origin) > 2000:
            raise ValueError("Origin must be an HTTPS provenance URL without credentials/query/fragment")
        if (name, version) in identities:
            raise ValueError("Duplicate pod/version")
        identities.add((name, version))
        records.append(dict(name=name, version=version, origin=origin, sha256=hashlib.sha256(raw).hexdigest(), podspec=text))
    data = ("\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n").encode("utf-8")
    meta = dict(format="oswl-vdb", formatVersion=2, mode="full", builder="oswl-scoped-podspecs",
                builtAt=datetime.now(timezone.utc).replace(tzinfo=None).isoformat(),
                sources={"cocoapods-specs": {"records": len(records), "origin": "operator-supplied-podspecs"}},
                files={"cocoapods-specs.jsonl": {"sha256": hashlib.sha256(data).hexdigest(), "lines": len(records)}})
    with zipfile.ZipFile(output, "x", zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr("meta.json", json.dumps(meta))
        bundle.writestr("cocoapods-specs.jsonl", data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", help="JSON array of {file, origin}; file paths relative to this manifest")
    parser.add_argument("output", help="New ZIP path (existing files are not overwritten)")
    args = parser.parse_args()
    build(args.manifest, args.output)
