# Stack Research — IntelliJ Platform 2.x Migration

**Research date:** 2026-04-23
**Overall confidence:** MEDIUM — core DSL shape stable; version numbers need live verification before pinning.

## Recommended Migration Stack

| Tool | From (current) | To (target) | Notes |
|------|---------------|-------------|-------|
| Gradle plugin ID | `org.jetbrains.intellij` 0.7.3 | `org.jetbrains.intellij.platform` 2.x | Different coordinate — a rewrite, not a bump. Skip 1.x entirely. |
| Gradle wrapper | 6.8 | 8.5+ (8.10 recommended) | Plugin 2.x requires Gradle ≥ 8.2 |
| JDK (build) | 11 | **17** (minimum) | Platform 2022.3+ is compiled with JDK 17; plugin 2.x enforces this |
| `java.toolchain` | `sourceCompatibility = 1.11` | `java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }` | Typed toolchain for reproducibility |
| IntelliJ target | `IU-2020.1.2` | `IC-2024.2.4` (single target first) | Narrow range first; widen after green verifier |
| Dependency configs | `compile`/`testCompile` | `implementation`/`testImplementation`/`testRuntimeOnly` | Removed in Gradle 7+ |
| Plugin descriptor patching | `patchPluginXml { ... }` | `intellijPlatform { pluginConfiguration { ... } }` | Task still named `patchPluginXml`; config surface moves |
| Run task | `runIde { jvmArgs = ... }` | `intellijPlatformTesting { runIde { register('...') { ... } } }` | Named-run DSL; models headless mode too |
| Plugin verifier | `runPluginVerifier` (1.x) | `verifyPlugin` (2.x) | First-class in 2.x; task renamed |
| Repositories | `repositories { mavenCentral() }` | + `intellijPlatform { defaultRepositories() }` | One call registers JetBrains + Marketplace + CDN |
| ActiveJDBC Gradle plugin | 1.2 | Same — **spike required** | Gradle 8.x compatibility unknown — flag for A1 |

## Build System Changes

### Before (current build.gradle conceptual shape)
```groovy
plugins {
    id 'java'
    id 'org.jetbrains.intellij' version '0.7.3'
}
sourceCompatibility = 1.11
intellij {
    version '2020.1.2'
    plugins = ['git4idea', 'java']
}
dependencies {
    compile 'org.eclipse.jgit:...'
    testCompile 'junit:junit:4.12'
}
patchPluginXml { sinceBuild '201'; untilBuild '201.*' }
runIde { jvmArgs '-Xss100m', '-Xmx16g' }
```

### After (IntelliJ Platform Gradle Plugin 2.x)
```groovy
plugins {
    id 'java'
    id 'org.jetbrains.intellij.platform' version '2.1.0'  // verify latest
    id 'de.schablinski.activejdbc-gradle-plugin' version '1.2'
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity('2024.2.4')     // verify current stable build
        bundledPlugin('Git4Idea')             // case matters
        bundledPlugin('com.intellij.java')
        pluginVerifier()
        zipSigner()
        instrumentationTools()
    }
    implementation 'org.eclipse.jgit:org.eclipse.jgit:5.10.0'
    implementation 'com.github.tsantalis:refactoring-miner:2.1.0'
    testImplementation 'junit:junit:4.12'
    testImplementation 'org.mockito:mockito-core:2.1.0'
}

intellijPlatform {
    pluginConfiguration {
        id = 'edu.unlv.cs.evol.repatch'
        name = 'RePatch'
        version = project.version.toString()
        ideaVersion {
            sinceBuild = '242'
            untilBuild = '243.*'
        }
    }
    pluginVerification {
        ides { recommended() }
    }
    buildSearchableOptions = false
}

intellijPlatformTesting {
    runIde {
        register('runIde') {
            task { jvmArgumentProviders.add({ ['-Xss100m', '-Xmx16g'] } as CommandLineArgumentProvider) }
        }
        register('runIntegrationPipeline') {
            task {
                args = ['integration',
                        (project.findProperty('dataPath') ?: '').toString(),
                        (project.findProperty('evaluationProject') ?: '').toString()]
                jvmArgumentProviders.add({ ['-Xss100m', '-Xmx16g'] } as CommandLineArgumentProvider)
            }
        }
    }
}
```

## patchPluginXml Mapping

| 1.x `patchPluginXml` property | 2.x location |
|-------------------------------|-------------|
| `sinceBuild` | `pluginConfiguration.ideaVersion.sinceBuild` |
| `untilBuild` | `pluginConfiguration.ideaVersion.untilBuild` |
| `version` | `pluginConfiguration.version` |
| `pluginDescription` | `pluginConfiguration.description` |
| `changeNotes` | `pluginConfiguration.changeNotes` |
| plugin id | `pluginConfiguration.id` |

## Dependency Declaration Model

