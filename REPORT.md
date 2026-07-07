# Investigation: unifying `Tag` and `KeyValue` (`Tag extends KeyValue`)

Branch: `investigate/tag-extends-keyvalue` (5 commits on top of `main` @ `c5e3dc066`).
Goal: make `io.micrometer.core.instrument.Tag` extend `io.micrometer.common.KeyValue` so the
Observation hot path (`DefaultMeterObservationHandler`) no longer converts `KeyValue` → `Tag`
per element per observation, and prove/disprove compatibility and the performance benefit.

Reproducible tooling and instructions: [`investigation/README.md`](investigation/README.md).
All measurements: Windows 11, JDK 25 (Temurin-style OpenJDK 25.0.1), `-XX:+UseParallelGC -Xms512m -Xmx512m`,
single-threaded `ThreadMXBean#getThreadAllocatedBytes` harness.

## Summary verdict

**Feasible in 1.x, with two documented edges that need a maintainer decision.**

- **Binary compatibility for standard usage holds.** Method descriptors are unchanged
  (`Iterable<Tag>` and `Iterable<? extends KeyValue>` erase identically), `Comparable` remains in
  `Tag`'s (now transitive) hierarchy, and japicmp with a resolvable classpath reports **no**
  incompatibility introduced by these commits. Old-compiled implementors of `Tag` (with or without
  custom `compareTo`), old-compiled `MeterRegistry` subclasses overriding `timer(String, Iterable<Tag>)`,
  sorting, `HashMap` keying of `Tags`/`Meter.Id`, and direct `compareTo` calls all behave identically
  on the new jars (fixture-verified, not just argued).
- **Edge 1 — heap-pollution dispatch hazard (the one real behavioral incompatibility found):**
  when new-style code passes a `KeyValues` through the widened virtual
  `MeterRegistry.timer(String, Iterable)` — which `DefaultMeterObservationHandler` now does — an
  **old-compiled** subclass override that iterates the elements as `Tag` throws
  `ClassCastException`. Reproduced deterministically in the fixture. Mitigations below; this is the
  main go/no-go item.
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
  Conversion provably moved to publish time: first `getTagsAsIterable()` on a KeyValues-built id costs
  ~31 ns / 184 B; every subsequent call is ~8 ns / 0 B (cached view).

## The commits

| commit | phase |
|---|---|
| `c129ac077` | 1 — specify the `KeyValue` equality/hash/ordering contract; `ImmutableKeyValue.equals` accepts any `KeyValue`; give `ValidatedKeyValue` contract-compliant `equals`/`hashCode` (it previously used identity) |
| `5d16856ab` | 2 — `Tag extends KeyValue`; keep deprecated default `compareTo(Tag)`; `ImmutableTag.equals` accepts any `KeyValue`; cross-type equality tests |
| `d20d590c4` | 3 — widen `Iterable<Tag>` → `Iterable<? extends KeyValue>` in place on `Tags.of/and/concat`, `MeterRegistry.counter/summary/timer`, `More.longTaskTimer`, the `Metrics` facade, and the meter builders' `tags(Iterable)` |
| `11c8144ea` | 4 — `Meter.Id` stores a sorted, deduplicated `KeyValue[]`; lazy cached `Tags` view; internal `Meter.Id.of(String, Iterable<? extends KeyValue>, …)` path; `DefaultMeterObservationHandler` passes `KeyValues` through; duplicate-meter regression tests |
| `1e0e23410` | 5 (follow-up found by measurement) — size-and-loop instead of a stream in `Tags.of`'s non-`Collection` branch (408 → 184 B for `Tags.of(keyValues)` with 5 pairs) |

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

Old-compiled classes on new jars — the **one deviation**, reproduced deterministically:

```
INFO: ClassCastException: class io.micrometer.common.ImmutableKeyValue cannot be cast to
      class io.micrometer.core.instrument.Tag
PASS: DISPATCH HAZARD: observation through old-compiled iterating override throws CCE on new jars
```

An `Observation` handled by `DefaultMeterObservationHandler` wired to `MyRegistry` works on old jars
(the handler converted to `List<Tag>` first) but throws on new jars: the handler now passes the
context's `KeyValues` into the virtual `timer(String, Iterable)` call, and the old-compiled override's
`for (Tag tag : tags)` checkcasts each element. Notes for the decision:

- Trigger requires *both* an old-compiled subclass overriding `timer(String, Iterable<Tag>)` (or
  another widened method) that iterates/uses elements as `Tag`, *and* non-`Tag` elements flowing in —
  today that is only `DefaultMeterObservationHandler` (timer + LTT paths) or users adopting the new API.
  Overrides that merely delegate (`super.timer(name, tags)`) are unaffected.
- Overriding these convenience methods was never the documented registry extension point
  (`newTimer(Id, …)` etc. is, and it is unaffected), but wrapper/decorator registries in the wild do exist.
- Mitigation options, in rising order of conservatism:
  1. accept + release-note (current prototype behavior);
  2. route the handler through a non-virtual path (build the `Meter.Id` internally and call the
     package-private `timer(Id, …)`), so old overrides never see `KeyValues` — cost: decorators that
     *do* override the convenience methods silently stop seeing observation-driven registrations
     (they see them today);
  3. have the handler keep passing a `Tags` (`Tags.of(keyValues)`) — safest, but reintroduces the
     per-element conversion at stop time and forfeits roughly half of the timer-path win (the `Id`
     construction saving survives via the eager-`Tags` fast path).

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
- tag-typed accessors on a KeyValues-built id return equal `Tag`s; the lazily computed `Tags` view is
  cached (`getTagsAsIterable()` returns the same instance); `withName/withBaseUnit/withTag/replaceTags`
  and `toString()` behave identically across both construction paths

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

### Conversion moved to publish time

On a `Meter.Id` registered via `registry.timer(name, keyValues)` (KeyValues-built, no `Tags`
materialized during registration):

| call | ns/call | B/call |
|---|---|---|
| first `getTagsAsIterable()` (lazy `Tag[]` conversion + `Tags` view) | 30.5 | 184 |
| subsequent `getTagsAsIterable()` (cached view) | **8.2** | **0** |

`getTag(String)` and `getConventionTags(NamingConvention)` read the `KeyValue[]` directly and never
force the view, so registries that publish via convention tags never pay the conversion at all.
(`getTags()` itself copies into a fresh unmodifiable `List` on every call — unchanged behavior from
before.) The cache field is non-volatile by design: a benign data race on an immutable value
(`String.hash`-style), documented in the field's javadoc.

JMH: not run — the existing `DefaultMeterObservationHandlerBenchmark` exercises 1 key-value at 4
threads, so it does not match the 5-key-value single-threaded target scenario; the ThreadMXBean
harness above is the primary measurement per the investigation brief. Running the JMH suite with
`GCProfiler` before/after (and possibly adding a 5-key-value convention benchmark) is a cheap
follow-up for confirmation on Linux.

## Open questions for the maintainer team

1. **The CCE dispatch hazard (Edge 1)** — accept + release-note, reroute the handler through a
   non-virtual internal path (hides observation registrations from convenience-method decorators), or
   keep passing `Tags` from the handler (forfeits ~half the timer-path win)? This is the main risk
   decision; everything else was clean.
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
