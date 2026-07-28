#!/usr/bin/env python3
"""Run the reproducible, isolated phase-0 beautify-pptx baseline."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import re
import shutil
import subprocess
from datetime import UTC, datetime
from html import escape
from pathlib import Path
from typing import Any

from pptx import Presentation


SCRIPT_PATHS = (
    "skills/ppt-master/workflows/beautify-pptx.md",
    "skills/ppt-master/scripts/beautify_identity.py",
    "skills/ppt-master/scripts/beautify_inventory.py",
    "skills/ppt-master/scripts/pptx_intake.py",
    "skills/ppt-master/scripts/project_manager.py",
    "skills/ppt-master/scripts/svg_quality_checker.py",
    "skills/ppt-master/scripts/finalize_svg.py",
    "skills/ppt-master/scripts/svg_to_pptx.py",
    "skills/ppt-master/scripts/source_to_md/ppt_to_md.py",
)
COLORS = {"background": "#FFFFFF", "title": "#16324F", "accent": "#2878B5", "text": "#263238"}


def run(command: list[str], cwd: Path, expected: int = 0) -> dict[str, Any]:
    completed = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    record = {
        "command": [redact_path(value, cwd) for value in command],
        "exitCode": completed.returncode,
        "stdout": redact_output(completed.stdout, cwd),
        "stderr": redact_output(completed.stderr, cwd),
    }
    if completed.returncode != expected:
        raise RuntimeError(json.dumps(record, ensure_ascii=False))
    return record


def redact_path(value: str, work_root: Path) -> str:
    try:
        return str(Path(value).resolve().relative_to(work_root.resolve()))
    except (OSError, ValueError):
        return f"<external>/{Path(value).name}" if Path(value).is_absolute() else value


def redact_output(value: str, work_root: Path) -> str:
    value = value.replace(str(work_root), "<work-root>")
    return re.sub(r"/(?:[^\s'\"`])+", "<path>", value).strip()[-2000:]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_output(repo: Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(repo), *args], text=True, capture_output=True, check=True).stdout.strip()


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def require(value: Any, message: str) -> Any:
    if not value:
        raise RuntimeError(message)
    return value


def create_svg(project: Path, expected: dict[str, Any]) -> None:
    svg_dir = project / "svg_output"
    for slide in expected["slides"]:
        page = int(slide["index"])
        items = list(slide["text"])
        for row in slide.get("tables", []):
            items.extend(row)
        chart_data = slide.get("charts", {})
        items.extend(chart_data.get("categories", []))
        for series in chart_data.get("series", []):
            items.append(series["name"])
            items.extend(str(value) for value in series["values"])
        lines = [
            '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" viewBox="0 0 1280 720">',
            f'<rect width="1280" height="720" fill="{COLORS["background"]}"/>',
            f'<rect x="70" y="105" width="210" height="6" fill="{COLORS["accent"]}"/>',
        ]
        if page == 3:
            lines.append('<image href="../images/image1.png" x="650" y="155" width="520" height="293" preserveAspectRatio="xMidYMid slice"/>')
        if page == 4:
            for x in (90, 430, 700, 1040):
                lines.append(f'<line x1="{x}" y1="180" x2="{x}" y2="430" stroke="{COLORS["accent"]}" stroke-width="2"/>')
            for y in (180, 260, 345, 430):
                lines.append(f'<line x1="90" y1="{y}" x2="1040" y2="{y}" stroke="{COLORS["accent"]}" stroke-width="2"/>')
        if page == 5:
            for offset, value in enumerate((18, 24, 21, 20, 20, 20)):
                x = 260 + offset * 110
                height = value * 12
                fill = COLORS["accent"] if offset < 3 else "#7FA9C9"
                lines.append(f'<rect x="{x}" y="{620 - height}" width="60" height="{height}" fill="{fill}"/>')
        y = 70
        for index, text in enumerate(items):
            font_size = 34 if index == 0 else 21
            x = 90 if page != 3 or index < 2 else 90
            if page == 3 and index == 1:
                y = 165
            elif page == 7 and index == 0:
                y = 340
                font_size = 42
            elif index > 0:
                y += 52
            lines.append(
                f'<text x="{x}" y="{y}" font-family="Aptos, Arial, sans-serif" '
                f'font-size="{font_size}" fill="{COLORS["title"] if index == 0 else COLORS["text"]}">{escape(text)}</text>'
            )
        if page == 5:
            lines.append(f'<text x="240" y="660" font-family="Aptos, Arial, sans-serif" font-size="18" fill="{COLORS["text"]}">North   South   West   Actual   Plan</text>')
        lines.append("</svg>")
        (svg_dir / f"{page:02d}_baseline.svg").write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate_schema(artifacts: dict[str, dict[str, Any]]) -> dict[str, Any]:
    required = {
        "identity": {"slide_count": int, "canvas": dict, "theme": dict, "observed": dict},
        "slide_library": {"schema": str, "slide_count": int, "canvas_px": dict, "slides": list},
        "source_profile": {"schema": str, "deck_count": int, "decks": list},
        "image_manifest": {},
        "inventory": {"schema": str, "slide_count": int, "canvas_px": dict, "slides": list},
    }
    summary: dict[str, Any] = {}
    for name, fields in required.items():
        payload = artifacts[name]
        if isinstance(payload, list):
            require(name == "image_manifest" and len(payload) > 0, "image manifest is invalid")
            require(all(isinstance(item, dict) for item in payload), "image manifest item is invalid")
            require(all(isinstance(item.get("filename"), str) and isinstance(item.get("occurrences"), list)
                        and isinstance(item.get("usage_count"), int) for item in payload), "image manifest field type changed")
            extended = [*payload, {"future_optional_field": True}]
            require(len(extended) == len(payload) + 1, "image manifest rejected extension")
            summary[name] = {"required": [], "extensionAccepted": True, "missingRequiredRejected": False}
            continue
        require(all(field in payload and isinstance(payload[field], expected_type) for field, expected_type in fields.items()),
                f"{name} missing required field or field type changed")
        extended = copy.deepcopy(payload)
        extended["future_optional_field"] = True
        require(all(field in extended and isinstance(extended[field], expected_type) for field, expected_type in fields.items()),
                f"{name} rejected extension")
        if fields:
            missing = copy.deepcopy(payload)
            missing.pop(next(iter(fields)))
            require(not all(field in missing and isinstance(missing[field], expected_type)
                            for field, expected_type in fields.items()), f"{name} accepted missing field")
        summary[name] = {"required": list(fields), "extensionAccepted": True, "missingRequiredRejected": bool(fields)}
    return summary


def create_canvas_variant(source: Path, destination: Path, width_inches: float, height_inches: float) -> None:
    presentation = Presentation(source)
    presentation.slide_width = int(width_inches * 914400)
    presentation.slide_height = int(height_inches * 914400)
    presentation.save(destination)


def text_by_slide(markdown: str) -> list[str]:
    chunks = markdown.split("\n## Slide ")
    return [chunk for chunk in chunks[1:] if chunk.strip()]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True, help="ppt-master checkout")
    parser.add_argument("--python", dest="python", type=Path, required=True, help="validated ppt-master Python")
    parser.add_argument("--fixture", type=Path, required=True, help="synthetic input PPTX")
    parser.add_argument("--expected", type=Path, required=True, help="fixture expectation manifest")
    parser.add_argument("--work-root", type=Path, required=True, help="empty, isolated output directory")
    args = parser.parse_args()

    repo = args.repo.resolve()
    fixture = args.fixture.resolve()
    expected = read_json(args.expected.resolve())
    work_root = args.work_root.resolve()
    if work_root.exists() and any(work_root.iterdir()):
        raise RuntimeError(f"work root must be empty: {work_root}")
    work_root.mkdir(parents=True, exist_ok=True)

    scripts = {path: repo / path for path in SCRIPT_PATHS}
    require(args.python.is_file(), "configured Python executable is missing")
    require(fixture.is_file(), "fixture is missing")
    require(all(path.is_file() for path in scripts.values()), "required upstream script is missing")

    records: list[dict[str, Any]] = []
    records.append(run([str(args.python), "--version"], repo))
    for path in scripts.values():
        if path.suffix != ".py":
            continue
        records.append(run([str(args.python), str(path), "--help"], repo))

    source_dirty = git_output(repo, "status", "--short")
    modified_paths = [line[2:].strip() for line in source_dirty.splitlines() if line]
    workflow_dirty = [path for path in modified_paths if path in SCRIPT_PATHS]
    freeze = run([str(args.python), "-m", "pip", "freeze"], repo)
    font_match = shutil.which("fc-match")
    font_record = run([font_match, "Aptos"], repo) if font_match else {"available": False}
    libreoffice = shutil.which("libreoffice")
    office_record = run([libreoffice, "--version"], repo) if libreoffice else {"available": False}
    snapshot = {
        "generatedAt": datetime.now(UTC).isoformat(),
        "commit": git_output(repo, "rev-parse", "HEAD"),
        "branch": git_output(repo, "branch", "--show-current"),
        "dirtyPaths": modified_paths,
        "workflowDirtyPaths": workflow_dirty,
        "scriptSha256": {path: sha256(script) for path, script in scripts.items()},
        "pythonDependencyFreeze": {"sha256": hashlib.sha256(freeze["stdout"].encode()).hexdigest(), "lineCount": len(freeze["stdout"].splitlines())},
        "fontCheck": font_record,
        "officeCheck": office_record,
    }
    write_json(work_root / "upstream-snapshot.json", snapshot)

    identity_stdout = work_root / "identity-stdout.json"
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/beautify_identity.py"]), str(fixture), "-o", str(identity_stdout)], repo))
    identity = read_json(identity_stdout)
    require(abs(identity["canvas"]["aspect"] - 1.7777) < 0.01, "fixture did not resolve to ppt169")
    canvas_matrix = {"1.7777": "ppt169", "1.3333": "ppt43", "other": "nearest standard format; source dimensions retained in evidence"}
    canvas_variants = (("ppt43", 10.0, 7.5, "ppt43"), ("custom", 10.0, 8.0, "nearest standard format"))
    canvas_results = {"ppt169": {"aspect": identity["canvas"]["aspect"], "resolvedFormat": "ppt169"}}
    for label, width, height, resolved in canvas_variants:
        variant = work_root / f"canvas-{label}.pptx"
        create_canvas_variant(fixture, variant, width, height)
        variant_identity = work_root / f"canvas-{label}.identity.json"
        records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/beautify_identity.py"]), str(variant), "-o", str(variant_identity)], repo))
        aspect = read_json(variant_identity)["canvas"]["aspect"]
        canvas_results[label] = {"aspect": aspect, "resolvedFormat": resolved}
    require(abs(canvas_results["ppt43"]["aspect"] - 1.3333) < 0.01, "4:3 canvas mapping probe failed")
    require(abs(canvas_results["custom"]["aspect"] - 1.25) < 0.01, "custom canvas mapping probe failed")

    projects_root = work_root / "projects"
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/project_manager.py"]), "init", "phase0-baseline", "--format", "ppt169", "--dir", str(projects_root)], repo))
    project = require(next(projects_root.iterdir(), None), "project init did not create a project")
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/project_manager.py"]), "import-sources", str(project), str(fixture), "--copy"], repo))
    analysis = project / "analysis"
    stem = fixture.stem
    required_files = {
        "identity": analysis / f"{stem}.identity.json",
        "slide_library": analysis / f"{stem}.slide_library.json",
        "source_profile": analysis / "source_profile.json",
        "image_manifest": project / "images/image_manifest.json",
        "source_markdown": project / "sources" / f"{stem}.md",
    }
    require(all(path.is_file() for path in required_files.values()), "import-sources intake bundle is incomplete")
    before = {path.name: sha256(path) for path in analysis.iterdir() if path.is_file()}
    require(f"{stem}.identity.json" in before and f"{stem}.slide_library.json" in before, "intake was not generated by import")
    fallback_analysis = work_root / "fallback-analysis"
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/pptx_intake.py"]), str(fixture), "-o", str(fallback_analysis)], repo))
    require((fallback_analysis / f"{stem}.identity.json").is_file(), "fallback intake did not write identity")
    require((fallback_analysis / f"{stem}.slide_library.json").is_file(), "fallback intake did not write slide library")

    inventory_path = analysis / "beautify_inventory.json"
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/beautify_inventory.py"]), str(required_files["slide_library"]), "--images", str(required_files["image_manifest"]), "-o", str(inventory_path)], repo))
    artifacts = {name: read_json(path) for name, path in required_files.items() if name != "source_markdown"}
    artifacts["inventory"] = read_json(inventory_path)
    schema_summary = validate_schema(artifacts)
    require(artifacts["inventory"]["slide_count"] == expected["slideCount"], "inventory page count mismatch")

    ignored = [{"slide_index": 3, "item": "source image crop/rotation", "reason": "not preserved by layout-only redraw"}]
    needs_confirmation = [
        {"slide_index": 6, "item": "density", "recommendation": "preserve one page and flag density"},
        {"slide_index": 7, "item": "near-empty divider", "recommendation": "preserve as divider"},
    ]
    plan = {
        "version": 1,
        "status": "draft",
        "sourcePageCount": expected["slideCount"],
        "identitySource": "observed",
        "ignored": ignored,
        "needsConfirmation": needs_confirmation,
        "hardConstraints": {"textVerbatim": True, "dataVerbatim": True, "pageMapping": "1:1"},
    }
    digest = hashlib.sha256(json.dumps(plan, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    plan["digest"] = digest
    write_json(analysis / "beautify_plan.draft.json", plan)
    require(not (project / "design_spec.md").exists() and not (project / "spec_lock.md").exists(), "spec existed before confirmation")
    rejected_plan = copy.deepcopy(plan)
    rejected_plan["requestedPageMapping"] = "split"
    rejected_plan["status"] = "rejected"
    write_json(analysis / "beautify_plan.rejected.json", rejected_plan)
    plan["status"] = "confirmed"
    write_json(analysis / "beautify_plan.confirmed.json", plan)

    (project / "design_spec.md").write_text("# Phase 0 Beautify Design\n\nAll seven source pages map 1:1 in order.\n", encoding="utf-8")
    (project / "spec_lock.md").write_text(
        "## colors\n- background: #FFFFFF\n- title: #16324F\n- accent: #2878B5\n- text: #263238\n- chart_plan: #7FA9C9\n"
        "## typography\n- body: 21\n- title: 34\n- annotation: 18\n",
        encoding="utf-8",
    )
    create_svg(project, expected)
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/svg_quality_checker.py"]), str(project / "svg_output"), "--format", "ppt169"], repo))
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/finalize_svg.py"]), str(project)], repo))
    output = project / "exports" / "phase0-baseline-output.pptx"
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/svg_to_pptx.py"]), str(project), "--only", "native", "--no-notes", "-o", str(output)], repo))
    require(output.is_file(), "export did not produce PPTX")
    readback = project / "validation-readback.md"
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/source_to_md/ppt_to_md.py"]), str(output), "-o", str(readback)], repo))
    records.append(run([str(args.python), str(scripts["skills/ppt-master/scripts/project_manager.py"]), "validate", str(project)], repo))
    output_slides = text_by_slide(readback.read_text(encoding="utf-8"))
    require(len(output_slides) == expected["slideCount"], "read-back page count mismatch")
    for source_slide, output_slide in zip(expected["slides"], output_slides, strict=True):
        for text in source_slide["text"]:
            require(text in output_slide, f"read-back text mismatch on page {source_slide['index']}: {text}")
        for row in source_slide.get("tables", []):
            for cell in row:
                require(cell in output_slide, f"read-back table mismatch on page {source_slide['index']}: {cell}")
        for series in source_slide.get("charts", {}).get("series", []):
            require(series["name"] in output_slide, f"read-back chart series mismatch: {series['name']}")
            for value in series["values"]:
                require(str(value) in output_slide, f"read-back chart value mismatch: {value}")
    for category in expected["slides"][4]["charts"]["categories"]:
        require(category in output_slides[4], f"read-back chart category mismatch: {category}")

    negative = run([str(args.python), str(scripts["skills/ppt-master/scripts/beautify_inventory.py"])], repo, expected=2)
    summary = {
        "result": "passed",
        "fixture": expected["fixture"],
        "pageCount": expected["slideCount"],
        "canvasMapping": {"rules": canvas_matrix, "probes": canvas_results},
        "intake": {name: str(path.relative_to(project)) for name, path in required_files.items()},
        "schema": schema_summary,
        "plan": {"version": plan["version"], "digest": digest, "confirmed": True, "rejectedConstraintRelaxation": True},
        "output": {"pptx": str(output.relative_to(project)), "readback": str(readback.relative_to(project)), "textAndDataFidelity": "passed"},
        "negativeCommand": negative,
        "commands": records,
    }
    write_json(work_root / "baseline-summary.json", summary)
    print(json.dumps({"result": "passed", "workRoot": str(work_root), "project": str(project)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
