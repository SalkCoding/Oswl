#!/usr/bin/env python3
"""Add JUnit 5 @Tag annotations to test classes based on path and content heuristics."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOTS = [
    ROOT / "oswl-app" / "src" / "test" / "java",
    ROOT / "oswl-scan-core" / "src" / "test" / "java",
    ROOT / "oswl-vuln-client" / "src" / "test" / "java",
]

TAG_IMPORT = "import org.junit.jupiter.api.Tag;"
TAGS_CLASS = """package com.salkcoding.oswl.testing;

/** JUnit 5 tag names for Gradle testFast / CI matrix filtering. */
public final class TestTags {
    private TestTags() {}

    public static final String FAST = "fast";
    public static final String AUTH = "auth";
    public static final String PARSER = "parser";
    public static final String WEB = "web";
    public static final String INTEGRATION = "integration";
    public static final String LIVE = "live";
}
"""


def categorize(rel_path: str, content: str) -> list[str]:
    path = rel_path.replace("\\", "/").lower()
    name = Path(rel_path).name.lower()

    if "liveintegration" in name or "livetest" in name:
        return ["live"]

    if "@springboottest" in content.lower() or "/repository/" in path:
        return ["integration"]

    tags: list[str] = []

    if any(
        k in name
        for k in (
            "verification",
            "parsertest",
            "parity",
            "manifestrules",
            "patcher",
            "mavenbom",
            "clonerootpathguard",
        )
    ) or "/manifest/" in path and "sync" in name:
        tags.append("parser")
    elif "/client/" in path and "bitbucket" not in name:
        if any(k in name for k in ("clienttest", "catalogservicetest", "epss", "osv", "depsdev", "kev")):
            tags.append("fast")

    if "/auth/" in path or ("/security/" in path and "/controller/" not in path):
        tags.append("auth")

    if "/controller/" in path:
        tags.append("web")

    if not tags:
        tags.append("fast")
    elif "parser" not in tags and "integration" not in tags and "live" not in tags:
        if "fast" not in tags and "auth" in tags and "web" not in tags:
            tags.append("fast")
        elif "fast" not in tags and "web" in tags:
            tags.append("fast")
        elif "fast" not in tags and "parser" not in tags:
            tags.append("fast")

    # parser-only tests should not also be fast (slow optional clones)
    if "parser" in tags:
        tags = [t for t in tags if t != "fast"]

    return sorted(set(tags))


def add_tags_to_file(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")
    if "@Tag(" in content:
        return False

    rel = str(path.relative_to(ROOT))
    tags = categorize(rel, content)
    if not tags:
        return False

    tag_lines = [f'@Tag(TestTags.{t.upper()})' for t in tags]
    static_import = "import static com.salkcoding.oswl.testing.TestTags.*;\n"

    lines = content.splitlines(keepends=True)
    pkg_idx = next((i for i, l in enumerate(lines) if l.startswith("package ")), None)
    if pkg_idx is None:
        return False

    insert_at = pkg_idx + 1
    while insert_at < len(lines) and lines[insert_at].strip() == "":
        insert_at += 1

    imports_block: list[str] = []
    if TAG_IMPORT not in content:
        imports_block.append(TAG_IMPORT + "\n")
    if "com.salkcoding.oswl.testing.TestTags" not in content:
        imports_block.append("import com.salkcoding.oswl.testing.TestTags;\n")

    # find class/interface declaration
    decl_idx = None
    for i, line in enumerate(lines):
        stripped = line.strip()
        if re.match(r"(public\s+)?(abstract\s+)?class\s+\w+", stripped):
            decl_idx = i
            break
        if re.match(r"(public\s+)?interface\s+\w+", stripped):
            decl_idx = i
            break
    if decl_idx is None:
        return False

    # skip annotations already on class
    anno_insert = decl_idx
    while anno_insert > 0 and lines[anno_insert - 1].strip().startswith("@"):
        anno_insert -= 1

    new_lines = lines[:insert_at] + imports_block + lines[insert_at:anno_insert]
    new_lines += [line + "\n" if not line.endswith("\n") else line for line in tag_lines]
    new_lines += lines[anno_insert:]
    path.write_text("".join(new_lines), encoding="utf-8")
    return True


def main() -> None:
    for module in ("oswl-app", "oswl-scan-core", "oswl-vuln-client"):
        tags_path = ROOT / module / "src" / "test" / "java" / "com" / "salkcoding" / "oswl" / "testing" / "TestTags.java"
        tags_path.parent.mkdir(parents=True, exist_ok=True)
        if not tags_path.exists():
            tags_path.write_text(TAGS_CLASS, encoding="utf-8")

    changed = 0
    for root in TEST_ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*Test.java")):
            if add_tags_to_file(path):
                changed += 1
                print(f"tagged: {path.relative_to(ROOT)}")
    print(f"Done. Tagged {changed} files.")


if __name__ == "__main__":
    main()
