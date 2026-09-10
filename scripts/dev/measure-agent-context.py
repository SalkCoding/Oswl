"""Compare bounded source retrieval with pre-extraction whole-file retrieval.

This measures raw bytes and cached file-read time, not tokens or agent task time.
Common instructions and test code are excluded from both sides.
"""
import argparse
import json
import statistics
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = "src/main/java/com/salkcoding/oswl/"
RESOURCES = "src/main/resources/"
CASES = {
    "composer_format": (
        JAVA + "service/ingest/DependencyManifestParserService.java",
        [".agents/features.md", ".agents/features/manifests.md",
         JAVA + "service/ingest/parser/ComposerLockParser.java"],
    ),
    "security_settings_request": (
        RESOURCES + "templates/settings/tabs/security.html",
        [".agents/features.md", ".agents/features/ui-requests.md",
         RESOURCES + "static/js/settings/security.js"],
    ),
}


def measure(paths):
    samples = []
    for _ in range(20):
        start = time.perf_counter_ns()
        output = b"\n".join(path.read_bytes() for path in paths)
        samples.append((time.perf_counter_ns() - start) / 1_000_000)
    return {"files": len(paths), "retrieved_bytes": sum(p.stat().st_size for p in paths),
            "equivalent_stdout_bytes": len(output), "median_read_ms": round(statistics.median(samples), 3)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="7695b20")
    args = parser.parse_args()
    result = {"baseline": args.baseline, "cases": {}}
    with tempfile.TemporaryDirectory(prefix="oswl-context-") as directory:
        for name, (old_path, new_paths) in CASES.items():
            old = Path(directory) / name
            old.write_bytes(subprocess.check_output(["git", "show", args.baseline + ":" + old_path], cwd=ROOT))
            result["cases"][name] = {
                "before_paths": [old_path], "after_paths": new_paths,
                "before": measure([old]), "after": measure([ROOT / p for p in new_paths]),
            }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