```
dependencies {
  intellijPlatform {
    intellijIdeaCommunity(version)     // target IDE
    bundledPlugin(id)                  // e.g. 'Git4Idea', 'com.intellij.java'
    plugin(id, version)                // marketplace 3rd-party
    pluginVerifier()                   // pulls verifier binary
    zipSigner()                        // signs plugin ZIP
    instrumentationTools()             // compile-time UI/form instrumentation
    testFramework(TestFrameworkType.Platform)     // BasePlatformTestCase etc.
    testFramework(TestFrameworkType.Plugin.Java)  // Java PSI fixtures
  }
}
```

`defaultRepositories()` in `repositories {}` block registers:
- JetBrains releases repo (IDE artifacts)
- JetBrains CDN cache
- JetBrains Marketplace (3rd-party plugin deps)

## Bundled Plugin IDs RePatch Needs

| Usage | Bundled plugin ID |
|-------|------------------|
| `git4idea.*` imports | `Git4Idea` (capital G, capital I — case matters) |
| `JavaPsiFacade`, refactoring processors, PSI | `com.intellij.java` |

## Java & Gradle Requirements

| | Value | Notes |
|-|-------|-------|
| JDK to build | 17 minimum | Enforced by plugin 2.x |
| JDK at runtime in sandbox | JBR bundled with target IDE | Platform provides it |
| Gradle wrapper | 8.5+ (8.10 recommended) | Plugin 2.x floor is 8.2 |

Wrapper upgrade:
```bash
./gradlew wrapper --gradle-version 8.10 --distribution-type bin
```

## Recommended IntelliJ Target

**Single stable target: `IC-2024.2.4` for first migration pass.**

Rationale: stable release, JDK 17 source level matches upgraded toolchain, has all APIs RePatch depends on.
`sinceBuild = '242'`, `untilBuild = '243.*'` — narrow to keep verifier output focused.

## Plugin Verification & CI

Task: `verifyPlugin` (renamed from `runPluginVerifier` in 1.x)

```groovy
intellijPlatform {
    pluginVerification {
        failureLevel = [
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
        ]
        ides { recommended() }
    }
}
```

GitHub Actions (illustrative):
```yaml
- run: ./gradlew --no-daemon build verifyPlugin
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: verifier-report
    path: build/reports/pluginVerifier
```

**Expect first `verifyPlugin` run to surface dozens of issues** — `DumbServiceImpl`, `JavaPsiFacadeImpl` impl-class uses are exactly what Track C1/C2 address. That first report IS Track C's intake list.

## Migration Path (mapped to Task IDs)

1. **A1** — Upgrade Gradle wrapper + JDK toolchain. Keep old IntelliJ plugin temporarily to isolate breakage.
2. **A2** — Swap plugin: remove 0.7.3, add `org.jetbrains.intellij.platform` 2.x. Move all dependency declarations.
3. **A3** — Migrate `compile`/`testCompile` → `implementation`/`testImplementation` (mostly done in A1).
4. **A4** — Plugin metadata: move `sinceBuild`/`untilBuild` to `pluginConfiguration`, verify `appStarter` registration.
5. **A5** — Wire `verifyPlugin` into CI. First report = Track C to-do list.
6. **A6** — Update README: `./gradlew runIde`, `./gradlew runIntegrationPipeline`, JDK 17 note.

**Do not** do a 1.x intermediate hop — not worth two migration windows for RePatch's size.

## Confidence Notes

| Claim | Confidence | Notes |
|-------|-----------|-------|
| Plugin ID `org.jetbrains.intellij.platform` | HIGH | Headline change since 2.x GA |
| Gradle ≥ 8.2, JDK ≥ 17 required | HIGH | Matches platform floor |
| Pin `2.1.0` or later | MEDIUM | Check GitHub releases for current latest before pinning |
| `IC-2024.2.4` as target | MEDIUM | Any recent stable line works; verify current latest at A2 time |
| Task names (`verifyPlugin`, `patchPluginXml`, `runIde`) | HIGH | Stable across 2.x minors |
| `defaultRepositories()` + `dependencies.intellijPlatform {}` shape | HIGH | Core DSL, stable since 2.0 |
| `Git4Idea` + `com.intellij.java` bundled IDs | HIGH | Long-standing, case-sensitive |
| ActiveJDBC Gradle plugin 1.2 under Gradle 8.10 | UNKNOWN | Spike required during A1 |
| `ApplicationStarter` still supported in 2024.2 | MEDIUM | API exists; signature changes to `main(List<String>)` — see Architecture research |

## Items to Verify Before Starting A1

1. Exact current latest 2.x plugin version (likely > 2.1.0 by April 2026)
2. Exact current stable IntelliJ IDEA build number
3. ActiveJDBC Gradle plugin 1.2 + Gradle 8.10 compatibility
4. RefactoringMiner 2.1.0 + JGit 5.10.0 transitive closure with IntelliJ 2024.2 dependencies

Quick verification commands:
```bash
# Latest 2.x plugin version
curl -s https://api.github.com/repos/JetBrains/intellij-platform-gradle-plugin/releases/latest | jq -r '.tag_name'
```

---
*Research date: 2026-04-23*
