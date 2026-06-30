# Fix attempt: provision source roots at project open (hypothesis #1)

_Branch `fix/2024-source-root-provisioning`, based on `migration/phase7-12660-chain-head` (`b8d6817`).
For whoever tests this on the build-capable system._

## What this fixes and why

The PSI-diag run (`psi-diag-result.md`) returned a unanimous verdict: **hypothesis #1 — the
auto-opened checkout is a hollow single module with ZERO source roots**, so
`JavaPsiFacade.findClass` never resolves a FQN, every replay/invert op hits its
`if (psiClass == null) return;` guard, the refactoring is silently skipped, and the merge output
is wrong. Indexing was never the problem (`dumb=false` on 100% of lookups; #2 fully ruled out).

That diagnostic ran on the **minimal** branch (`da31719`). This fix is built on **chain-head**,
which is much further along and already has: the IntelliMerge fat jar removed (so the
lang3/JDT shadowing blockers are gone), `registerAndGetRepository` (EDT/VCS-root fix), and a
`runInSmartReadAction` wrapper around every PSI read (so #2 cannot recur). The one thing still
missing is a real module/source-root model — which is what this branch adds.

## The change (3 files, ~184 lines)

1. **`platform/ProjectRootsService.java`** — new `provisionSourceRoots(Project)`:
   walks the checkout once, finds every `**/src/main/java` and `**/src/test/java` directory
   (pruning `.git/.idea/.gradle/build/out/target/bin/node_modules`, depth-capped at 12), and
   registers each as a source folder on the project's module content entry — creating/reusing a
   content root as needed. This is what a real Gradle import would produce (minus the per-module
   split) and is enough for FQN resolution, because each source root maps its package tree.
   Threading mirrors the existing `addSourceRoot` (modifiable model in a read action, commit in a
   write action, then `drainDumbTasks`).

2. **`integration/RePatchIntegration.java`** — call `provisionSourceRoots(this.project)` in
   `openProject(...)`, immediately after `platform.openProject(...)`, before any PSI resolution.

3. **`platform/PsiSearchService.java`** — self-measuring `[RePatch-DIAG]` probes in `findClass`:
   logs which of the three strategies resolved (`facade` / `vfsPath` / `index` / `none`) plus
   `base=` (confirms the project opened at the checkout, not the IDE install dir). The bare
   `FAILED HERE` print is upgraded to a tagged, informative line.

## How to test it

Run the same scenario as the diag run (`apache/kafka,danielogen/linkedin,12289,MO`, pinned
`fdb9fd013a`), isolated DB. Then `grep "[RePatch-DIAG]"` on stdout.

**Success looks like:**
- `provisionSourceRoots: ... discovered main=<N> test=<M> newlyAdded=<K>` with N/M/K > 0
  (kafka should discover dozens of `src/main/java` + `src/test/java` dirs).
- `findClass(...) result=RESOLVED via=facade` becomes the common case (was `facade=0/234` before).
- The merge_result DB row gets written for 12289, and the apply path stops silently skipping.

**If it still fails**, the probes localize it:
- `findClass ... via=vfsPath` resolving but processors still failing → the PSI handle resolves but
  the refactoring *processor* needs more of the module model (scope/deps), not just source roots.
- `provisionSourceRoots ... newlyAdded=0` → the scan found roots but couldn't attach them
  (content-entry intersect, or wrong base) — check the `base=` value and module count lines.
- `discovered main=0 test=0` → the checkout layout isn't `src/{main,test}/java` (unlikely for
  kafka) or `getBasePath()` is wrong again.

## NOT verified here / known risks

- **Not compiled on this machine** — the build pulls the full 2024.3.7 SDK and provisions JDK 17;
  impractical in the authoring session. Static review only. Please `compileJava` first.
- **`addContentEntry` intersect**: handled for the exact-match and ancestor cases; a content root
  that is a *descendant* of the checkout root (a single nested src dir already registered) is not
  handled and would throw — not expected for a hollow module, but watch for it.
- **Scala/Kotlin sources**: only `src/{main,test}/java` are registered (kafka core is Scala, but
  RefactoringMiner/PSI here operate on Java; revisit if a Java class fails to resolve because its
  package also spans a non-java root).
- This is a **direct source-root registration**, not a true Gradle sync. It will not reproduce the
  exact 43/129-module model, but it should be sufficient for FQN resolution. If processors still
  misbehave, the next escalation is a real `ExternalSystemUtil.refreshProject` Gradle import
  (requires bundling `org.jetbrains.plugins.gradle`).
