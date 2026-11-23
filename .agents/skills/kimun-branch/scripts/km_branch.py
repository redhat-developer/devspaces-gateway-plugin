#!/usr/bin/env python3
"""Branch-scoped Kimün (``km``) code-health gate.

Scores only the files changed by the current topic branch and fails when any
grade drops below ``A-``. Duplication is compared against a baseline snapshot
because per-file duplication scores cannot see duplication across files.

Standard library only; runs on macOS, Linux and Windows (Python 3.9+).
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Optional, Sequence, Union

EXIT_PASS = 0
EXIT_GATE_FAILURE = 1
EXIT_TOOLING_ERROR = 2

PASSING_GRADE_PREFIX = "A"
GRADE_MINIMUM_LABEL = "A-"

CHANGED_FILE_FILTER = "ACMRT"
BASE_REF_ENV_VAR = "KM_BASE_REF"
DEFAULT_BASE_REFS = ("upstream/main", "origin/main", "main")
UNTRACKED_SCOPE = "src"
AGENT_INFRA_DIRECTORIES = (".agents/", ".claude/", ".cursor/", "rules/")
AGENT_INFRA_FILES = ("AGENTS.md",)

DEFAULT_DUPS_BASELINE = Path("build") / "km-dups-baseline.json"
USE_DEFAULT_BASELINE_PATH = "default"
DUPS_BASELINE_VERSION = 1

STALE_REF_AGE_DAYS = 30
SECONDS_PER_DAY = 86_400

DIMENSION_ABBREVIATIONS = {
    "Cognitive Complexity": "Cog",
    "Duplication": "Dup",
    "Indentation Complexity": "Ind",
    "Halstead Effort": "Hal",
    "File Size": "Size",
}

FAILED_CELL_MARKER = " ✗"
ENCODING = "utf-8"
ENCODING_ERRORS = "replace"

# A ``km dups`` group as parsed from JSON: ``line_count`` plus ``locations``.
DuplicateGroup = dict[str, Any]
# One file's participation in a duplicate group: (member files, block size).
ParticipationSignature = tuple[frozenset[str], int]
# A duplication regression: (changed file, participation signatures new at baseline).
DupsRegression = tuple[str, list[ParticipationSignature]]
# A scored file: path, overall grade, per-dimension grades and pass flags.
ScoreRecord = dict[str, Any]


class ToolingError(RuntimeError):
    """Raised when git or km cannot run or returns unusable output."""


def decode(raw_bytes: bytes) -> str:
    return raw_bytes.decode(ENCODING, ENCODING_ERRORS)


def run_command(
    command: Sequence[str], cwd: Path, allow_failure: bool = False
) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(command, cwd=str(cwd), capture_output=True, check=False)
    except FileNotFoundError as error:
        raise ToolingError(
            f"`{command[0]}` not found in PATH. See rules/kimun.md for install steps."
        ) from error
    if result.returncode != 0 and not allow_failure:
        details = decode(result.stderr).strip()
        raise ToolingError(f"`{' '.join(command)}` failed: {details}")
    return result


def run_git(
    git_arguments: Sequence[str], cwd: Path, allow_failure: bool = False
) -> subprocess.CompletedProcess:
    return run_command(["git", *git_arguments], cwd, allow_failure)


def find_repo_root() -> Path:
    result = run_git(["rev-parse", "--show-toplevel"], Path.cwd())
    root = decode(result.stdout).strip()
    if not root:
        raise ToolingError("not inside a git repository")
    return Path(root)


def ensure_km_available(cwd: Path) -> None:
    result = run_command(["km", "--version"], cwd, allow_failure=True)
    if result.returncode != 0:
        details = decode(result.stderr).strip()
        raise ToolingError(
            f"`km --version` failed: {details}. See rules/kimun.md for install steps."
        )


def ref_exists(ref: str, cwd: Path) -> bool:
    result = run_git(
        ["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], cwd, allow_failure=True
    )
    return result.returncode == 0


def fetch_hint(ref: str) -> str:
    if "/" in ref:
        remote, branch = ref.split("/", 1)
        return f"git fetch {remote} {branch}"
    return "git fetch --all"


def resolve_base_ref(explicit_ref: Optional[str], cwd: Path) -> str:
    requested = explicit_ref or os.environ.get(BASE_REF_ENV_VAR)
    if requested:
        if not ref_exists(requested, cwd):
            raise ToolingError(
                f"base ref `{requested}` not found. Fetch it first: {fetch_hint(requested)}"
            )
        return requested
    for candidate in DEFAULT_BASE_REFS:
        if ref_exists(candidate, cwd):
            return candidate
    tried = ", ".join(DEFAULT_BASE_REFS)
    raise ToolingError(
        f"no base ref found (tried {tried}). Pass --base <ref> or set {BASE_REF_ENV_VAR}."
    )


def last_update_epoch(ref: str, cwd: Path) -> Optional[int]:
    result = run_git(
        ["reflog", "show", "-1", "--date=unix", "--format=%gd", ref], cwd, allow_failure=True
    )
    if result.returncode != 0:
        return None
    selector = decode(result.stdout).strip()
    if "@{" not in selector or not selector.endswith("}"):
        return None
    try:
        return int(selector.rsplit("@{", 1)[1].rstrip("}"))
    except ValueError:
        return None


def warn_if_stale(ref: str, cwd: Path) -> None:
    """Warn when a remote-tracking base ref has not been refreshed recently."""
    if not ref.startswith(("upstream/", "origin/")):
        return
    updated_epoch = last_update_epoch(ref, cwd)
    if updated_epoch is None:
        return
    age_days = max(0, int(time.time()) - updated_epoch) // SECONDS_PER_DAY
    if age_days < STALE_REF_AGE_DAYS:
        return
    print(
        f"warning: `{ref}` was last refreshed {age_days} days ago and may be stale. "
        f"Refresh with: {fetch_hint(ref)}",
        file=sys.stderr,
    )


def parse_nul_paths(raw_bytes: bytes) -> list[str]:
    return [decode(part) for part in raw_bytes.split(b"\0") if part]


def is_agent_infra(relative_path: str) -> bool:
    return relative_path in AGENT_INFRA_FILES or relative_path.startswith(AGENT_INFRA_DIRECTORIES)


def untracked_source_arguments(include_untracked_all: bool) -> list[str]:
    arguments = ["ls-files", "--others", "--exclude-standard", "-z"]
    if not include_untracked_all:
        arguments.extend(["--", UNTRACKED_SCOPE])
    return arguments


def git_sources(base_ref: str, include_untracked_all: bool) -> list[list[str]]:
    """The three change sources, in priority order (committed, working tree, untracked)."""
    return [
        ["diff", "--name-only", "-z", f"--diff-filter={CHANGED_FILE_FILTER}", f"{base_ref}...HEAD"],
        ["diff", "--name-only", "-z", f"--diff-filter={CHANGED_FILE_FILTER}", "HEAD"],
        untracked_source_arguments(include_untracked_all),
    ]


def discover_changed_files(
    repo_root: Path,
    base_ref: str,
    include_infra: bool = False,
    include_untracked_all: bool = False,
) -> list[str]:
    changed_paths: list[str] = []
    seen_paths: set[str] = set()
    for source in git_sources(base_ref, include_untracked_all):
        result = run_git(source, repo_root)
        for relative_path in parse_nul_paths(result.stdout):
            if relative_path in seen_paths:
                continue
            seen_paths.add(relative_path)
            if not (repo_root / relative_path).is_file():
                continue
            if not include_infra and is_agent_infra(relative_path):
                continue
            changed_paths.append(relative_path)
    return changed_paths


def score_path(relative_path: str, repo_root: Path) -> Optional[dict[str, Any]]:
    result = run_command(
        ["km", "score", "--format", "json", relative_path], repo_root, allow_failure=True
    )
    if result.returncode != 0:
        details = decode(result.stderr).strip()
        raise ToolingError(f"`km score` failed for {relative_path}: {details}")
    try:
        payload = json.loads(decode(result.stdout))
    except json.JSONDecodeError as error:
        raise ToolingError(f"`km score` returned invalid JSON for {relative_path}: {error}")
    if payload.get("files_analyzed", 0) == 0 or payload.get("score") is None:
        return None
    return payload


def is_passing_grade(grade: Optional[str]) -> bool:
    """A missing grade cannot be measured, so km spreads its weight: treat as pass."""
    return grade is None or grade.startswith(PASSING_GRADE_PREFIX)


def score_record(relative_path: str, payload: dict[str, Any]) -> ScoreRecord:
    dimensions = [
        {
            "name": dimension.get("name", "Unknown dimension"),
            "grade": dimension.get("grade"),
            "passing": is_passing_grade(dimension.get("grade")),
        }
        for dimension in payload.get("dimensions", [])
    ]
    overall_grade = payload.get("grade")
    return {
        "path": relative_path,
        "overall_grade": overall_grade,
        "overall_passing": is_passing_grade(overall_grade),
        "dimensions": dimensions,
    }


def file_failures(record: ScoreRecord) -> list[str]:
    failures: list[str] = []
    if not record["overall_passing"]:
        failures.append(f"overall `{record['overall_grade']}`")
    for dimension in record["dimensions"]:
        if not dimension["passing"]:
            failures.append(f"{dimension['name']} `{dimension['grade']}`")
    return failures


def abbreviate_dimension(name: str) -> str:
    return DIMENSION_ABBREVIATIONS.get(name, "".join(word[0] for word in name.split()[:4]))


def render_grade_cell(grade: Optional[str], passing: bool) -> str:
    rendered = grade if grade else "n/a"
    return rendered if passing else f"{rendered}{FAILED_CELL_MARKER}"


def render_score_table(records: Sequence[ScoreRecord]) -> str:
    if not records:
        return "_No km-analyzable files changed._"
    dimension_names = [dimension["name"] for dimension in records[0]["dimensions"]]
    header = ["File", "Overall", *[abbreviate_dimension(name) for name in dimension_names]]
    lines = [
        "| " + " | ".join(header) + " |",
        "| " + " | ".join(["---"] * len(header)) + " |",
    ]
    for record in records:
        lines.append("| " + " | ".join(score_row_cells(record, dimension_names)) + " |")
    lines.append("")
    lines.append(f"✗ = below {GRADE_MINIMUM_LABEL}")
    return "\n".join(lines)


def score_row_cells(record: ScoreRecord, dimension_names: Sequence[str]) -> list[str]:
    grades = {dimension["name"]: dimension for dimension in record["dimensions"]}
    cells = [
        f"`{record['path']}`",
        render_grade_cell(record["overall_grade"], record["overall_passing"]),
    ]
    for name in dimension_names:
        dimension = grades.get(name)
        cells.append(
            "n/a" if dimension is None else render_grade_cell(dimension["grade"], dimension["passing"])
        )
    return cells


def render_score_failures(records: Sequence[ScoreRecord]) -> str:
    failing_records = [record for record in records if file_failures(record)]
    if not failing_records:
        return f"All {len(records)} analyzed files pass {GRADE_MINIMUM_LABEL}."
    lines = [f"**{len(failing_records)} of {len(records)} files below {GRADE_MINIMUM_LABEL}:**"]
    for record in failing_records:
        lines.append(f"- `{record['path']}`: " + ", ".join(file_failures(record)))
    return "\n".join(lines)


def evaluate_branch(
    repo_root: Path, base_ref: str
) -> tuple[list[ScoreRecord], list[str], bool]:
    changed_files = discover_changed_files(repo_root, base_ref)
    records: list[ScoreRecord] = []
    for relative_path in changed_files:
        payload = score_path(relative_path, repo_root)
        if payload is not None:
            records.append(score_record(relative_path, payload))
    failed = any(file_failures(record) for record in records)
    return records, changed_files, failed


def print_score_section(records: Sequence[ScoreRecord]) -> None:
    print(render_score_table(records))
    print()
    print(render_score_failures(records))


def normalize_location(raw_location: str, repo_root: Path) -> str:
    location = Path(raw_location)
    if location.is_absolute():
        try:
            location = location.relative_to(repo_root)
        except ValueError:
            return location.as_posix()
    normalized = location.as_posix()
    while normalized.startswith("./"):
        normalized = normalized[2:]
    return normalized


def load_dups_payload(repo_root: Path) -> dict[str, Any]:
    result = run_command(["km", "dups", "--format", "json"], repo_root, allow_failure=True)
    if result.returncode != 0:
        details = decode(result.stderr).strip()
        raise ToolingError(f"`km dups` failed: {details}")
    try:
        return json.loads(decode(result.stdout))
    except json.JSONDecodeError as error:
        raise ToolingError(f"`km dups` returned invalid JSON: {error}")


def group_files(group: DuplicateGroup, repo_root: Path) -> set[str]:
    return {
        normalize_location(location.get("file_path", ""), repo_root)
        for location in group.get("locations", [])
    }


def group_line_count(group: DuplicateGroup) -> int:
    return int(group.get("line_count", 0))


def group_signature(group: DuplicateGroup, repo_root: Path) -> ParticipationSignature:
    return frozenset(group_files(group, repo_root)), group_line_count(group)


def groups_touching(
    groups: Sequence[DuplicateGroup], changed_files: Sequence[str], repo_root: Path
) -> list[DuplicateGroup]:
    changed = set(changed_files)
    return [group for group in groups if group_files(group, repo_root) & changed]


def participation_signatures(
    groups: Sequence[DuplicateGroup], relative_path: str, repo_root: Path
) -> set[ParticipationSignature]:
    """Every (member files, block size) signature in which the file participates."""
    signatures: set[ParticipationSignature] = set()
    for group in groups:
        members = group_files(group, repo_root)
        if relative_path in members:
            signatures.add((frozenset(members), group_line_count(group)))
    return signatures


def signature_sort_key(signature: ParticipationSignature) -> tuple[tuple[str, ...], int]:
    members, line_count = signature
    return tuple(sorted(members)), line_count


def render_group(group: DuplicateGroup, repo_root: Path) -> str:
    locations = []
    for location in group.get("locations", []):
        relative_path = normalize_location(location.get("file_path", ""), repo_root)
        start = location.get("start_line", "?")
        end = location.get("end_line", "?")
        locations.append(f"`{relative_path}:{start}-{end}`")
    return (
        f"- {len(group.get('locations', []))} locations, "
        f"{group_line_count(group)} lines, severity {group.get('severity', '?')}: "
        + ", ".join(locations)
    )


def resolve_path(repo_root: Path, requested_path: Union[str, Path]) -> Path:
    candidate = Path(requested_path)
    return candidate if candidate.is_absolute() else repo_root / candidate


def baseline_path(repo_root: Path, requested_path: Optional[Union[str, Path]]) -> Path:
    if requested_path in (None, USE_DEFAULT_BASELINE_PATH):
        return repo_root / DEFAULT_DUPS_BASELINE
    return resolve_path(repo_root, requested_path)


def build_baseline_document(
    changed_files: Sequence[str], dups_payload: dict[str, Any]
) -> dict[str, Any]:
    return {
        "version": DUPS_BASELINE_VERSION,
        "created_with": "km_branch.py",
        "changed_files": sorted(changed_files),
        "km_dups": dups_payload,
    }


def read_baseline_groups(path: Path) -> list[DuplicateGroup]:
    try:
        with open(path, "r", encoding=ENCODING) as baseline_file:
            document = json.load(baseline_file)
    except OSError as error:
        raise ToolingError(f"cannot read dups baseline {path}: {error}")
    except json.JSONDecodeError as error:
        raise ToolingError(f"dups baseline {path} is not valid JSON: {error}")
    if "km_dups" in document:
        return document["km_dups"].get("groups", [])
    return document.get("groups", [])


def write_baseline(
    path: Path, changed_files: Sequence[str], dups_payload: dict[str, Any]
) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    document = build_baseline_document(changed_files, dups_payload)
    with open(path, "w", encoding=ENCODING) as baseline_file:
        json.dump(document, baseline_file, indent=2)
        baseline_file.write("\n")
    return path


def compare_against_baseline(
    changed_files: Sequence[str],
    current_groups: Sequence[DuplicateGroup],
    baseline_groups: Sequence[DuplicateGroup],
    repo_root: Path,
) -> tuple[list[DupsRegression], list[DuplicateGroup]]:
    """A regression is a changed file participating in a duplicate group (member set or block
    size) that did not exist at baseline.

    Returns the per-file regressions plus, for context, the groups that touch changed files and
    are not in the baseline at all. Line numbers are deliberately ignored: they shift on every
    edit above a block.
    """
    regressions: list[DupsRegression] = []
    for relative_path in changed_files:
        baseline_signatures = participation_signatures(baseline_groups, relative_path, repo_root)
        current_signatures = participation_signatures(current_groups, relative_path, repo_root)
        new_signatures = sorted(
            current_signatures - baseline_signatures, key=signature_sort_key
        )
        if new_signatures:
            regressions.append((relative_path, new_signatures))

    baseline_group_signatures = {
        group_signature(group, repo_root) for group in baseline_groups
    }
    new_groups = [
        group
        for group in current_groups
        if group_signature(group, repo_root) not in baseline_group_signatures
    ]
    return regressions, groups_touching(new_groups, changed_files, repo_root)


def compare_with_baseline_file(
    repo_root: Path,
    changed_files: Sequence[str],
    current_groups: Sequence[DuplicateGroup],
    baseline: Path,
) -> tuple[list[DupsRegression], list[DuplicateGroup]]:
    baseline_groups = read_baseline_groups(baseline)
    return compare_against_baseline(changed_files, current_groups, baseline_groups, repo_root)


def render_participation(relative_path: str, signature: ParticipationSignature) -> str:
    members, line_count = signature
    partners = sorted(member for member in members if member != relative_path)
    partner_text = ", ".join(f"`{partner}`" for partner in partners) if partners else "same file"
    return f"{line_count} duplicated lines with {partner_text}"


def render_dups_regressions(
    regressions: Sequence[DupsRegression],
    new_groups: Sequence[DuplicateGroup],
    repo_root: Path,
) -> str:
    lines = ["**New duplicate participation by changed files:**"]
    for relative_path, signatures in regressions:
        lines.append(f"- `{relative_path}`:")
        for signature in signatures:
            lines.append(f"  - {render_participation(relative_path, signature)}")
    if new_groups:
        lines.append("")
        lines.append(f"**{len(new_groups)} duplicate group(s) not in the baseline:**")
        lines.extend(render_group(group, repo_root) for group in new_groups)
    return "\n".join(lines)


def baseline_comparison_report(
    regressions: Sequence[DupsRegression],
    new_groups: Sequence[DuplicateGroup],
    repo_root: Path,
    baseline: Path,
) -> str:
    if regressions:
        header = f"Compared against `{baseline}`."
        return header + "\n" + render_dups_regressions(regressions, new_groups, repo_root)
    return f"No new duplicate participation by changed files (baseline: `{baseline}`)."


def report_duplicate_groups(
    repo_root: Path, changed_files: Sequence[str], groups: Sequence[DuplicateGroup]
) -> None:
    touching = groups_touching(groups, changed_files, repo_root)
    if not touching:
        print(f"No duplicate groups touch the {len(changed_files)} changed files.")
        return
    print(f"**{len(touching)} duplicate group(s) touch changed files:**")
    for group in touching:
        print(render_group(group, repo_root))


def command_discover(args: argparse.Namespace) -> int:
    repo_root = find_repo_root()
    base_ref = resolve_base_ref(args.base, repo_root)
    warn_if_stale(base_ref, repo_root)
    changed_files = discover_changed_files(
        repo_root, base_ref, args.include_infra, args.include_untracked_all
    )
    for relative_path in changed_files:
        print(relative_path)
    return EXIT_PASS


def command_score(args: argparse.Namespace) -> int:
    repo_root = find_repo_root()
    ensure_km_available(repo_root)
    base_ref = resolve_base_ref(args.base, repo_root)
    warn_if_stale(base_ref, repo_root)
    print(f"# Kimün branch score (base={base_ref})")
    records, _changed_files, failed = evaluate_branch(repo_root, base_ref)
    print_score_section(records)
    return EXIT_GATE_FAILURE if failed else EXIT_PASS


def command_dups(args: argparse.Namespace) -> int:
    repo_root = find_repo_root()
    ensure_km_available(repo_root)
    base_ref = resolve_base_ref(args.base, repo_root)
    warn_if_stale(base_ref, repo_root)
    changed_files = discover_changed_files(repo_root, base_ref)
    dups_payload = load_dups_payload(repo_root)
    current_groups = dups_payload.get("groups", [])

    if args.baseline is not None:
        return report_dups_against_baseline(args, repo_root, changed_files, current_groups)

    report_duplicate_groups(repo_root, changed_files, current_groups)
    if args.save is not None:
        target = baseline_path(repo_root, args.save)
        write_baseline(target, changed_files, dups_payload)
        print(f"Baseline written to `{target.as_posix()}`.")
    return EXIT_PASS


def report_dups_against_baseline(
    args: argparse.Namespace,
    repo_root: Path,
    changed_files: Sequence[str],
    current_groups: Sequence[DuplicateGroup],
) -> int:
    baseline = resolve_path(repo_root, args.baseline)
    if not baseline.is_file():
        raise ToolingError(f"dups baseline {baseline} does not exist")
    regressions, new_groups = compare_with_baseline_file(
        repo_root, changed_files, current_groups, baseline
    )
    print(baseline_comparison_report(regressions, new_groups, repo_root, baseline))
    return EXIT_GATE_FAILURE if regressions else EXIT_PASS


def command_all(args: argparse.Namespace) -> int:
    repo_root = find_repo_root()
    ensure_km_available(repo_root)
    base_ref = resolve_base_ref(args.base, repo_root)
    warn_if_stale(base_ref, repo_root)

    print(f"# Kimün branch gate (base={base_ref})")
    records, changed_files, failed = evaluate_branch(repo_root, base_ref)
    print_score_section(records)

    print("\n## Duplication")
    dups_payload = load_dups_payload(repo_root)
    current_groups = dups_payload.get("groups", [])
    default_baseline = repo_root / DEFAULT_DUPS_BASELINE
    if not default_baseline.is_file():
        write_baseline(default_baseline, changed_files, dups_payload)
        print(f"Snapshot written to `{default_baseline.as_posix()}` (first run).")
        report_duplicate_groups(repo_root, changed_files, current_groups)
    else:
        regressions, new_groups = compare_with_baseline_file(
            repo_root, changed_files, current_groups, default_baseline
        )
        print(baseline_comparison_report(regressions, new_groups, repo_root, default_baseline))
        failed = bool(regressions) or failed

    return EXIT_GATE_FAILURE if failed else EXIT_PASS


def add_base_argument(subparser: argparse.ArgumentParser) -> None:
    subparser.add_argument(
        "--base",
        metavar="REF",
        default=None,
        help=f"base ref (default: ${BASE_REF_ENV_VAR}, then {', '.join(DEFAULT_BASE_REFS)})",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="km_branch.py",
        description="Branch-scoped Kimün code-health gate.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    discover_parser = subparsers.add_parser(
        "discover", help="list the changed files that km should score"
    )
    add_base_argument(discover_parser)
    discover_parser.add_argument(
        "--include-infra", action="store_true", help="keep .agents/, .claude/, rules/, AGENTS.md"
    )
    discover_parser.add_argument(
        "--include-untracked-all",
        action="store_true",
        help="scan untracked files outside src/",
    )
    discover_parser.set_defaults(handler=command_discover)

    score_parser = subparsers.add_parser(
        "score", help=f"grade changed files and fail below {GRADE_MINIMUM_LABEL}"
    )
    add_base_argument(score_parser)
    score_parser.set_defaults(handler=command_score)

    dups_parser = subparsers.add_parser("dups", help="report or baseline project duplication")
    add_base_argument(dups_parser)
    dups_actions = dups_parser.add_mutually_exclusive_group()
    dups_actions.add_argument(
        "--save",
        nargs="?",
        const=USE_DEFAULT_BASELINE_PATH,
        default=None,
        metavar="FILE",
        help=f"write a baseline, default {DEFAULT_DUPS_BASELINE.as_posix()}",
    )
    dups_actions.add_argument(
        "--baseline", type=Path, default=None, metavar="FILE", help="compare against a baseline"
    )
    dups_parser.set_defaults(handler=command_dups)

    all_parser = subparsers.add_parser("all", help="score plus duplication snapshot")
    add_base_argument(all_parser)
    all_parser.set_defaults(handler=command_all)

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except ToolingError as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_TOOLING_ERROR


if __name__ == "__main__":
    sys.exit(main())
