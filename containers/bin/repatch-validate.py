#!/usr/bin/env python3
"""RePatch 2.0 validation-study harness (levels 2-3 of the study design).

Level 1 (conflict-free integration) is produced by the evaluation pipeline
and read back here from a run database. This harness adds, per case whose
integration is conflict-free:

  baseline:  checkout of the pre-integration target revision -> build -> test
  post:      the exact RePatch-produced target state          -> build -> test

and classifies every case into the study's final outcome enum
(VALID | INTEGRATION_FAIL | BUILD_FAIL | TEST_FAIL | INCONCLUSIVE), keeping
the stage-wise outcomes as separate fields. One machine-readable JSON record
per case (dataset.jsonl); raw build/test logs are kept per case.

Tests that already fail at the baseline are never counted as RePatch
regressions: only newly failing tests (post minus baseline) matter.

Subcommands:
  records  --db DB --out DIR       build/refresh dataset.jsonl from a run DB
  validate --db DB --out DIR ...   run levels 2-3 for eligible cases
  status   --out DIR               progress summary of a dataset

Invoked by the `repatch` CLI (which exports DATA/RESULTS/TEMPLATE/
CLONE_PARENT and starts MySQL); not intended to run standalone.
"""

import argparse
import datetime
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

DATA = Path(os.environ.get("DATA", str(Path.home() / "data")))
RESULTS = Path(os.environ.get("RESULTS", str(Path.home() / "results")))
TEMPLATE = Path(os.environ.get("TEMPLATE", str(DATA / "template")))
CLONE_PARENT = Path(os.environ.get("CLONE_PARENT", str(DATA / "run")))
VALIDATE_PARENT = Path(os.environ.get("VALIDATE_PARENT", str(DATA / "validate")))

MYSQL = ["mysql", "-h", os.environ.get("DB_HOST", "127.0.0.1"),
         "-u", os.environ.get("JDBC_USER", "repatch"),
         "-p" + os.environ.get("JDBC_PASSWORD", "repatch")]

BUILD_TIMEOUT = int(os.environ.get("RP_VAL_BUILD_TIMEOUT", "3600"))
TEST_TIMEOUT = int(os.environ.get("RP_VAL_TEST_TIMEOUT", "14400"))

# First JDK to try per project. The target revision is the fork's live
# HEAD, so actively developed forks want a modern JDK while dormant ones
# are stuck in their era; a wrong guess costs one failed build before the
# ladder walks the other baked JDKs. Overridable with --jdk.
JDK_DEFAULT = {
    "linkedin-kafka": "11",              # 2.4-based fork, JDK 11 proven
    "apache-kafka": "17",                # live trunk
    "bisq-network-bitcoinj": "17",       # gradle 8.x era
    "langerhans-dogecoinj-new": "8",     # dormant 2015 fork
    "eisop-checker-framework": "17",
    "typetools-checker-framework": "17",
    "ufal-clarin-dspace": "8",           # DSpace 5/6 era
    "DSpace-DSpace": "17",               # live mainline
    "tulipcc-ParserGeneratorCC": "8",
    "Willena-sqlite-jdbc-crypt": "11",
}
JDK_HOMES = {
    "8": "/usr/lib/jvm/temurin-8-jdk-amd64",
    "11": "/usr/lib/jvm/temurin-11-jdk-amd64",
    "17": "/opt/java/openjdk",
}

FINAL_OUTCOMES = ("VALID", "INTEGRATION_FAIL", "BUILD_FAIL", "TEST_FAIL",
                  "INCONCLUSIVE", "PENDING")


def log(msg):
    print("[validate] %s" % msg, flush=True)


