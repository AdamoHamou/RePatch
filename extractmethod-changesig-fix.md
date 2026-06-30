# Fix: ReplayExtractMethod → ChangeSignatureProcessor NPE (PROCESSOR_THREW)

_Branch `fix/replay-extractmethod-changesig-npe`, based on `fix/2024-source-root-provisioning`
(chain-head + the `[RePatch-DIAG]` probes). For the build-capable test system._

## The bug (traced end to end)

In the `srcroot-fix` validation run, resolution stopped being the blocker and op-execution failures
surfaced — the loudest being `PROCESSOR_THREW=8`:

```
PROCESSOR_THREW replay EXTRACT_OPERATION (createTestTopic(name, replicas, expectedErrorCode) extracted
from createTestTopic(name, replicas) in class ...ReplicationControlTestContext)
  — NullPointerException: Cannot read field "oldParameterIndex" because "info" is null
```

The NPE is inside IntelliJ's `JavaChangeInfoImpl` — it iterates the `ParameterInfoImpl[]` we pass to
`ChangeSignatureProcessor` and reads `info.oldParameterIndex` on **every** element. It throws because
one element is `null`.

Chain (all in `ReplayExtractMethod`):
1. `getParameterInfo` (was line 265) builds `ParameterInfoImpl[]` by matching each expected parameter
   to a PSI parameter with **exact** name+type equality (`umlType.equals(psiType)`). A parameter that
   doesn't match (cosmetic type diff — `int[][]`/varargs/qualifier — or a genuinely new param) leaves
   that array slot **null**.
2. `updateSignature` guarded only `if (parameterInfo[0] != null)` — it checked **only the first slot**,
   so a null at index 1+ went straight into `ChangeSignatureProcessor` → NPE → `PROCESSOR_THREW`.

## The fix (3 small changes, 2 files)

1. **`PsiSearchService.sameType` → `public`** so it can be reused (was package-private). This is the
   tolerant type matcher this branch already hardened for method matching (whitespace / varargs /
   qualifier-depth tolerant).
2. **`ReplayExtractMethod.getParameterInfo`** — match parameter types with `PsiSearchService.sameType`
   instead of exact `equals`. Names are unique in a parameter list, so name still matches exactly; only
   the type comparison is loosened. This stops cosmetic mismatches from leaving null slots — the
   primary cause of the crash.
3. **`ReplayExtractMethod.updateSignature`** — replace the `parameterInfo[0] != null` guard with
   `hasNoNullSlots(...)` (every slot must be populated). If the array still has a null (e.g. a truly
   new parameter that doesn't exist on the PSI method yet), **skip the signature update and log**
   instead of crashing:
   ```
   [RePatch-DIAG] updateSignature skipped for '<name>': N/M parameter slots unmatched
   ```

Layer 2 reduces how often slots go null; layer 3 guarantees we never hand a null-containing array to
the processor again. Together: `PROCESSOR_THREW` (this signature path) should go to 0.

## How to test / what to look for

Re-run the same scenario (`apache/kafka,danielogen/linkedin,12289,MO`, pinned `fdb9fd013a`), grep stdout:
- **`PROCESSOR_THREW`** in the `[PipelineSummary] failures:` line should drop (was 8). The
  `JavaChangeInfoImpl ... oldParameterIndex ... info is null` NPE should be gone.
- Any remaining genuinely-unmatchable extracts now print `[RePatch-DIAG] updateSignature skipped …`
  instead of throwing — a clean, countable skip.
- Watch the verdict: 12289 was `RePatch 1/5/57`. If the signature updates now land correctly,
  POSTCONDITION_BROKEN may also move; compare against base `0/0/0`.

## Not verified here
- **Not compiled on the authoring machine** (SDK + JDK17 toolchain too heavy) — static review only;
  run `compileJava` first.
- The skip path (layer 3) is a safety net, not a correctness win: a skipped signature update leaves the
  extracted method with whatever shape `ExtractMethodProcessor` produced. That may still cause a
  POSTCONDITION_BROKEN for that specific op — but no longer a crash. If those matter, the next step is
  to handle the truly-new-parameter case in `getParameterInfo` (add the parameter rather than match it).
- `provisionSourceRoots` from the base branch still no-ops here (harmless early-return); left in place.
  Strip it if you want a clean branch.
