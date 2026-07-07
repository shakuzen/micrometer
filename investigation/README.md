# Tag / KeyValue unification — investigation tooling

Reproducible tooling for the investigation documented in [`../REPORT.md`](../REPORT.md)
(branch `investigate/tag-extends-keyvalue`). Not part of the Gradle build; intended to be
dropped before anything merges.

Both tools run against staged jar directories under this directory:

| directory | contents | staged by |
|---|---|---|
| `libs-old` | released `1.16.2` jars from Maven Central | `stage-libs.(sh\|ps1) old` |
| `libs-new` | locally built jars of this branch | `stage-libs.(sh\|ps1) new` |
| `libs-main` | locally built jars of unmodified `main` | `stage-libs.(sh\|ps1) main` |

To stage `new` (or `main`), first build the jars from the respective commit:

```
./gradlew :micrometer-commons:jar :micrometer-core:jar :micrometer-observation:jar
investigation/stage-libs.sh new     # or .ps1 on Windows PowerShell
```

## compat-fixture

Binary/source compatibility fixture. Compiles user-style code (custom `Tag`/`KeyValue`
implementations, a `MeterRegistry` subclass overriding `timer(String, Iterable<Tag>)`,
sorting/HashMap/compareTo usage) ONCE against the 1.16.2 jars, runs those class files
against both old and new jars, recompiles the shared sources against the new jars and
reruns them, then compiles each `src-probes` file against old and new jars to catalog
source incompatibilities.

```
investigation/compat-fixture/run.sh      # or run.ps1
```

Expected outcome: every phase prints `ALL CHECKS PASSED`, including the intentionally
expectation-parameterized checks (cross-type equality flips on new jars; the
`DISPATCH HAZARD` check expects a `ClassCastException` on new jars; the newly-compiled
run expects key-ordering for custom `compareTo(Tag)` implementations). The survey then
prints per-probe compile results.

## alloc-harness

Single-threaded allocation/time harness using
`com.sun.management.ThreadMXBean#getThreadAllocatedBytes` (ParallelGC, fixed heap,
300k-op warmup, 7 batches of 300k ops; median and min reported).

Scenarios: full `Observation` lifecycle (create/start/scope/stop) against
`DefaultMeterObservationHandler` with a convention producing 5 low-cardinality
key-values, with and without the long-task timer; a cached `Timer.Sample` start/stop
reference; and micro paths for the KeyValue→Tag conversion.

```
investigation/alloc-harness/run.sh old          # 1.16.2 baseline
investigation/alloc-harness/run.sh main         # unmodified main baseline
investigation/alloc-harness/run.sh new --new    # prototype, incl. new-API scenarios
```
