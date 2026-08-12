# Investigation: unifying `Tag` and `KeyValue` (`Tag extends KeyValue`)

Branch: `investigate/tag-extends-keyvalue` (5 commits on top of `main` @ `c5e3dc066`).
Goal: make `io.micrometer.core.instrument.Tag` extend `io.micrometer.common.KeyValue` so the
Observation hot path (`DefaultMeterObservationHandler`) no longer converts `KeyValue` → `Tag`
per element per observation, and prove/disprove compatibility and the performance benefit.

Reproducible tooling and instructions: [`investigation/README.md`](investigation/README.md).
All measurements: Windows 11, JDK 25 (Temurin-style OpenJDK 25.0.1), `-XX:+UseParallelGC -Xms512m -Xmx512m`,
single-threaded `ThreadMXBean#getThreadAllocatedBytes` harness.

## Summary verdict

**Feasible in 1.x. The one hard blocker found (a ClassCastException through old-compiled registry
decorators) has been eliminated by a 16-byte-per-observation mitigation; what remains for a
maintainer decision is one deprecation wart and a small residual risk for new user code.**

- **Binary compatibility for standard usage holds.** Method descriptors are unchanged
  (`Iterable<Tag>` and `Iterable<? extends KeyValue>` erase identically), `Comparable` remains in
  `Tag`'s (now transitive) hierarchy, and japicmp with a resolvable classpath reports **no**
  incompatibility introduced by these commits. Old-compiled implementors of `Tag` (with or without
  custom `compareTo`), old-compiled `MeterRegistry` subclasses overriding `timer(String, Iterable<Tag>)`,
  sorting, `HashMap` keying of `Tags`/`Meter.Id`, and direct `compareTo` calls all behave identically
  on the new jars (fixture-verified, not just argued).
- **Edge 1 — heap-pollution dispatch hazard, found and mitigated:** when non-`Tag` elements pass
  through the widened virtual `MeterRegistry.timer(String, Iterable)`, an **old-compiled** subclass
  override that iterates the elements as `Tag` throws `ClassCastException` (reproduced
  deterministically in the fixture; a code search found WildFly's `ApplicationRegistry` shipping
  exactly this shape). The handler therefore passes a lazily converting `Iterable<Tag>` view
  (`KeyValuesTagIterable`, commit 7): old iterating decorators receive genuine `Tag`s and keep their
  semantics, while `Meter.Id` unwraps the view so the non-decorated path stays conversion-free
  (+16 B/op measured, timings unchanged). Residual risk is limited to *new user code* passing raw
  `KeyValues` into an old-compiled iterating decorator directly — see the CCE section.
- **Edge 2 — sort-dispatch wart for recompiled custom comparators:** a custom `Tag` implementation
  overriding `compareTo(Tag)` keeps its custom ordering as an old binary, but **after recompilation**
  `Arrays.sort`/`Collections.sort`/`TreeSet` silently stop dispatching to it (they route through
  `Comparable.compareTo(Object)` → `KeyValue.compareTo(KeyValue)`, and the recompiled class no longer
  gets a bridge). Direct `tag.compareTo(otherTag)` calls still dispatch to it. Deprecation javadoc
  warns about this; fixture-verified.
- **Source compatibility:** plain `Tag` implementors, implementors overriding `compareTo(Tag)`,
  all sorting idioms with relaxed bounds, method references to the widened methods, and every
  call-site pattern surveyed still compile. Exactly three patterns break, all niche (see the
  exact-errors section): subclass overrides of the widened methods (name clash), `Comparable<Tag>`-typed
  code, and strict `T extends Comparable<T>` bounds.
- **Performance:** the target observation lifecycle improves by **≈ −20 % time** in all configurations
  and **≈ −15…19 % allocation** with the long-task timer enabled (2,308 → ~1,860 B/op vs current main;
  2,348 → ~1,860 B/op vs 1.16.2). The KeyValue→Tag conversion is gone from the per-observation path;
  what remains is dominated by the Observation machinery itself (context, convention invocation ×2,
  scope ThreadLocals), which caps the achievable win for the no-LTT case at roughly −10 % allocation.
  Conversion provably moved to publish time: `getTagsAsIterable()` on a KeyValues-built id costs
  ~24–31 ns / 184 B per call (the view is computed per call since commit 6 removed the cache; with
  caching it was ~8 ns / 0 B after the first call — see the caching subsection). Confirmed
  independently by the repo's JMH benchmarks (see the JMH subsection).

## The commits

