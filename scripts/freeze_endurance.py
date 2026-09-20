#!/usr/bin/env python3
"""Freeze source/build inputs for an explicit LLM endurance run; uses only the standard library."""
import argparse
import hashlib
import json
from pathlib import Path

def inputs(root):
    if (root / "samcnpc-llm/src").is_dir():
        modules = [root / ("samcnpc-" + name) for name in ("core", "behavior", "llm")]
    elif (root / "src/main/kotlin/io/samcnpc/llm").is_dir():
        modules = [root, root / "behavior", root / "behavior/core"]
    else:
        raise ValueError("Use the SAMCNPC workspace or standalone SAMCNPC_LLM root")
    files = set()
    for module in modules:
        if not (module / "src").is_dir():
            raise ValueError("Initialize the pinned recursive submodules first")
        files.update(p for p in (module / "src").rglob("*") if p.is_file())
        for name in ("build.gradle", "build.gradle.kts", "settings.gradle", "gradle.properties"):
            if (module / name).is_file():
                files.add(module / name)
    for name in ("build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
                 "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar"):
        if (root / name).is_file():
            files.add(root / name)
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--manifest", type=Path, default=Path("build/endurance-source-manifest.json"))
    parser.add_argument("--verify", action="store_true")
    parser.add_argument("--report", type=Path, help="Optional final FULL llm-hour-progress.json to verify")
    args = parser.parse_args()
    root = args.root.resolve()
    manifest = args.manifest if args.manifest.is_absolute() else root / args.manifest
    current = inputs(root)
    if args.verify:
        saved = json.loads(manifest.read_text(encoding="utf-8"))
        if current != saved:
            changed = sorted(n for n in set(current) | set(saved) if current.get(n) != saved.get(n))
            raise ValueError("Source/build inputs changed: " + ", ".join(changed[:12]))
    else:
        if args.report:
            raise ValueError("--report requires --verify")
        if manifest.exists():
            raise ValueError("Manifest already exists; use --verify or an explicit fresh path")
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(json.dumps(current, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
    if args.report:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        if not (report.get("status") == "PASS" and report.get("mode") == "FULL"
                and report.get("sourceHash") == digest and report.get("npcCount") == 6
                and report.get("activeSeconds", 0) >= 3600 and report.get("activeTicks", 0) >= 72000):
            raise ValueError("Report is not a passing FULL campaign for this manifest")
        print("Verified final FULL report and unchanged source/build inputs.")
    print("Files:", len(current))
    print("llmHourSourceHash=" + digest)

if __name__ == "__main__":
    main()