def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def sql(db, query):
    """Run a query, return rows as lists of strings (tab-separated, -N -B)."""
    out = subprocess.run(MYSQL + ["-N", "-B", "-e", query, db],
                         capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError("mysql failed: %s" % out.stderr.strip())
    return [line.split("\t") for line in out.stdout.splitlines()]


def repo_dirname(url):
    """<Owner>-<RepoName> — must match RepoNaming.java."""
    parts = url.rstrip("/").split("/")
    name = parts[-1]
    if name.endswith(".git"):
        name = name[:-4]
    return "%s-%s" % (parts[-2], name)


# ---------------------------------------------------------------- records

def load_dataset(out_dir):
    path = Path(out_dir) / "dataset.jsonl"
    records = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if line.strip():
                rec = json.loads(line)
                records[rec["case_id"]] = rec
    return records


def save_dataset(out_dir, records):
    path = Path(out_dir) / "dataset.jsonl"
    tmp = path.with_suffix(".jsonl.tmp")
    with tmp.open("w") as f:
        for cid in sorted(records):
            f.write(json.dumps(records[cid], sort_keys=True) + "\n")
    tmp.replace(path)


def classify(rec):
    """Final study classification from the stage-wise fields. Mutually
    exclusive; the detailed diagnostics stay in the record."""
    integ = rec["integration"]["status"]
    if integ == "CONFLICTED":
        return "INTEGRATION_FAIL", "integration", None
    if integ == "TOOL_TIMEOUT":
        return "INCONCLUSIVE", "infrastructure", "tool_timeout"
    if integ == "SKIPPED_NO_SCENARIO":
        return "INCONCLUSIVE", "infrastructure", "pr_metadata_unavailable"
    if integ == "NOT_RUN":
        return "PENDING", None, None
    # conflict-free (CONFLICT_FREE by RePatch, or GIT_CLEAN by plain git)
    base, post = rec.get("baseline") or {}, rec.get("post") or {}
    cause = (post.get("inconclusive_cause") or base.get("inconclusive_cause"))
    if cause:
        return "INCONCLUSIVE", "infrastructure", cause
    # A baseline that does not build cannot support a defensible build/test
    # comparison (study section 4) — checked before the pending-post case,
    # because a failed baseline legitimately has no post record at all.
    if base.get("build") in ("FAIL", "TIMEOUT", "UNSUPPORTED"):
        return "INCONCLUSIVE", "infrastructure", \
            "baseline_build_%s" % base["build"].lower()
    if not post.get("build"):
        return "PENDING", None, None
    if post["build"] == "FAIL":
        return "BUILD_FAIL", "build", None
    if post["build"] != "PASS":
        return "INCONCLUSIVE", "infrastructure", "build_%s" % post["build"].lower()
    if post.get("tests") in (None, "SKIPPED"):
        return "PENDING", None, None
    for side in (base, post):
        if side.get("tests") in ("ERROR", "TIMEOUT"):
            return "INCONCLUSIVE", "infrastructure", "test_harness_%s" % side["tests"].lower()
    if rec["post"].get("newly_failing_tests"):
        return "TEST_FAIL", "test", None
    return "VALID", None, None


def reclassify(rec):
    outcome, stage, cause = classify(rec)
    rec["validation"] = rec.get("validation") or {}
    rec["validation"].update({
        "final_outcome": outcome,
        "failure_stage": stage,
        "inconclusive_cause": cause,
    })


def cmd_records(args):
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    records = load_dataset(out_dir)

    rows = sql(args.db, """
        SELECT pj.source_url, pj.fork_url, p.number, IFNULL(p.patch_type,''),
               p.is_done, p.is_conflicting,
               IFNULL(mc.id,''), IFNULL(mc.commit_hash,''),
               IFNULL(mc.parent_1,''), IFNULL(mc.parent_2,''),
               IFNULL(mc.is_conflicting,''), IFNULL(mc.is_done,'')
        FROM patch p JOIN project pj ON p.project_id = pj.id
        LEFT JOIN merge_commit mc
               ON mc.patch_id = p.id AND mc.project_id = pj.id
        ORDER BY pj.id, p.number""")
    verdicts = {}
    for mcid, tool, files, confl, loc, rt in sql(args.db, """
        SELECT mr.merge_commit_id, mr.merge_tool, mr.total_conflicting_files,
               mr.total_conflicts, mr.total_conflicting_loc, mr.runtime
        FROM merge_result mr"""):
        verdicts.setdefault(mcid, {})[tool] = {
            "files": int(files), "conflicts": int(confl), "loc": int(loc),
            "runtime_ms": int(rt)}

    n_new = 0
    for (src, fork, number, ptype, p_done, p_confl,
         mcid, mchash, left, right, mc_confl, mc_done) in rows:
        dirname = repo_dirname(fork)
        case_id = "%s__pr%s" % (dirname, number)
        rp = verdicts.get(mcid, {}).get("RePatch")
        git = verdicts.get(mcid, {}).get("Git-CherryPick")

        if not mcid:
            status = "SKIPPED_NO_SCENARIO" if p_done == "1" else "NOT_RUN"
        elif mc_confl == "0":
            status = "GIT_CLEAN"
        elif rp is None:
            status = "NOT_RUN"
        elif rp["files"] < 0:
            status = "TOOL_TIMEOUT"
        elif rp["files"] == 0:
            status = "CONFLICT_FREE"
        else:
            status = "CONFLICTED"

        results_dir = str(RESULTS / dirname / ("commit%s" % mcid)) if mcid else None
        state = {}
        if results_dir and (Path(results_dir) / "repatch-state.json").exists():
            state = json.loads((Path(results_dir) / "repatch-state.json").read_text())

        rec = records.get(case_id, {})
        prior = {k: rec.get(k) for k in ("baseline", "post")}  # keep level 2-3 work
        rec.update({
            "case_id": case_id,
            "source_repo": src,
            "target_repo": fork,
            "pr_number": int(number),
            "patch_type": ptype,
            "source_revision": right or None,
            "target_revision": left or None,
            "base_revision": state.get("base_commit"),
            "integration": {
                "status": status,
                "repatch_verdict": rp,
                "git_verdict": git,
                "runtime_ms": rp["runtime_ms"] if rp else None,
                "result_sha": state.get("result_sha"),
                "bundle": (results_dir + "/repatch-state.bundle")
                          if state.get("bundle") else None,
            },
            "provenance": {
                "run_db": args.db,
                "results_dir": results_dir,
                "merge_commit_row": int(mcid) if mcid else None,
                "records_refreshed_at": now_iso(),
            },
        })
        for k, v in prior.items():
            if v is not None:
                rec[k] = v
        reclassify(rec)
        records[case_id] = rec
        n_new += 1

    save_dataset(out_dir, records)
    counts = {}
    for rec in records.values():
        counts[rec["integration"]["status"]] = counts.get(rec["integration"]["status"], 0) + 1
    log("dataset.jsonl: %d cases (%s)" % (
        len(records), ", ".join("%s=%d" % kv for kv in sorted(counts.items()))))
    return 0


# ---------------------------------------------------------------- git/build

def run(cmd, cwd, log_file=None, timeout=None, env=None):
    """Run a command; tee output to log_file. Returns (rc, timed_out)."""
    lf = open(log_file, "a") if log_file else subprocess.DEVNULL
    try:
        if log_file:
            lf.write("\n$ %s\n" % " ".join(cmd))
            lf.flush()
        proc = subprocess.Popen(cmd, cwd=str(cwd), stdout=lf, stderr=lf,
                                env=env, start_new_session=True)
        try:
            rc = proc.wait(timeout=timeout)
            return rc, False
        except subprocess.TimeoutExpired:
            import signal
            os.killpg(proc.pid, signal.SIGKILL)
            proc.wait()
            return -9, True
    finally:
        if log_file:
            lf.close()


def git(clone, *argv):
    out = subprocess.run(["git", "-C", str(clone)] + list(argv),
                         capture_output=True, text=True)
    return out.returncode, out.stdout.strip(), out.stderr.strip()


def has_commit(clone, sha):
    return git(clone, "cat-file", "-e", sha + "^{commit}")[0] == 0


def ensure_scratch_clone(dirname):
    """A dedicated build/test clone per project so validation never touches
    the pipeline's working or template clones."""
    scratch = VALIDATE_PARENT / dirname
    if (scratch / ".git").is_dir():
        return scratch
    src = None
    if dirname == "linkedin-kafka" and (TEMPLATE / ".git").is_dir():
        src = TEMPLATE
    elif (CLONE_PARENT / dirname / ".git").is_dir():
        src = CLONE_PARENT / dirname
    if src is None:
        return None
    VALIDATE_PARENT.mkdir(parents=True, exist_ok=True)
    log("cloning scratch copy of %s (one-time)" % dirname)
    rc = subprocess.run(["git", "clone", "--no-checkout", str(src), str(scratch)]).returncode
    if rc != 0:
        shutil.rmtree(scratch, ignore_errors=True)
        return None
    return scratch


def checkout_clean(clone, sha):
    git(clone, "cherry-pick", "--abort")
    git(clone, "reset", "--hard")
    # -x is omitted on purpose: build outputs are gitignored and keeping
    # them is what makes per-case incremental builds cheap.
    git(clone, "clean", "-fd")
    rc, _, err = git(clone, "checkout", "--detach", "-f", sha)
    return rc == 0, err


def prepare_post_state(rec, clone, log_file):
    """Bring the scratch clone to the exact RePatch-produced target state.
    Returns (sha, inconclusive_cause)."""
    integ = rec["integration"]
    if integ["status"] == "CONFLICT_FREE":
        bundle = integ.get("bundle")
        if not bundle or not Path(bundle).exists():
            return None, "missing_result_state"
        sha = integ.get("result_sha")
        if not has_commit(clone, sha or "0" * 40):
            rc, _, err = git(clone, "fetch", bundle,
                             "HEAD:refs/repatch/%s" % rec["case_id"])
            if rc != 0:
                Path(log_file).write_text("bundle fetch failed: %s\n" % err)
                return None, "bundle_fetch_failed"
        if not sha or not has_commit(clone, sha):
            return None, "missing_result_state"
        return sha, None
    # GIT_CLEAN: the patch applied with plain cherry-pick; reconstruct it
    # (deterministic given the pinned parents).
    left, right = rec["target_revision"], rec["source_revision"]
    for missing in (s for s in (left, right) if not has_commit(clone, s)):
        for remote, _, _ in [r.partition("\t") for r in
                             git(clone, "remote")[1].splitlines()]:
            if git(clone, "fetch", remote, missing)[0] == 0:
                break
        if not has_commit(clone, missing):
            return None, "missing_objects"
    ok, err = checkout_clean(clone, left)
    if not ok:
        return None, "checkout_failed"
    rc, _, err = git(clone, "cherry-pick", "--allow-empty", right)
    if rc != 0 and "is a merge" in err:
        git(clone, "cherry-pick", "--abort")
        rc, _, err = git(clone, "cherry-pick", "--allow-empty", "-m", "1", right)
    if rc != 0:
        # Three distinct non-clean outcomes, kept apart because they mean
        # different things for the study:
        # - no unmerged files at all: the cherry-pick is REDUNDANT — the
        #   patch content is already present in the target (git stops with
        #   "nothing to commit"; --allow-empty only covers originally-empty
        #   commits). The integrated state degenerates to the baseline.
        # - non-Java unmerged only: consistent with the pipeline's
        #   Java-scoped clean verdict, but there is no well-defined
        #   integrated state to build.
        # - Java unmerged: contradicts the pipeline's verdict — a real
        #   anomaly worth investigating.
        unmerged = git(clone, "diff", "--name-only", "--diff-filter=U")[1].splitlines()
        git(clone, "cherry-pick", "--abort")
        git(clone, "cherry-pick", "--quit")
        Path(log_file).write_text("cherry-pick reconstruction failed: %s\nunmerged:\n%s\n"
                                  % (err, "\n".join(unmerged)))
        if not unmerged:
            return None, "patch_already_integrated"
        if not any(p.endswith(".java") for p in unmerged):
            return None, "non_java_unmerged"
        return None, "reconstruction_mismatch"
    return git(clone, "rev-parse", "HEAD")[1], None


def detect_build_system(clone):
    if (clone / "gradlew").exists():
        return "gradlew"
    if (clone / "pom.xml").exists():
        return "maven"
    if (clone / "build.gradle").exists():
        return "gradle"
    return None


def gradle_modules(clone):
    """Gradle project paths from settings.gradle, e.g. 'core', 'connect:api'.
    include statements span lines (kafka lists one module per comma-continued
    line), so collect each statement until its continuation ends."""
    mods = set()
    settings = clone / "settings.gradle"
    if not settings.exists():
        return mods
    collecting = False
    statement = []
    for line in settings.read_text(errors="replace").splitlines():
        stripped = line.strip()
        if re.match(r"include[\s(]", stripped):
            collecting = True
            statement = [stripped]
        elif collecting:
            statement.append(stripped)
        if collecting and not stripped.endswith(","):
            mods.update(re.findall(r"['\"]:?([\w:.\-]+)['\"]",
                                   " ".join(statement)))
            collecting = False
    return mods


def test_scope(rec, clone, scope_flag):
    """Task/target list for the test stage. Module scoping is only applied
    to gradle multi-module projects (kafka-sized); everything else runs the
    full suite."""
    system = detect_build_system(clone)
    if scope_flag == "none":
        return system, None, "none"
    if system in ("gradlew", "gradle") and scope_flag in ("auto", "module"):
        mods = gradle_modules(clone)
        if mods:
            changed = git(clone, "diff", "--name-only",
                          rec["target_revision"],
                          rec["integration"]["result_sha"] or "HEAD")[1].splitlines()
            hit, unmatched = set(), False
            for path in changed:
                best = ""
                for mod in mods:
                    prefix = mod.replace(":", "/") + "/"
                    if path.startswith(prefix) and len(mod) > len(best):
                        best = mod
                if best:
                    hit.add(best)
                elif path.endswith((".java", ".scala", ".kt")):
                    # A JVM source file outside every module means the scope
                    # is genuinely unknown -> full suite. Anything else
                    # (docs, checkstyle configs, root build scripts, python
                    # system tests) selects no gradle test module and must
                    # not force a multi-hour full-suite fallback.
                    unmatched = True
            if hit and not unmatched:
                tasks = [":%s:test" % m for m in sorted(hit)]
                return system, tasks, "module:" + ",".join(sorted(hit))
            if not hit and not unmatched:
                # No JVM source changed anywhere: with a passing build,
                # existing tests are trivially unaffected. Record that
                # instead of burning hours on a full suite.
                return system, [], "no-jvm-source-changed"
    return system, ["test"] if system != "maven" else None, "full"


def build_env(jdk):
    env = dict(os.environ)
    home = JDK_HOMES.get(jdk)
    if home and Path(home).exists():
        env["JAVA_HOME"] = home
        env["PATH"] = home + "/bin:" + env.get("PATH", "")
    env["GRADLE_USER_HOME"] = str(DATA / "gradle-validate")
    return env


def clear_stale_test_results(clone):
    for pattern in ("build/test-results", "target/surefire-reports"):
        for d in clone.glob("**/" + pattern):
            shutil.rmtree(d, ignore_errors=True)


def parse_junit_results(clone):
    """(total, failing ids) from gradle test-results and surefire XMLs."""
    total, failing = 0, set()
    xmls = list(clone.glob("**/build/test-results/**/*.xml")) + \
           list(clone.glob("**/target/surefire-reports/TEST-*.xml"))
    for path in xmls:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            total += 1
            if case.find("failure") is not None or case.find("error") is not None:
                failing.add("%s#%s" % (case.get("classname", "?"),
                                       case.get("name", "?")))
    return total, sorted(failing)


def find_gradle():
    """System-gradle fallback for wrapper-less projects: RP_GRADLE_BIN, a
    gradle on PATH, or the wrapper dist already cached in the data volume."""
    if os.environ.get("RP_GRADLE_BIN"):
        return os.environ["RP_GRADLE_BIN"]
    if shutil.which("gradle"):
        return "gradle"
    dists = sorted((DATA / "gradle-kafka" / "wrapper" / "dists").glob("*/*/*/bin/gradle"))
    return str(dists[-1]) if dists else None


def run_build(clone, system, jdk, log_file):
    env = build_env(jdk)
    if system == "gradlew":
        cmd = ["./gradlew", "--no-daemon", "--continue", "testClasses"]
    elif system == "gradle":
        gradle = find_gradle()
        if gradle is None:
            return "UNSUPPORTED"
        cmd = [gradle, "--no-daemon", "--continue", "testClasses"]
    elif system == "maven":
        cmd = ["mvn", "-B", "-DskipTests", "-Dmaven.javadoc.skip=true",
               "-Dmaven.repo.local=%s" % (DATA / "m2-validate"), "test-compile"]
    else:
        return "UNSUPPORTED"
    rc, timed_out = run(cmd, clone, log_file, BUILD_TIMEOUT, env)
    if timed_out:
        return "TIMEOUT"
    return "PASS" if rc == 0 else "FAIL"


def run_tests(clone, system, tasks, jdk, log_file):
    """Returns (status, total, failing). Failures are read from the JUnit
    XMLs, so a non-zero exit with parsed results still counts as a completed
    test stage."""
    if tasks == []:
        # 'no-jvm-source-changed' sentinel: nothing to execute, and an
        # empty gradle invocation would run the default tasks instead.
        return "DONE", 0, []
    env = build_env(jdk)
    clear_stale_test_results(clone)
    if system in ("gradlew", "gradle"):
        base = "./gradlew" if system == "gradlew" else find_gradle()
        if base is None:
            return "ERROR", 0, []
        cmd = [base, "--no-daemon", "--continue"] + (tasks or ["test"])
    elif system == "maven":
        cmd = ["mvn", "-B", "-Dmaven.test.failure.ignore=true",
               "-Dmaven.repo.local=%s" % (DATA / "m2-validate"), "test"]
    else:
        return "ERROR", 0, []
    rc, timed_out = run(cmd, clone, log_file, TEST_TIMEOUT, env)
    total, failing = parse_junit_results(clone)
    if timed_out:
        return "TIMEOUT", total, failing
    if rc != 0 and total == 0:
        return "ERROR", 0, []
    return "DONE", total, failing


# ---------------------------------------------------------------- validate

def baseline_cache_key(dirname, left, jdk, scope_key):
    import hashlib
    h = hashlib.sha1(scope_key.encode()).hexdigest()[:8]
    return "%s__%s__jdk%s__%s" % (dirname, (left or "none")[:12], jdk, h)


def validate_case(rec, args, out_dir, baseline_cache):
    case_id = rec["case_id"]
    dirname = repo_dirname(rec["target_repo"])
    jdk = args.jdk or JDK_DEFAULT.get(dirname, "11")
    log_dir = out_dir / "logs" / case_id
    log_dir.mkdir(parents=True, exist_ok=True)

    def inconclusive(side, cause):
        rec[side] = {"inconclusive_cause": cause}
        log("%s: %s (%s)" % (case_id, cause, side))

    clone = ensure_scratch_clone(dirname)
    if clone is None:
        inconclusive("post", "no_provisioned_clone")
        return

    # 1. Post state first: the test scope is derived from what changed.
    sha, cause = prepare_post_state(rec, clone, log_dir / "prepare.log")
    if cause:
        inconclusive("post", cause)
        return
    rec["integration"]["result_sha"] = sha
    ok, err = checkout_clean(clone, sha)
    if not ok:
        inconclusive("post", "checkout_failed")
        return
    system, tasks, scope_key = test_scope(rec, clone, args.test_scope)
    if system is None:
        inconclusive("post", "build_system_unsupported")
        return

    # 2. Baseline (cached per project+revision+jdk+scope: every case of a
    #    project shares the same pre-integration target revision).
    ck = baseline_cache_key(dirname, rec["target_revision"], jdk, scope_key)
    if ck in baseline_cache and not args.redo:
        rec["baseline"] = dict(baseline_cache[ck])
        rec["baseline"]["cached"] = True
    else:
        ok, err = checkout_clean(clone, rec["target_revision"])
        if not ok:
            inconclusive("baseline", "checkout_failed")
            return
        # The target revision is the fork's live HEAD, so its toolchain era
        # is unknown up front: try the family default, then walk the other
        # baked JDKs. The post build reuses whichever JDK the baseline chose.
        ladder = [jdk] if args.jdk else \
            [jdk] + [j for j in ("17", "11", "8") if j != jdk]
        b = None
        for j in ladder:
            log("%s: baseline build (jdk %s, %s)" % (case_id, j, system))
            status = run_build(clone, system, j, log_dir / "baseline-build.log")
            b = {"build": status, "jdk": j, "build_system": system}
            if status != "FAIL":
                break
        if b["build"] == "PASS" and args.test_scope != "none":
            log("%s: baseline tests (%s)" % (case_id, scope_key))
            status, total, failing = run_tests(clone, system, tasks, jdk,
                                               log_dir / "baseline-test.log")
            b.update({"tests": status, "tests_total": total,
                      "failing_tests": failing})
        elif b["build"] == "PASS":
            b["tests"] = "SKIPPED"
        rec["baseline"] = b
        baseline_cache[ck] = {k: v for k, v in b.items() if k != "cached"}
        cache_path = out_dir / "baseline-cache.json"
        cache_path.write_text(json.dumps(baseline_cache, indent=1, sort_keys=True))
    if rec["baseline"]["build"] != "PASS":
        return  # classified INCONCLUSIVE (baseline_build_fail) or pending
    jdk = rec["baseline"].get("jdk", jdk)  # ladder may have picked another

    # 3. Post build + tests.
    ok, err = checkout_clean(clone, sha)
    if not ok:
        inconclusive("post", "checkout_failed")
        return
    log("%s: post-RePatch build (jdk %s, %s)" % (case_id, jdk, system))
    p = {"build": run_build(clone, system, jdk, log_dir / "post-build.log"),
         "jdk": jdk, "build_system": system}
    if p["build"] == "PASS" and args.test_scope != "none":
        log("%s: post-RePatch tests (%s)" % (case_id, scope_key))
        status, total, failing = run_tests(clone, system, tasks, jdk,
                                           log_dir / "post-test.log")
        p.update({"tests": status, "tests_total": total, "failing_tests": failing})
        if status == "DONE" and rec["baseline"].get("tests") == "DONE":
            base_failing = set(rec["baseline"].get("failing_tests") or [])
            p["newly_failing_tests"] = sorted(set(failing) - base_failing)
    elif p["build"] == "PASS":
        p["tests"] = "SKIPPED"
    rec["post"] = p
    rec.setdefault("validation", {})
    rec["validation"]["test_scope"] = scope_key
    rec["validation"]["validated_at"] = now_iso()


def cmd_validate(args):
    out_dir = Path(args.out)
    records = load_dataset(out_dir)
    if not records:
        log("no dataset.jsonl in %s — run 'repatch validate --records' first" % out_dir)
        return 1
    cache_path = out_dir / "baseline-cache.json"
    baseline_cache = json.loads(cache_path.read_text()) if cache_path.exists() else {}

    wanted = None
    if args.cases:
        wanted = set(args.cases.split(","))
    if args.prs:
        prs = set(args.prs.split(","))
        wanted = {cid for cid in records if cid.rsplit("pr", 1)[-1] in prs}

    eligible = []
    for cid in sorted(records):
        rec = records[cid]
        if wanted is not None and cid not in wanted:
            continue
        if rec["integration"]["status"] not in ("CONFLICT_FREE", "GIT_CLEAN"):
            continue
        if not args.redo and rec.get("validation", {}).get("final_outcome") \
                not in (None, "PENDING"):
            continue
        eligible.append(cid)
    if args.limit:
        eligible = eligible[:args.limit]
    log("%d cases to validate (of %d in dataset)" % (len(eligible), len(records)))

    done = 0
    for cid in eligible:
        rec = records[cid]
        try:
            validate_case(rec, args, out_dir, baseline_cache)
        except Exception as e:
            log("%s: harness error: %s" % (cid, e))
            rec.setdefault("post", {})["inconclusive_cause"] = "harness_error"
        reclassify(rec)
        records[cid] = rec
        save_dataset(out_dir, records)  # checkpoint after every case
        done += 1
        log("%s -> %s  [%d/%d]" % (cid, rec["validation"]["final_outcome"],
                                   done, len(eligible)))
    return 0


def cmd_status(args):
    records = load_dataset(Path(args.out))
    if not records:
        log("no dataset at %s" % args.out)
        return 1
    by_outcome, by_status = {}, {}
    for rec in records.values():
        o = rec.get("validation", {}).get("final_outcome") or "PENDING"
        by_outcome[o] = by_outcome.get(o, 0) + 1
        s = rec["integration"]["status"]
        by_status[s] = by_status.get(s, 0) + 1
    print("cases: %d" % len(records))
    print("integration: " + ", ".join("%s=%d" % kv for kv in sorted(by_status.items())))
    print("final:       " + ", ".join("%s=%d" % kv for kv in sorted(by_outcome.items())))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("records", "validate", "status"):
        p = sub.add_parser(name)
        p.add_argument("--out", required=True)
        if name != "status":
            p.add_argument("--db", required=True)
        if name == "validate":
            p.add_argument("--cases")
            p.add_argument("--prs")
            p.add_argument("--test-scope", default="auto",
                           choices=["auto", "module", "full", "none"])
            p.add_argument("--jdk", choices=sorted(JDK_HOMES))
            p.add_argument("--redo", action="store_true")
            p.add_argument("--limit", type=int)
    args = ap.parse_args()
    return {"records": cmd_records, "validate": cmd_validate,
            "status": cmd_status}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