| commit | phase |
|---|---|
| `c129ac077` | 1 — specify the `KeyValue` equality/hash/ordering contract; `ImmutableKeyValue.equals` accepts any `KeyValue`; give `ValidatedKeyValue` contract-compliant `equals`/`hashCode` (it previously used identity) |
| `5d16856ab` | 2 — `Tag extends KeyValue`; keep deprecated default `compareTo(Tag)`; `ImmutableTag.equals` accepts any `KeyValue`; cross-type equality tests |
| `d20d590c4` | 3 — widen `Iterable<Tag>` → `Iterable<? extends KeyValue>` in place on `Tags.of/and/concat`, `MeterRegistry.counter/summary/timer`, `More.longTaskTimer`, the `Metrics` facade, and the meter builders' `tags(Iterable)` |
| `11c8144ea` | 4 — `Meter.Id` stores a sorted, deduplicated `KeyValue[]`; lazy cached `Tags` view; internal `Meter.Id.of(String, Iterable<? extends KeyValue>, …)` path; `DefaultMeterObservationHandler` passes `KeyValues` through; duplicate-meter regression tests |
| `1e0e23410` | 5 (follow-up found by measurement) — size-and-loop instead of a stream in `Tags.of`'s non-`Collection` branch (408 → 184 B for `Tags.of(keyValues)` with 5 pairs) |
| `917b1d44e` | 6 — do **not** cache the computed `Tags` view in `Meter.Id` (final field, set eagerly only for Tags-built ids); see the caching-cost subsection under Performance |
| `638858ed6` | 7 — `KeyValuesTagIterable`: the handler passes a lazily converting `Iterable<Tag>` view through the overridable convenience methods, eliminating the ClassCastException hazard for old-compiled iterating registry overrides (fixture-verified) while `Meter.Id.of` unwraps it so the non-overridden path performs no conversion |

Design constraints confirmed along the way:

- `interface Tag extends KeyValue, Comparable<Tag>` is indeed illegal (same generic interface with
  different type arguments); ordering must come from `Comparable<KeyValue>`.
- `Iterable<Tag>` overloads cannot coexist with `Iterable<? extends KeyValue>` (same erasure), so
  widening in place was the only option, as planned.
- `Tag[]` IS-A `KeyValue[]` array covariance is used by the `Id(String, Tags, …)` constructors to share
  the `Tags`-internal array with zero copying; nothing ever writes into a shared array.
- `summary(String, Iterable)` was **not** rerouted to the new direct-Id path because
  `DistributionSummary.Builder` has a per-builder default distribution config (no shared constant to
  reuse safely); it still benefits from the widened signature. Extracting a shared default would be a
  follow-up.

## Prior art: the 2022 attempt (#3097 / #3111) and how this prototype differs

This was tried before, in the run-up to 1.10 (March–April 2022), and abandoned explicitly over
compatibility concerns. The trail:

