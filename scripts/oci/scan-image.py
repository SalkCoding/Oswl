"""Inspect a pinned OCI image without running it; emit installed OS packages as an OsWL payload."""
import argparse
import base64
import gzip
import hashlib
import io
import json
import posixpath
import re
import shutil
import sys
import tarfile
import tempfile
import time
from datetime import datetime, timezone
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

class InspectionError(ValueError):
    pass


TRACKED = {"etc/os-release", "usr/lib/os-release", "var/lib/dpkg/status", "lib/apk/db/installed"}
ACCEPT = ", ".join(["application/vnd.oci.image.index.v1+json", "application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json", "application/vnd.docker.distribution.manifest.v2+json"])


def digest(value):
    if not isinstance(value, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", value):
        raise InspectionError("A sha256 digest is required")
    return value.split(":", 1)[1]


class Budget:
    def __init__(self, seconds=120, compressed=512*1024**2, expanded=2*1024**3):
        self.deadline = time.monotonic() + seconds
        self.compressed, self.expanded = compressed, expanded

    def check(self):
        if time.monotonic() > self.deadline:
            raise InspectionError("Image inspection time budget exceeded")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Registry:
    def __init__(self, host, repository, budget, credentials=None):
        if not re.fullmatch(r"[a-zA-Z0-9.-]+(?::[0-9]{1,5})?", host) or not re.fullmatch(r"[a-z0-9._/-]+", repository) or ".." in repository or repository.startswith("/"):
            raise InspectionError("Invalid registry or repository")
        self.host, self.repository, self.budget = host, repository, budget
        self.credentials, self.token = credentials, None
        self.opener = urllib.request.build_opener(NoRedirect())

    def authenticate(self, challenge):
        if not challenge.lower().startswith("bearer "):
            raise InspectionError("Registry requires unsupported authentication")
        fields = urllib.request.parse_keqv_list(urllib.request.parse_http_list(challenge[7:]))
        realm = urllib.parse.urlsplit(fields.get("realm", ""))
        allowed = {self.host}
        if self.host == "registry-1.docker.io":
            allowed.add("auth.docker.io")
        if realm.scheme != "https" or realm.netloc not in allowed or realm.username or realm.fragment:
            raise InspectionError("Registry authentication realm is not trusted")
        query = urllib.parse.urlencode({"service": fields.get("service", self.host), "scope": "repository:" + self.repository + ":pull"})
        url = urllib.parse.urlunsplit((realm.scheme, realm.netloc, realm.path, query, ""))
        headers = {}
        if self.credentials:
            raw = (self.credentials["username"] + ":" + self.credentials["password"]).encode()
            headers["Authorization"] = "Basic " + base64.b64encode(raw).decode()
        # Credentials never follow a redirect, including at the token service.
        with self.opener.open(urllib.request.Request(url, headers=headers), timeout=15) as response:
            raw = response.read(65537)
        if len(raw) > 65536:
            raise InspectionError("Registry token response exceeds its budget")
        token = json.loads(raw)
        self.token = token.get("token") or token.get("access_token")
        if not isinstance(self.token, str) or len(self.token) > 16384:
            raise InspectionError("Invalid registry token response")

    def fetch(self, kind, reference, target, limit):
        url = "https://" + self.host + "/v2/" + self.repository + "/" + kind + "/" + reference
        authenticated = False
        for _ in range(6):
            self.budget.check()
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme != "https" or parsed.username or parsed.fragment:
                raise InspectionError("Unsafe registry redirect")
            headers = {"Accept": ACCEPT}
            if self.token and parsed.netloc == self.host:
                headers["Authorization"] = "Bearer " + self.token
            try:
                response = self.opener.open(urllib.request.Request(url, headers=headers), timeout=15)
            except urllib.error.HTTPError as error:
                if error.code == 401 and parsed.netloc == self.host and not authenticated:
                    self.authenticate(error.headers.get("WWW-Authenticate", "")); authenticated = True
                    error.close(); continue
                if error.code in (301, 302, 303, 307, 308):
                    url = urllib.parse.urljoin(url, error.headers.get("Location", "")); error.close(); continue
                error.close()
                raise InspectionError("Registry request failed with HTTP " + str(error.code)) from None
            total = 0
            with response, target.open("wb") as output:
                while True:
                    self.budget.check()
                    block = response.read(65536)
                    if not block: break
                    total += len(block); self.budget.compressed -= len(block)
                    if total > limit or self.budget.compressed < 0: raise InspectionError("Compressed image budget exceeded")
                    output.write(block)
            return
        raise InspectionError("Too many registry redirects")


class ImageSource:
    def __init__(self, workspace, budget, registry=None, layout=None):
        self.workspace, self.budget, self.registry = Path(workspace), budget, registry
        self.layout = Path(layout).resolve() if layout else None
        self.cache = {}

    def blob(self, reference, kind="blobs", limit=512*1024**2):
        name = digest(reference)
        if reference in self.cache: return self.cache[reference]
        target = self.workspace / name
        if self.layout:
            source = (self.layout / "blobs" / "sha256" / name).resolve()
            if not source.is_relative_to(self.layout): raise InspectionError("Blob escapes OCI layout")
            if source.stat().st_size > limit: raise InspectionError("Blob exceeds its budget")
            self.budget.compressed -= source.stat().st_size
            if self.budget.compressed < 0: raise InspectionError("Compressed image budget exceeded")
            shutil.copyfile(source, target)
        else:
            self.registry.fetch(kind, reference, target, limit)
        with target.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != name: raise InspectionError("Image blob checksum mismatch")
        self.cache[reference] = target
        return target

    def document(self, reference, kind="blobs"):
        return json.loads(self.blob(reference, kind, 4*1024**2).read_bytes())


class ExpandedReader:
    def __init__(self, stream, budget):
        self.stream, self.budget, self.hash = stream, budget, hashlib.sha256()
    def read(self, size=-1):
        self.budget.check()
        if size < 0: size = 65536
        data = self.stream.read(size)
        self.budget.expanded -= len(data)
        if self.budget.expanded < 0: raise InspectionError("Expanded image budget exceeded")
        self.hash.update(data)
        return data


def path_name(raw):
    if "\\" in raw or raw.startswith("/") or ".." in raw.split("/"):
        raise InspectionError("Unsafe layer path")
    return posixpath.normpath(raw)


def apply_layer(path, media_type, expected_diff_id, state, budget):
    digest(expected_diff_id)
    if media_type not in ("application/vnd.oci.image.layer.v1.tar", "application/vnd.oci.image.layer.v1.tar+gzip", "application/vnd.docker.image.rootfs.diff.tar.gzip"):
        raise InspectionError("Unsupported layer media type; only tar and gzip are supported")
    additions, removals, opaque, seen = {}, [], [], set()
    with Path(path).open("rb") as raw:
        stream = gzip.GzipFile(fileobj=raw) if media_type.endswith("gzip") else raw
        reader = ExpandedReader(stream, budget)
        with tarfile.open(fileobj=reader, mode="r|") as archive:
            for entry in archive:
                budget.check()
                name = path_name(entry.name)
                if name in seen: raise InspectionError("Duplicate layer path")
                seen.add(name)
                if len(seen) > 250000: raise InspectionError("Layer entry budget exceeded")
                parent, base = posixpath.split(name)
                if base == ".wh..wh..opq": opaque.append(parent); continue
                if base.startswith(".wh."): removals.append(posixpath.join(parent, base[4:])); continue
                if name.startswith("var/lib/dpkg/updates/") and entry.isfile() and entry.size:
                    raise InspectionError("Pending dpkg updates are unsupported")
                if name not in TRACKED:
                    if not entry.isdir() and any(item.startswith(name + "/") for item in TRACKED):
                        raise InspectionError("A package database ancestor is not a directory")
                    continue
                if entry.issym() or entry.islnk():
                    link = entry.linkname.lstrip("/") if entry.linkname.startswith("/") else posixpath.join(parent if entry.issym() else "", entry.linkname)
                    link = posixpath.normpath(link)
                    if link not in TRACKED: raise InspectionError("Unsupported package database link")
                    additions[name] = ("link", link)
                elif entry.isfile() and not entry.sparse:
                    if entry.size > 16*1024**2: raise InspectionError("Package database size budget exceeded")
                    additions[name] = archive.extractfile(entry).read()
                else: raise InspectionError("Unsupported package database entry")
        while reader.read(65536): pass
        if "sha256:" + reader.hash.hexdigest() != expected_diff_id: raise InspectionError("Uncompressed layer checksum mismatch")
    # Whiteouts only remove lower-layer entries, irrespective of archive order.
    for old in list(state):
        if any(old == removed or old.startswith(removed + "/") for removed in removals) or any(not directory or old.startswith(directory + "/") for directory in opaque):
            del state[old]
    state.update(additions)


def content(state, name):
    seen = set()
    while isinstance(state.get(name), tuple):
        if name in seen: raise InspectionError("Cyclic package database link")
        seen.add(name); name = state[name][1]
    raw = state.get(name)
    return raw.decode("utf-8", errors="strict") if isinstance(raw, bytes) else None


def inventory(state):
    release = content(state, "etc/os-release") or content(state, "usr/lib/os-release")
    if not release: raise InspectionError("Image has no supported os-release metadata")
    info = {}
    for line in release.splitlines():
        if re.match(r"^[A-Z_]+=", line):
            key, value = line.split("=", 1); info[key] = value.strip().strip('"\'')
    identity, version = info.get("ID"), info.get("VERSION_ID", "")
    if not re.fullmatch(r"\d+(?:\.\d+){0,2}", version): raise InspectionError("Unsupported distribution version")
    if identity == "alpine": ecosystem = "ALPINE:V" + ".".join(version.split(".")[:2]); database = content(state, "lib/apk/db/installed")
    elif identity == "debian": ecosystem = "DEBIAN:" + version.split(".")[0]; database = content(state, "var/lib/dpkg/status")
    elif identity == "ubuntu":
        ecosystem = "UBUNTU:" + version + (":LTS" if version.endswith(".04") and int(version.split(".")[0]) % 2 == 0 else "")
        database = content(state, "var/lib/dpkg/status")
    else: raise InspectionError("Unsupported distribution package database")
    if database is None: raise InspectionError("Image has no supported installed-package database")
    components = {}
    for paragraph in re.split(r"\n\s*\n", database):
        fields = dict(line.split(":", 1) for line in paragraph.splitlines() if ":" in line and not line[0].isspace())
        fields = {key: value.strip() for key, value in fields.items()}
        if not fields: continue
        if identity == "alpine": name, package_version = fields.get("o") or fields.get("P"), fields.get("V")
        else:
            if fields.get("Status") != "install ok installed": continue
            source = fields.get("Source", fields.get("Package", ""))
            match = re.fullmatch(r"([^\s]+)(?: \(([^)]+)\))?", source)
            if not match: raise InspectionError("Malformed installed package source")
            name, package_version = match[1], match[2] or fields.get("Version")
        if not name or not package_version or len(name) > 300 or len(package_version) > 100: raise InspectionError("Malformed installed package identity")
        components[(name, package_version)] = dict(name=name, version=package_version, ecosystem=ecosystem, dependencyInfo="Installed OS package", dependencyPaths=[])
        if len(components) > 50000: raise InspectionError("Installed package count budget exceeded")
    if not components: raise InspectionError("No supported installed packages; inspection is incomplete")
    return sorted(components.values(), key=lambda item: (item["name"], item["version"]))


def inspect(source, reference, platform="linux/amd64"):
    platform_parts = platform.split("/")
    if len(platform_parts) not in (2,3): raise InspectionError("Platform must be os/architecture[/variant]")
    manifest = source.document(reference, "manifests")
    selected = reference
    if "manifests" in manifest:
        matches = [item for item in manifest["manifests"] if item.get("platform", {}).get("os") == platform_parts[0] and item.get("platform", {}).get("architecture") == platform_parts[1] and (len(platform_parts)==2 or item.get("platform", {}).get("variant")==platform_parts[2])]
        if len(matches) != 1: raise InspectionError("No unique image matches the requested platform")
        selected = matches[0]["digest"]; manifest = source.document(selected, "manifests")
    if manifest.get("schemaVersion") != 2: raise InspectionError("Unsupported image manifest schema")
    config = source.document(manifest["config"]["digest"])
    if config.get("os") != platform_parts[0] or config.get("architecture") != platform_parts[1]: raise InspectionError("Image configuration platform mismatch")
    layers, diffs = manifest.get("layers", []), config.get("rootfs", {}).get("diff_ids", [])
    if not layers or len(layers) != len(diffs) or len(layers) > 256: raise InspectionError("Invalid image layer chain")
    state = {}
    for layer, diff in zip(layers, diffs):
        if layer.get("urls"): raise InspectionError("External layer URLs are unsupported")
        path = source.blob(layer["digest"])
        if path.stat().st_size != layer.get("size"): raise InspectionError("Layer descriptor size mismatch")
        apply_layer(path, layer["mediaType"], diff, state, source.budget)
    packages = inventory(state)
    return dict(version=selected, components=packages), dict(imageDigest=reference, manifestDigest=selected, platform=platform, layers=len(layers), installedPackages=len(packages), inventory="installed OS source packages", executedImage=False)


def query_osv(packages, budget):
    results = []
    for start in range(0, len(packages), 1000):
        budget.check()
        chunk = packages[start:start+1000]
        queries = []
        for package in chunk:
            stored = package["ecosystem"]
            ecosystem = ("Alpine:" + stored[7:].lower()) if stored.startswith("ALPINE:") else ("Debian:" + stored[7:]) if stored.startswith("DEBIAN:") else "Ubuntu:" + stored[7:]
            queries.append(dict(package=dict(ecosystem=ecosystem,name=package["name"]),version=package["version"]))
        request = urllib.request.Request("https://api.osv.dev/v1/querybatch",data=json.dumps(dict(queries=queries)).encode(),headers={"Content-Type":"application/json"})
        with urllib.request.build_opener(NoRedirect()).open(request,timeout=20) as response:
            raw = response.read(16*1024**2+1)
        if len(raw)>16*1024**2: raise InspectionError("Advisory response budget exceeded")
        found = json.loads(raw).get("results")
        if not isinstance(found,list) or len(found)!=len(chunk): raise InspectionError("Incomplete advisory response")
        for package, result in zip(chunk, found):
            if not isinstance(result,dict) or result.get("error") or result.get("next_page_token") or not isinstance(result.get("vulns",[]),list): raise InspectionError("Incomplete advisory response")
            ids=[item.get("id") for item in result.get("vulns",[]) if isinstance(item,dict)]
            if len(ids)!=len(result.get("vulns",[])) or not all(isinstance(value,str) and value for value in ids): raise InspectionError("Malformed advisory response")
            results.append(dict(name=package["name"],version=package["version"],ecosystem=package["ecosystem"],ids=ids))
    return dict(status="RESOLVED",source="https://api.osv.dev/v1/querybatch",queriedAt=datetime.now(timezone.utc).isoformat(),packages=results)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--image", help="registry/repository@sha256:digest")
    group.add_argument("--layout", help="Local OCI layout directory; requires --digest")
    parser.add_argument("--digest")
    parser.add_argument("--platform", default="linux/amd64")
    parser.add_argument("--credentials-file", help="Private JSON file containing username and password; never printed")
    parser.add_argument("--query-osv", action="store_true", help="Query the online OSV API for the exact installed package versions")
    parser.add_argument("--output", required=True, help="New scan payload JSON file")
    parser.add_argument("--report", required=True, help="New provenance report JSON file")
    args = parser.parse_args()
    if Path(args.output).exists() or Path(args.report).exists(): raise InspectionError("Output paths must be new files")
    budget = Budget()
    credentials = None
    if args.credentials_file:
        with open(args.credentials_file, "rb") as stream: raw = stream.read(16385)
        if len(raw)>16384: raise InspectionError("Credentials file exceeds its budget")
        credentials = json.loads(raw)
        if not all(isinstance(credentials.get(key), str) for key in ("username","password")): raise InspectionError("Invalid credentials file")
    with tempfile.TemporaryDirectory(prefix="oswl-oci-") as workspace:
        if args.image:
            location, reference = args.image.rsplit("@", 1)
            host, repository = location.split("/", 1)
            registry = Registry(host, repository, budget, credentials)
        else: reference, registry = args.digest, None
        digest(reference)
        source = ImageSource(workspace, budget, registry, args.layout)
        payload, report = inspect(source, reference, args.platform)
        report["analysis"] = query_osv(payload["components"],budget) if args.query_osv else dict(status="UNQUERIED")
        with open(args.output,"x",encoding="utf-8") as stream: json.dump(payload, stream, indent=2)
        with open(args.report,"x",encoding="utf-8") as stream: json.dump(report, stream, indent=2)


if __name__ == "__main__":
    try: main()
    except Exception as error:
        # Transport/library exception strings may contain signed URLs or credentials.
        print(str(error) if isinstance(error, InspectionError) else "Image inspection failed; no credentials or source URLs were logged", file=sys.stderr)
        sys.exit(2)