- PR [#3097](https://github.com/micrometer-metrics/micrometer/pull/3097) "Deprecating Tag and Tags in
  core and moving them to commons" and PR [#3111](https://github.com/micrometer-metrics/micrometer/pull/3111)
  "Deprecating tags in core" (branches `tags_copied_to_commons` / `deprecating_tags_in_core`, still on
  marcingrzejszczak's fork). The design was the same idea **inverted**: a new `io.micrometer.common.Tag`
  as the parent type, core `Tag extends io.micrometer.common.Tag` with the whole core type deprecated,
  and `Iterable<Tag>` parameters widened to `Iterable<? extends io.micrometer.common.Tag>` — the same
  erasure-based widening as commit 3 here.
- #3111 was closed unmerged (2022-04-12) with the verdict: *"We're going to take a different approach
  to solve the problem, as this approach is too invasive, burdensome on users, and potential for subtle
  incompatibilities are too much."*
- PR [#3120](https://github.com/micrometer-metrics/micrometer/pull/3120) then reverted all breaking
  changes to ensure 1.10.0↔1.9.0 binary compatibility, and PR
  [#3122](https://github.com/micrometer-metrics/micrometer/pull/3122) pivoted to renaming the commons
  types to **KeyValue/KeyValues as deliberately distinct types** ("Trying to fix the Tag & Tags
  confusion"), even removing `TagKey.of` so commons could not hand out tags usable by core. That
  decision is why the KeyValue→Tag boundary conversion this branch removes exists in the first place
  (and issue [#3102](https://github.com/micrometer-metrics/micrometer/issues/3102) records the
  user-facing conversion friction it caused from day one).

How each recorded concern maps onto this prototype:

| 2022 concern | 2022 attempt | this prototype |
|---|---|---|
| "too invasive" | 249 files, +6.1k/−1.7k lines; every binder and registry touched; `Tag`, `Tags`, `ImmutableTag` all deprecated and re-homed | 17 main-source files across commons+core; no type moves, no type deprecations; binders untouched; whole repo compiles unchanged |
| "burdensome on users" | ecosystem-wide deprecation warnings on `Tag`/`Tags` usage; users pushed to migrate to a new type with the same simple name (confusion called out in #3122) | `Tag` stays the first-class metrics type; nothing to migrate; the only deprecation is the `compareTo(Tag)` overload, visible only to custom implementors overriding it |
| "potential for subtle incompatibilities" | unenumerated risk (no fixture/japicmp evidence in the PRs); plus a real one baked into the design: common `Tag extends Comparable<Object>` whose `compareTo` returned `-1` for non-Tags — violating comparator antisymmetry (flagged in #3097 review) | the risk is now enumerated and tested instead of feared: japicmp is clean with a resolvable classpath, and the fixture reduces the behavioral delta to the two documented edges (CCE through old-compiled iterating decorators; sort dispatch for recompiled custom `compareTo(Tag)`) with mitigations proposed — see the sections below |
| the `Comparable` generics wall | "solved" with `Comparable<Object>` + instanceof | solved by inheriting `Comparable<KeyValue>` — key-only ordering with an intact contract |

Two more pieces of relevant history:

- In the #3097 review, jonatan-ivanov proposed sorting `Tags` internals with
  `Comparator.comparing(Tag::getKey)` ("a universal Comparator that can compare both type of tags")
  instead of relying on the elements' `Comparable` at all — that is exactly the alternative behind
  open question 2 below (key-only comparison in `Tags` internals would also erase the Edge 2 wart, at
  the cost of changing behavior for old binaries with custom orderings).
- Issue [#2092](https://github.com/micrometer-metrics/micrometer/issues/2092) / PR
  [#2431](https://github.com/micrometer-metrics/micrometer/pull/2431) (2020–2022) debated widening
  `Iterable<Tag>` → `Iterable<? extends Tag>`; jkschneider warned not to confuse source with binary
  compatibility, and the `Tags.concat` widening was merged in 2022 only after ABI-compatibility was
  confirmed. Commit 3 here is the same class of change, and this investigation re-proves the erasure
  argument empirically (japicmp `===` on descriptors; old-compiled override dispatch verified by the
  fixture) rather than re-litigating it.

In short: the previously found problems were (1) blast radius of deprecating/re-homing the types,
(2) user migration burden, (3) unquantified subtle-incompatibility risk, and (4) the Comparable
generics wall. (1), (2), and (4) are designed out in this prototype; (3) has been converted into a
concrete, fixture-verified two-item list with proposed mitigations, which is exactly what the open
questions below put in front of the team.

## japicmp

### Repo build task (`./gradlew :module:japicmp`, baseline `1.16.0`, `failOnModification` + `failOnSourceIncompatibility`)

| module | result |
|---|---|
| micrometer-commons | **PASS** — "No changes." (the `equals` behavior change and the package-private `ValidatedKeyValue` additions are invisible at the signature level) |
| micrometer-observation | **PASS** (module untouched) |
| micrometer-core | **FAIL** — 2 findings, **both classified as false positives** of the task's classpath setup (below) |

The core failure report (`micrometer-core/build/reports/japi.txt`):

```
***! MODIFIED CLASS: PUBLIC io.micrometer.core.instrument.ImmutableTag
	---! REMOVED INTERFACE: java.lang.Comparable
***! MODIFIED INTERFACE: PUBLIC ABSTRACT io.micrometer.core.instrument.Tag
	---! REMOVED INTERFACE: java.lang.Comparable
	---! REMOVED METHOD: PUBLIC(-) SYNTHETIC(-) BRIDGE(-) int compareTo(java.lang.Object)
```

**Classification: binary compatible; the two findings are artifacts.** The Gradle task puts only the
single module jar on japicmp's classpath (`ignoreMissingClasses = true`), so japicmp cannot resolve
`io.micrometer.common.KeyValue` and concludes `Comparable` disappeared. In truth
`Tag → KeyValue → Comparable<KeyValue>`, i.e. `Comparable` never leaves the hierarchy, and the
`compareTo(Object)` bridge that used to live in `Tag.class` is inherited from `KeyValue.class`
(verified with `javap`: `KeyValue.class` contains a synthetic default
`compareTo(Object) { checkcast KeyValue; invokeinterface compareTo(KeyValue) }`; JVMS §5.4.3.4
interface method resolution finds superinterface methods). Proof by execution: running japicmp 0.23.1
**with the module dependencies on the classpath** (1.16.2 + its commons vs prototype + its commons),
`--only-incompatible` reports **nothing** for `Tag`/`ImmutableTag`/`Tags`/`Meter*` — the only entries
are the `OkHttpContext#getState/setState` removals that predate this branch (they are already in the
build's `methodExcludes`) plus an informational `MeterNotFoundException` default-serialVersionUID note.
The fixture (next section) confirms the runtime behavior the bridge finding would otherwise question.

**Action item if this proceeds:** feed the module's dependency classpath into the japicmp task's
`oldClasspath`/`newClasspath` (or add targeted excludes) — as configured today the task would keep
failing on these false positives.

### Exact binary-level inventory of the change (unmodified `main` vs prototype, `--only-modified`)

Everything japicmp reports for micrometer-core, classified:

| change | japicmp marker | classification |
|---|---|---|
| `Tag`: new interface `KeyValue`; `compareTo(Tag)` gains `@Deprecated` | `***`/`+++` | binary compatible (interface addition, annotation addition) |
| `ImmutableTag`: new (transitive) interface `KeyValue` | `***`/`+++` | binary compatible |
| 19 methods across `Tags` (`of`/`and`/`concat`×2), `MeterRegistry` (`counter`/`summary`/`timer`), `MeterRegistry$More.longTaskTimer`, `Metrics` + `Metrics$More` facades, and the 11 builders' `tags(Iterable)`: generic signature `Iterable<Tag>` (or `Iterable<? extends Tag>`) → `Iterable<? extends KeyValue>` | `===*` (binary-unchanged, source-incompatible flag) | binary compatible for **callers and old binaries** (descriptor unchanged, old overriders still dispatch — fixture-verified); **source-incompatible for subclass overriders** (name clash — see survey) |

micrometer-commons binary-level diff: none (japicmp: "No changes").

## Old-binary fixture results

`investigation/compat-fixture` — compiled once against 1.16.2, the class files then run against the
prototype jars (full log reproducible via `run.sh`/`run.ps1`; all phases end `ALL CHECKS PASSED`).

Old-compiled classes on **new** jars — everything identical to old jars:

- classes load; `MyTag` (custom **descending** `compareTo(Tag)`), `PlainTag`, `MyKeyValue` all work
- `tag.compareTo(otherTag)` direct calls dispatch to the custom implementation
- `Arrays.sort(Tag[])`, `Collections.sort(List<Tag>)` honor the old-compiled custom ordering
  (the class file carries its own `compareTo(Object)` bridge)
- `Tags.of` honors the old-compiled custom ordering (internals still call `compareTo(Tag)`)
- plain/built-in tags sort by key; `TreeSet<Tag>` natural ordering by key
- `Tags` and `Meter.Id` work as `HashMap` keys
- `ImmutableTag.hashCode() == ImmutableKeyValue.hashCode()` for equal pairs (identical formula in
  1.16.2 already — this is what makes commit 4 safe)
- **intended change observed:** `ImmutableTag("a","b").equals(ImmutableKeyValue("a","b"))` flips
  `false` → `true` (both directions, symmetric)
- the old-compiled `MyRegistry extends SimpleMeterRegistry` override of
  `timer(String, Iterable<Tag>)` is **still dispatched** through the widened method, sees its `Tag`
  elements, and meter deduplication works through it

### The ClassCastException hazard — found, analyzed, and mitigated

This was the one real behavioral incompatibility found, and the key question for shipping in a
minor release. Full anatomy:

**Mechanism.** With commit 4 alone, `DefaultMeterObservationHandler.onStop` passed the context's
`KeyValues` into the *virtual* `meterRegistry.timer(String, Iterable)` call. A registry subclass
compiled against ≤1.17 that overrides `timer(String name, Iterable<Tag> tags)` still receives the
call (same erased descriptor), but javac compiled its element access with a cast. From the fixture's
old-compiled `MyRegistry.class` (`javap -c`):

```
22: invokeinterface #29,  1   // InterfaceMethod java/util/Iterator.next:()Ljava/lang/Object;
27: checkcast     #33         // class io/micrometer/core/instrument/Tag   <-- throws here
```

With `ImmutableKeyValue` elements, instruction 27 throws
`ClassCastException: class io.micrometer.common.ImmutableKeyValue cannot be cast to class
io.micrometer.core.instrument.Tag` — on **every** observation stop, propagating out of
`Observation.stop()` into the instrumented request path. Loud and immediate (caught in any smoke
test), not silent corruption — but catastrophic where triggered. Trigger requires all of: an
old-compiled subclass overriding a widened convenience method, that override casting elements to
`Tag` (iteration, streams, `toArray(new Tag[0])`, lambdas typed `Tag`), and non-`Tag` elements
flowing in. Of the widened methods, only `timer(String, Iterable)` (observation stop) and
`More.longTaskTimer` (observation start, reachable via an overridden `more()` — which WildFly's
`ApplicationRegistry` actually does, with a custom `ApplicationMore` overriding
`longTaskTimer(String, Iterable<Tag>)`) carried `KeyValues` from micrometer itself; both are
wrapped in the converting view by the mitigation. `counter` via `onEvent` goes through
`Counter.builder` and never hits the virtual convenience method.

**Affected population is real.** A GitHub code search for the exact override signature
(`gh api search/code`, "public Timer timer(String name, Iterable<Tag>") finds, besides micrometer
forks: **WildFly's `ApplicationRegistry`** (delegating registry that *streams the elements through a
`Tag`-typed lambda* to add a `wf_deployment` tag — would have thrown), **Expedia Styx's
`PluginMeterRegistry`** (passes the iterable straight to `Tags.and` — safe, converts correctly), and
**Kora's `NoopMeterRegistry`** (returns a constant — safe). One confirmed-vulnerable, widely
deployed integration is enough to rule out shipping the raw-`KeyValues` behavior in a minor.

**Mitigation implemented (commit `638858ed6`).** The handler now wraps the `KeyValues` in
`KeyValuesTagIterable` (internal, `io.micrometer.core.instrument.internal`), a 16-byte lazily
converting `Iterable<Tag>` view: any override that iterates receives genuine `Tag` elements
(per-element `instanceof` fast path), so WildFly-style decorators keep working **with identical
semantics** — they see the meters, their added tags apply, nothing changes for them. On the
non-overridden path the view's iterator is never invoked: `Meter.Id.of` unwraps it to the backing
`KeyValues` and builds the id conversion-free. Measured cost: exactly the wrapper allocation
(+16 B/op, e.g. 1,496 → 1,512 B on the no-LTT lifecycle; timings unchanged). The fixture's
observation-through-old-compiled-iterating-override check, which previously demonstrated the CCE,
now passes on new jars **and** verifies the override observed the converted `Tag` elements.

**Residual risk (documented, not mitigated):** *new user code* that passes a raw `KeyValues` (or any
non-`Tag` iterable) directly into the widened public methods of a registry wrapped by an old-compiled
iterating decorator can still trigger the same CCE. That requires newly written code meeting an
old binary, is outside micrometer's own call paths, and fails loudly on first use; the javadoc of the
widened methods could warn about it. Alternatives considered and rejected: routing the handler
through a non-virtual path (old decorators like WildFly would silently stop seeing/tagging
observation meters — a silent behavior change, worse than none); passing materialized `Tags`
(same safety as the view but pays per-element conversion even when no decorator exists).

The recompiled-fixture run also demonstrates Edge 2 (same source, recompiled against the prototype):

```
PASS: Arrays.sort IGNORES newly-compiled custom compareTo (key-ascending) [documented wart]
PASS: Tags.of with newly-compiled custom compareTo sorts by key [documented wart]
```

(`Tags.of` first checks sortedness via `compareTo(Tag)` — which still dispatches to the custom
implementation — but the `Arrays.sort` it then delegates to no longer does; for recompiled custom
comparators the two now disagree. Custom `Tag` orderings that are not key-consistent already violate
`Tags`' documented "sorted and deduplicated by key" invariant today, so this was judged acceptable to
deprecate rather than support.)

## Source-incompatibility survey (exact list)

Compiled against 1.16.2 ✔, then against the prototype. Errors below verbatim (`javac`, English locale).

**1. Subclass overriding a widened method — BREAKS (expected, inherent to in-place widening):**

```
MyRegistry.java:21: error: name clash: timer(String,Iterable<Tag>) in MyRegistry and
timer(String,Iterable<? extends KeyValue>) in MeterRegistry have the same erasure,
yet neither overrides the other
MyRegistry.java:20: error: method does not override or implement a method from a supertype   [@Override]
```

Fix for affected users is mechanical: change the override's parameter to
`Iterable<? extends KeyValue>`. The same applies to overriders of any of the 19 widened methods.

**2. `Comparable<Tag>`-typed code — BREAKS:**

```
ProbeComparableTagTyped.java:10: error: incompatible types: Tag cannot be converted to Comparable<Tag>
```

**3. Strict `T extends Comparable<T>` generic bounds — BREAKS:**

```
ProbeStrictComparableBound.java:25: error: method max in class ProbeStrictComparableBound cannot be
applied to given types;
  reason: inference variable T has incompatible equality constraints KeyValue,Tag
```

The relaxed, idiomatic bound `T extends Comparable<? super T>` (what `Collections.sort`,
`Comparator.naturalOrder`, `Stream.sorted` use) is satisfied by `Tag extends Comparable<KeyValue>`
and keeps compiling.

**Still compiles (verified in one probe, with only deprecation warnings):** plain `implements Tag`;
`implements Tag` with `@Override compareTo(Tag)` (thanks to the retained deprecated overload — 
warning: `[deprecation] compareTo(Tag) in Tag has been deprecated`); `Collections.sort(List<Tag>)`;
`list.sort(null)`; `Arrays.sort(Tag[])`; `TreeSet<Tag>`; `Stream.sorted()`;
`Comparator.<Tag>naturalOrder()`; `tag.compareTo(otherTag)` (binds to the deprecated overload — same
dispatch as before); `registry.timer/counter/summary(name, listOfTags)`;
`more().longTaskTimer(name, listOfTags)`; `Timer.builder(...).tags(listOfTags)`;
`BiFunction<String, Iterable<Tag>, Timer> f = registry::timer` (method reference against the widened
signature); `Tags.of/and/concat` with `Iterable<Tag>` arguments. No overload-resolution ambiguities
were found: `Tags`-typed arguments keep selecting the 1.16-era `(String, Tags)` overloads, everything
else selects the widened `Iterable` methods exactly as before.

**In-repo evidence:** the entire repository (all registries, binders, samples, test fixtures — main
and test source sets of every module) compiles unchanged against the new core:
`./gradlew compileJava compileTestJava` → `BUILD SUCCESSFUL`. The only in-repo adaptations needed at
all were two `@SuppressWarnings("deprecation")` in `Tags`' own internals (intentional dispatch
preservation) and `@Override` on `Tag.getKey/getValue` (ErrorProne `MissingOverride`).

## Duplicate-meter regression tests

`micrometer-core/src/test/java/io/micrometer/core/instrument/MeterIdTest.java` (committed, passing):

- `Meter.Id` built from `Tags.of("a","1","b","2")` equals (both directions, equal hash) an id built
  from `KeyValues.of("a","1","b","2")`, including unsorted/mixed `Tag`+`KeyValue` element lists
- register `timer` via `Tags` then look up via `KeyValues` → **same `Timer` instance, 1 meter in the
  registry**; and vice versa; same for `counter` and `more().longTaskTimer`; also with a
  `commonTags` `MeterFilter` configured (exercises the `mapId`/`meterMap` path, not just
  `preFilterIdToMeterMap`)
- tag-typed accessors on a KeyValues-built id return equal `Tag`s (equal views on repeated calls);
  `withName/withBaseUnit/withTag/replaceTags` and `toString()` behave identically across both
  construction paths

This is what commit 1 exists for: `Meter.Id.equals/hashCode` now iterate the stored `KeyValue[]`,
so `ImmutableTag`/`ImmutableKeyValue` elements must collide. A consequence worth calling out: any
**third-party `KeyValue` implementation with identity `equals`** that reaches a `Meter.Id` (only
possible through new-API/observation paths) would produce a new meter per registration. The contract
is now specified on `KeyValue` and both in-repo implementations comply (`ValidatedKeyValue` was
fixed in commit 1; `KeyValueWithCardinality` never reaches contexts — it is unwrapped first). The
pre-existing analogue (custom `Tag` implementations in `Meter.Id`s) has always had the same
requirement, just unspecified.

## Test suites and checks

| check | result |
|---|---|
| `:micrometer-commons:test` | PASS |
| `:micrometer-observation:test` | PASS |
| `:micrometer-core:test` | 1083 tests: 1071 pass, 13 skipped, **3 fail ×4 retries — all pre-existing/environmental**: `ProcessorMetricsTest.otelCpuMetrics(+WithExtraTags)` and `OkHttpMetricsEventListenerTest.cachedResponsesDoNotLeakMemory` fail **identically on unmodified `main`** on this Windows machine (verified side by side); zero failures attributable to the change |
| `checkFormat` (spring-javaformat), `checkstyleMain/Test`, `license` on all three modules | PASS |
| `compileJava compileTestJava` across **all** modules (registries, binders, samples) | PASS |
| main source sets language level | unchanged Java 8 (`--release 8` enforced by the build; compiles clean) |

## Performance

Setup: `http.server.requests`-style `ObservationConvention` producing 5 low-cardinality key-values
(computed fresh per call, keys pre-sorted), full `createNotStarted → start → openScope → close → stop`
lifecycle, `DefaultMeterObservationHandler` on a `SimpleMeterRegistry`, meters pre-registered (steady
state). Numbers are medians of 7 in-process batches × 300k ops; lifecycle scenarios were re-run 3× per
jar set because escape-analysis/JIT ordering causes ±(50–100) B/op run-to-run movement — ranges shown.

### Observation lifecycle (the target path)

| scenario | 1.16.2 | main (before) | prototype (after) | after vs main |
|---|---|---|---|---|
| (a) handler with LTT | 2,348 B/op / 963 ns | 2,164–2,308 B/op / ~895–918 ns | **1,844–1,892 B/op / ~717–731 ns** | **−15…−19 % alloc, −21 % time** |
| (b) handler LTT disabled | 1,880 B/op / 629 ns | 1,576–1,696 B/op / ~575–601 ns | **1,496–1,688 B/op / ~441–475 ns** | **−5…−12 % alloc, −23 % time** |
| cached `Timer.Sample` start/stop (reference) | 0 B/op / 52 ns | 0 B/op / 51 ns | 0 B/op / 53 ns | unchanged (the ~24 B `Sample` is escape-analyzed away in this loop) |

Reading: the eliminated work is exactly the per-element conversion + `Tags`/`Meter.Id`
materialization (two `ArrayList`+`ImmutableTag`×n passes per lifecycle with LTT, one without, plus
`Tags.of` copying/sort-checking and the old `Meter.Id` construction). The remaining ~1.5–1.9 KB is
Observation-machinery cost (context, two convention invocations, scope handling, `Timer.start`), which
is why the no-LTT allocation win is proportionally smaller — its meter path was a smaller slice.

### Micro paths (5 key-values)

| path | B/op | ns/op |
|---|---|---|
| old-style manual convert loop (`ArrayList` + `Tag.of`×5 + `Tags.of(list)`) | 264 | 36 |
| `Tags.of(keyValues)` **before** commit 5 (stream fallback) | 408 | 57 |
| `Tags.of(keyValues)` **after** commit 5 | **184** | **28** |
| handler-equivalent convert path incl. error tag (6 pairs), unchanged code, old vs new jars | 288 / 288 | 86 / 76 |

(Commit 5 exists because the measurement exposed the stream-based fallback as a regression relative
to the manual loop; with it, `Tags.of(keyValues)` beats the old conversion pattern on both axes.)

### Conversion moved to publish time, and what caching the view is worth

On a `Meter.Id` registered via `registry.timer(name, keyValues)` (KeyValues-built, no `Tags`
materialized during registration), `getTagsAsIterable()` was measured in both `Meter.Id` variants:

| call | with view caching (commit 4) | without caching (commit 6, current) |
|---|---|---|
| first `getTagsAsIterable()` | 30.5 ns / 184 B | 31.7 ns / 184 B |
| repeated `getTagsAsIterable()` | 8.2 ns / 0 B | 23.7 ns / 184 B |

That difference — ~16 ns and 184 B per repeated tag-typed accessor call, for 5 key values — is the
*entire* value of the cache, and it only accrues on KeyValues-built ids whose `Tags` view is
requested more than once. Nothing on the registration/lookup hot path touches the view
(`equals`/`hashCode`/`getTag(String)`/`getConventionTags` all read the `KeyValue[]` directly), which
the lifecycle scenarios confirm: identical results under both variants. The realistic repeated
consumer is a registry that iterates `getTagsAsIterable()`/`getTags()` per publish interval —
~184 B × meters, per interval, i.e. noise. The prototype therefore ships **without** the cache
(final field, no lazily initialized state, trivially thread-safe); reintroducing the benign-data-race
cache later needs no API change if a profile ever justifies it. (`getTags()` copies into a fresh
unmodifiable `List` on every call in *both* variants — unchanged from the status quo.)

### Call-site polymorphism (JIT) check

Concern: today, `Tag`-typed and `KeyValue`-typed call sites are effectively monomorphic
(`ImmutableTag` / `ImmutableKeyValue` respectively), which is ideal for inline caches. After this
change, shared library sites can see both classes — most importantly `Meter.Id.hashCode/equals`
element accesses on the registry-lookup hot path, since Tags-built ids hold `ImmutableTag` elements
while KeyValues-built ids hold `ImmutableKeyValue` elements. Does bimorphism eat the conversion win?

`AllocBenchMixed` runs two ops against **one shared registry**: a Tags-typed lookup
(`registry.timer(name, Tags)`, Id built from `ImmutableTag`s) and the no-LTT observation lifecycle
(Id built from `ImmutableKeyValue`s) — each alone per JVM ("solo", clean profiles) and interleaved in
one JVM ("mixed", polluted profiles). Old jars are the control: there the same mixed workload stays
monomorphic because the handler converts everything to `ImmutableTag` up front.

| jars | op | solo | mixed |
|---|---|---|---|
| 1.16.2 (control) | Tags-typed timer lookup | 22.0 ns / 40 B | 14.8 ns / 40 B |
| 1.16.2 (control) | observation lifecycle | 645.6 ns / 1,880 B | 643.2 ns / 1,880 B |
| prototype | Tags-typed timer lookup | 15.4 ns / 48 B | 13.6–13.9 ns / 48 B (5 runs) |
| prototype | observation lifecycle | median **434.7 ns / 1,496 B** (8 runs: 399–479) | median **434.4 ns / 1,496 B** (8 runs: 419–482) |

**No systematic bimorphic penalty was measurable.** A first sample suggested +45 ns on the mixed
observation path, but 8 paired JVM runs per mode show the medians are indistinguishable; the apparent
delta was a **bistable JIT/escape-analysis outcome** (~430 ns/1,496 B vs ~480 ns/1,688 B per JVM,
landing in the slow state once per 8 runs in *both* modes — unmodified `main` flaps the same way,
1,576↔1,696 B, so the bistability is not introduced by this change). Even the slow state beats the
old jars' mixed workload by ~25 %. Mechanistically this matches expectations: C2 inlines bimorphic
sites behind a single subtype check, and `ImmutableTag.getKey/getValue/hashCode/equals` and their
`ImmutableKeyValue` twins are tiny. Two honest caveats: (a) the measured `Meter.Id` object itself
grew by ~8 B (the extra `count`/cache fields — visible as 40→48 B on the lookup op); (b) if a
**third** implementation ever becomes hot at these sites (`ValidatedKeyValue`, custom
`Tag`/`KeyValue` impls), they go megamorphic (C2 inlines at most two receivers) — not measured here,
and worth a `-prof perfasm` JMH pass on Linux before final judgment.

### Repo JMH benchmarks (before = unmodified `main`, after = this branch incl. commits 6–7)

`benchmarks-core` jmh jars built from each side and run identically on the same machine
(JDK 25, Windows; `-wi 3 -w 1s -i 5 -r 1s -prof gc`, first pass `-f 1`, flagged results re-verified
with `-f 3` and `-t 1`). Headline rows (annotation-default thread counts):

| benchmark | before | after | delta |
|---|---|---|---|
| `DefaultMeterObservationHandlerBenchmark.observation` (4 threads, 1 key-value) | 1,021.6 ns / 1,310 B | 976.4 ns / 1,199 B | −4.4 % ns, **−111 B** |
| `…observationWithoutThreadContention` (1 thread, 1 key-value) | 405.0 ns / 1,284 B | 388.9 ns / 1,172 B | −4.0 % ns, **−112 B** |
| `…builtTimerWithSample` / `…observationOrTimer` / `…builtTimerAndLongTaskTimer` | — | — | +8…19 B (larger `Meter.Id`), ns within noise |
| `MeterRegistrationBenchmark.registerExistingTimer` (re-verified `-f 3 -t 1`) | 16.0 ns / 40 B | 16.8 ns / 48 B | **+0.8 ns, +8 B** |
| `MeterRegistrationBenchmark.registerExistingCounter` (re-verified `-f 3 -t 1`) | 19.6 ns / 40 B | 19.9 ns / 48 B | +0.4 ns, +8 B |
| `TagsBenchmark.dotAnd` (re-verified `-f 3`) | 56.9 ns / 336 B | 57.1 ns / 336 B | no change |
| `TagsBenchmark.tagsOf(Un)orderedTagsSet{2,4,10}`, `.of` | — | — | no change beyond noise |

Reading:

- The observation benchmarks confirm the harness result at the benchmark's own shape (only **1**
  low-cardinality key-value, no convention): −111/−112 B per observation is exactly the eliminated
  `ArrayList` + per-element `Tag` conversion + `Tags`/`Id` materialization for that size; with 5
  key-values (the harness scenario) the same mechanism removes ~300–440 B.
- The one real cost found: the Tags-typed lookup (`registry.timer(name, Tags)` on an existing meter)
  pays **+8 B/op** (the `Meter.Id` object grew by the `keyValues`+`count` fields) and **<1 ns**.
  Part of the ns delta is plausibly `ImmutableTag.equals`' `instanceof KeyValue` now being an
  interface check where `instanceof ImmutableTag` was an exact-class check; adding an exact-class
  fast path in `ImmutableTag`/`ImmutableKeyValue.equals` is a one-line follow-up candidate if this
  matters at scale.
- First-pass `-f 1` numbers flagged `registerExisting*` at +43…65 % and `dotAnd` at +48 B; all of it
  dissolved under `-f 3`/`-t 1` (and `KeyValuesBenchmark.dotAnd` — **untouched code** — moved +10 %
  between runs). Single-fork JMH on this Windows box has a noise floor of several ns / tens of bytes
  from bistable compilation; conclusions above use only the re-verified runs. A Linux perf-rig JMH
  pass (with `-prof perfasm` for the instanceof/polymorphism questions, and ideally a new
  5-key-value convention benchmark in `DefaultMeterObservationHandlerBenchmark`) is the recommended
  confirmation before merging anything.

## Valhalla readiness note

`Tag`/`KeyValue` implementations, `Meter.Id`, and `Tags`/`KeyValues` are candidates for Project
Valhalla value classes (JEP 401) down the road. How this branch interacts with that:

- **Helps.** The commit-1 equality contract (value-based `equals`/`hashCode` across all
  implementations, no identity reliance) is exactly the semantics value classes formalize.
  Commit 6 (no view caching) is load-bearing here: the commit-4 variant's lazily written cache
  field was a *mutable* field and would have disqualified `Meter.Id` from value-class migration;
  with it removed, every `Meter.Id` field is final and the class is value-ready. `KeyValuesTagIterable`
  (one final field, no identity use) is itself a value-class candidate, so the +16 B mitigation
  wrapper and the +8 B transient lookup-`Id` cost both trend toward zero under value-class
  scalarization/flattening — Valhalla strengthens rather than undercuts this design.
- **Neutral.** `Meter.Id` referencing a `KeyValue[]` is fine for a value class (arrays stay identity
  objects; the array reference is just a field). The `instanceof` fast paths (`Tags`,
  `KeyValuesTagIterable`) work unchanged on value objects.
- **Pre-existing blockers, untouched by this branch.** `ImmutableTag` is a public **non-final**
  class (third parties may subclass), and value classes must be final — that needs a deprecation
  cycle regardless of this work. `Tags`/`KeyValues` use identity singleton checks
  (`tags == EMPTY`, already flagged by ErrorProne `ReferenceEquality`) whose semantics shift subtly
  under value-object `==`; switching them to `length == 0` checks at migration time is trivial.
  Nothing holds `Tag`/`Id` in weak references, synchronizes on them, or uses identity hashing, so no
  other disqualifiers were found in core.

## Open questions for the maintainer team

1. **The CCE dispatch hazard (Edge 1)** — mitigated via the `KeyValuesTagIterable` converting view
   (commit 7): old-compiled iterating decorators (e.g. WildFly) keep working with identical
   semantics, at +16 B per observation. To review: (a) is the internal-package placement acceptable,
   or should the view live elsewhere; (b) the residual risk that *new user code* passing raw
   `KeyValues` into an old-compiled iterating decorator still throws — accept with a javadoc warning
   on the widened methods, or additionally have the widened javadoc recommend `Tags.of(...)` when
   targeting wrapped registries?
2. **Recompiled custom `compareTo(Tag)` losing sort dispatch (Edge 2)** — is deprecation javadoc
   enough, or should `Tags` internals switch to key-only comparison outright so old- and new-compiled
   implementors at least behave identically (a behavior change for old binaries that "worked" with
   custom orderings)?
3. `ImmutableTag`/`ImmutableKeyValue` cross-type equality is observable behavior change (`false` →
   `true`) even for users who never touch the new APIs (e.g. `Set<Object>` mixing both). Judged
   low-risk; worth a release note?
4. Scope of widening: `MeterRegistry.gauge*(String, Iterable<Tag>, …)`, `Meter.Id.withTags/replaceTags`,
   `Search`/`RequiredSearch.tags`, `Config.commonTags`, `MeterFilter.commonTags/ignoreTags`,
   `Meter.MeterProvider.withTags` (interface — widening would source-break implementors) were left
   untouched. Follow the same treatment in a real PR, or keep the minimal surface?
5. `summary(String, Iterable)` still materializes `Tags` via the builder (no shared default
   distribution config constant). Extract one (like `AbstractTimerBuilder.DEFAULT_DISTRIBUTION_CONFIG`)?
6. The build's japicmp task needs its classpath fed with module dependencies (or two targeted
   excludes) before this could go green in CI — see the japicmp section.
7. `KeyValues` could expose `size()` (it is `SIZED` internally); `Meter.Id.of` and `Tags.of` currently
   read it via `spliterator().getExactSizeIfKnown()`, which works but is roundabout.
8. Follow-up candidates once ids are KeyValue-based: an `Observation`-side path that hands the
   context's `KeyValues` to `ObservationOrTimerCompatibleInstrumentation`, and a
   `Counter`-path equivalent for `onEvent` (currently still builder-based, one `Tags` materialization
   per event).
