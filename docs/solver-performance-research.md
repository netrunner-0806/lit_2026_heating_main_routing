# Solver performance research — 2026-10-18

Work is isolated on `experiment/solver-performance`, based on stable
`c155a15a067be9c868330a8fd30aefbdcc4e72d3`. Nothing is merged or pushed to main.
The machine/server timing difference is not evidence in this experiment.

## Method

Frozen production/test class snapshots, Eclipse Temurin 11.0.23 aarch64,
Docker quota 8 CPUs, 4 GiB container memory, `-Xms128m -Xmx2g`, G1GC.
Timed benchmark runs are sequential on the same local machine; the existing UI remains idle.
Original inputs and output SHA-256 values are recorded in the JSON report.
No runtime configuration, engineering formulas, dataset or production volume changes.
Wall/CPU measurements cover the standalone parsing/solve/output/validation harness,
not HTTP upload, queue waiting or PostgreSQL job persistence. Existing solver
component timings and counters are retained in the JSON report.

Short benchmark series consist of one JVM warm-up and four measured runs.
Large scenes initially get a single control run; those are **not warm medians**.
JFR runs are for sampling, never for the before/after timing comparison.
The first dense JFR run briefly overlapped with an offline JFR export; its timing
is excluded. Subsequent summaries use a bounded streaming reader rather than a
large textual allocation-stack dump.
Inclusive stack percentages overlap and must not be added as component times.
Heap and JVM RSS are sampled every 10 ms; they are not an exact cgroup peak.
Thread allocation deltas exclude threads that terminated between readings.
JFR TLAB totals are allocation sampling estimates, not retained heap.

## Initial profile and decisions

The 250-consumer scene reproduces 48,361,411 examined pairs, 8,388,925 visibility
checks, 9,633,368 routing intersections, 3,714 traversals, 18,076 candidate hydraulic
recomputations, 25,270 topology copies and 3,999,169 directed edges. Four strategies
produce completed candidates; `separate-connections` reaches the unchanged edge
limit. Three independently checked 250/250 variants survive, as required by D2.

* H1, incremental hydraulics: **REJECT at profiling screen**. Only 0.64% of dense
  execution samples include hydraulic computation. No implementation experiment;
  the global dependency risk is unjustified by this measured upper bound.
* H2, transactional topology copies: **REJECT at profiling screen**. Network copy
  accounts for 0.36% of CPU samples and 0.79% of sampled allocations. No code change.
* H5, convex buffer clipping: **REJECT after implementation**. A conservative
  convex-only clipping path passed randomized JTS differential checks and both
  competition hashes, but reduced intersection calls by only 4.4%. PLANAR warm
  median was 26,431 → 25,957.5 ms (1.8%, overlapping run ranges). DEPTH was
  29,772.5 → 27,848 ms, but the baseline ranged 27,964–31,646 ms and trended upward.
  This does not justify added geometry code. Experiment commit `117c81a` was
  explicitly reverted by `b3ef50a`; no clipping implementation remains.

H3 investigation targets dynamic obstacle work inside Dijkstra, rather than
duplicating the existing multi-target traversal. The baseline dense profile has
57.35% inclusive CPU samples in `DynamicObstacles.proximity`. A first STRtree
implementation retained insertion order by sorting candidates. Its single dense
control was 181,343 → 173,282 ms (4.45%); output bytes and every routing counter
were identical. This is insufficient evidence of a useful stable improvement.
The subsequent short series was deliberately interrupted, not passed.

The follow-up JFR attributes 39.32% inclusive CPU samples to STRtree query traversal
and 9.92% to TimSort. The revised experiment uses conservative envelopes of consecutive
groups of 16 stored segments, traversed in the original order. A group can only be
skipped if its envelope is disjoint from the query envelope. Otherwise the **original
individual envelope predicate and all exact distance/exemption calculations** still run.
No candidate order, floating-point accumulation order, Dijkstra tie-breaking, path,
engineering constraint or resource budget changes. Hard checks still stop on the
first actual conflict. This is a constant-factor optimization; worst-case scanning
remains linear in the number of dynamic segments.

Bounds live with one dynamic-obstacle state and are invalidated by add/clear.
Access to the legacy mutable list disables the prefilter. A shallow copy disables
it in both owners because their public segment envelopes can be mutated through
either list. Legacy aliasing is retained without stale metadata. Storage is at most one
envelope per 16 segments, with no global cache, index replication per search, or
materialised candidate list. Small scenes retain the exact linear predicate.

The first dense control of this revision is 181,343 → 101,391 ms (44.09% wall),
284,920 → 205,650 ms CPU. All output bytes and non-timing routing counters match.
Sampled peak heap rises 715,334,648 → 803,264,664 bytes and RSS rises
1,100,505,088 → 1,151,418,368 bytes. These increases are reported rather than hidden:
the experiment is aimed at CPU cost, not a heap reduction. Repeated/scaling evidence
and the full suite are required before acceptance.

The final implementation is local commit `472ebaf`; compared with the stable
frozen build, **only `DynamicObstacles.class` differs**. Final production build
digest: `5acf89668206f5dff9e480a21f820f7457d803fd6a58390638b3d01f0024240b`.
Full Java suite: **508 passed, 0 skipped** (6 min 26 s); UI suite: **28 passed**.
This includes the independent geometry/cost/hydraulic oracles, audit regressions,
long-detour/narrow-corridor checks, witness routes and existing HTTP/upload tests.
New differential tests compare hard decisions, exact penalty double bits and near
ordering with the frozen linear implementation over 1,500 deterministic queries,
plus bounds/touching cases, exemptions, add/clear/copy and mutable-alias invalidation.

## Reproduction

Use an isolated checkout/worktree. Generate the small consumer fixtures with:

```sh
python3 scripts/generate-scale-datasets.py --only-consumers --consumer-counts 50 100 250 500
```

Build baseline production sources from `c155a15` in a separate worktree and use
the experiment's `ScaleBenchmarkMain.java` test harness in **both** worktrees.
Do not label a compilation of the optimized sources as baseline. With the already
available Maven/JDK image, compile each worktree as follows (no tests are skipped
in the separate acceptance run):

```sh
docker run --rm --pull=never --cpus 8 --memory 4g \
  -v "$PWD:/work" -v lct-robustness-m2:/root/.m2 -w /work \
  lct-robustness-builder:baseline \
  mvn -B -ntp test-compile dependency:build-classpath -Dmdep.outputFile=target/stress-classpath.txt
```

Freeze each build with `benchmark-scale.py --label LABEL --snapshot`. Place both
frozen directories under the experiment's `target/scale-builds` (the dependency
classpath points to the same container Maven cache). No classes are overwritten
after freezing. The already prepared local snapshots can be used directly:

```sh
python3 scripts/research-solver-performance.py --label baseline --scene original --mode PLANAR --repeat 4 --name baseline-original-planar
python3 scripts/research-solver-performance.py --label baseline --scene 250 --repeat 0 --name baseline-250-planar
```

The complete selected matrix is one command in a fresh measurement directory:

```sh
python3 scripts/run-solver-performance-matrix.py --before baseline --after h3-block-final
```

`--reuse-completed` can reuse an existing **OK** series only after verifying its
input/build SHA and resource conditions. Existing reports are never overwritten.
The 500-consumer case has an explicit 300-second experimental wall budget per build;
timeout makes the matrix **INCOMPLETE (exit 2)**, not successful. This is a harness
budget, not a new solver limit. Short scenes get warm-up plus four measured runs;
250 gets two separate cold controls; OSM/water and mixed cases are correctness
comparisons in both modes, not multi-run performance claims.

Acceptance commands:

```sh
docker run --rm --pull=never --cpus 8 --memory 4g -e MAVEN_OPTS=-Xmx384m \
  -v "$PWD:/work" -v lct-robustness-m2:/root/.m2 -w /work \
  lct-robustness-builder:baseline mvn -o -B -ntp \
  -Dheatnet.replay.seed=12345 -Dresult=examples/stress/baseline/planar.geojson test > target/research/full-java.log 2>&1
node --test src/test/js/*.test.cjs > target/research/ui.log 2>&1
python3 scripts/summarize-solver-performance.py
```

The runner uses the already available local image `lct-robustness-builder:baseline`
and Maven cache `lct-robustness-m2`. It never starts/stops application services.
Reports/JFR/output files remain under ignored `target/research`; summaries belong
in `docs/solver-performance-results.json`. A timeout or interrupted run is not
reported as a solver infeasibility, successful validation or speedup.

## Acceptance status

Research is finished. **ACCEPT the ordered-bounds optimization on the completed
cases; NEEDS MORE EVIDENCE for a full 500-consumer comparison.** The selected
matrix is honestly marked **INCOMPLETE (exit 2)** because both 500-consumer runs
reached the predeclared 300-second harness budget. Nothing is transferred to main.

Four hypothesis families were investigated; H3 had two implementations. H1/H2
were stopped at profiling because their measured contribution was too small to
justify invasive changes. Only the conservative ordered-bounds implementation
remains in production source in this experimental branch.

| Hypothesis / implementation | Decision | CPU change | Wall change | RSS change | Correctness |
|---|---|---:|---:|---:|---|
| H1 incremental hydraulics | REJECT at screen | Not implemented; 0.64% profile share | — | — | No source change |
| H2 topology transactions/copy-on-write | REJECT at screen | Not implemented; 0.36% profile share | — | — | No source change |
| H5 convex clipping, original PLANAR | REJECT | −3.2% | −1.8%, insufficient | −0.3% | Differential tests and hashes passed; reverted |
| H3 dynamic STRtree, dense 250 | REJECT | −2.6% | −4.4%, single control | −4.5% | Identical bytes/counters; replaced |
| H3 ordered bounding groups, dense 250 | ACCEPT on completed scenes | −27.7% | −45.2% | +0.7% | Identical bytes/counters; 508 Java + 28 UI |

The accept decision is scoped to the completed cases below; the bounded 500 case
is reported separately and is not implied to pass by the unit suite.

Measured final-build comparisons (seconds; memory in MiB). Short-scene columns are
medians of four warm runs after one warm-up. The 250 row is explicitly the median
of **two separate cold controls**, not a warm benchmark. All completed rows have
identical output bytes before/after and clean independent validation.

| Scene | Objects / connected | Best score (unchanged) | Wall before → after | CPU before → after | Peak heap before → after | Peak RSS before → after |
|---|---:|---:|---:|---:|---:|---:|
| Original PLANAR | 144 / 17 | 13.433636792 | 26.431 → 26.492 | 139.220 → 136.680 | 416.0 → 415.8 | 807.2 → 806.4 |
| Original DEPTH | 144 / 17 | 13.433636792 | 29.773 → 27.633 | 146.565 → 136.980 | 425.7 → 409.0 | 834.3 → 806.0 |
| Synthetic 50 PLANAR | 107 / 50 | 13.000569916 | 8.359 → 7.075 | 15.765 → 14.320 | 362.6 → 376.1 | 734.6 → 765.6 |
| Synthetic 100 PLANAR | 207 / 100 | 23.042428744 | 35.219 → 24.495 | 53.110 → 42.500 | 391.8 → 429.3 | 781.0 → 869.2 |
| Dense 250 PLANAR, cold | 507 / 250 | 54.759390540 | 188.113 → 103.168 | 294.460 → 213.010 | 724.3 → 710.1 | 1109.6 → 1117.7 |
| Synthetic 500 PLANAR | 1007 / unverified | Unverified | TIMEOUT → TIMEOUT, 300 s budget each | Not recorded for interrupted solve | Not recorded | Not recorded |

Wall reductions are **15.36% (50)**, **30.45% (100)** and **45.16% (250)**.
For 100, before ranges 34.965–35.473 s versus after 24.229–24.770 s do not overlap.
For 250, cold controls are 181.343/194.882 s versus 105.189/101.146 s. These support
the large-scene gain despite environment variability. The original PLANAR difference
is noise; the DEPTH difference is not claimed as a stable gain because baseline
warm-run variation overlaps the candidate range.

Additional **single cold correctness controls**, not performance claims:

| Scene | Objects / connected | Score PLANAR / DEPTH | Wall PLANAR before → after | Wall DEPTH before → after |
|---|---:|---:|---:|---:|
| Frozen real OSM roads + water | 629 / 17 | 14.142099592 / 14.138740444 | 44.809 → 44.868 s | 59.910 → 58.568 s |
| Universal mixed restrictions | 11 / 1 | 0.677163696 / 0.677163696 | 0.658 → 0.642 s | 0.688 → 0.761 s |

All of those outputs are byte-identical across builds, valid, with zero validator
errors/warnings and zero independent engineering-oracle issues. Both original
outputs match the pre-existing SHA-256 in the JSON report across all five runs per
mode. Routing counters (nodes, directed edges, visibility/intersection calls,
traversals, candidates, hydraulic recomputations, copies, fallback activations)
remain identical. The optimization reduces dynamic envelope scanning, **not**
the number or quality of routes, exact JTS predicates or engineering checks.

For 500, both runner metadata records say TIMEOUT and the matrix returns exit 2.
The candidate log contains completed early strategies with 500 connections, but
the full solve/output/independent-validation pipeline did not finish within the
budget. Those logs are **not** counted as a validated 500/500 result, a deterministic
output, a measured whole-job speedup, or geometric infeasibility. No solver time
limit was added and no resource ceiling was raised. Historical long 500 runs are
not substituted for a current baseline. This remains the explicit acceptance gap;
the investigation stops without starting another long series.

Across the 26 completed standalone solves of the final candidate, the largest
sampled heap was **769.0 MiB** and JVM RSS **1,205.3 MiB**. Interrupted 500 runs are
not included in those maxima. The original main/origin refs were rechecked as
`c155a15` and the original untracked local script's SHA was unchanged. Existing
application/database containers remain healthy with the same container IDs.

## Changed files and isolation

Production change: `src/main/java/ru/lct/heatnet/routing/DynamicObstacles.java` only.

Tests/harness:

* `src/test/java/ru/lct/heatnet/routing/DynamicObstacleIndexTest.java`
* `src/test/java/ru/lct/heatnet/routing/LinearDynamicObstaclesOracle.java`
* `src/test/java/ru/lct/heatnet/stress/ScaleBenchmarkMain.java`
* `src/test/java/ru/lct/heatnet/stress/ProfileSummaryMain.java`

Reproduction/reporting:

* `scripts/research-solver-performance.py`
* `scripts/run-solver-performance-matrix.py`
* `scripts/summarize-solver-performance.py`
* `docs/solver-performance-research.md`
* `docs/solver-performance-results.json`

The experiment worktree is `target/solver-performance-worktree` under the original
checkout. The original untracked `scripts/verify-audit-runtime.py` was preserved;
it is not part of these commits. Generated datasets, frozen builds, JFR and logs
remain ignored under the experiment's `target/`. Main and its existing UI are not
modified. There is no push, deployment, main merge or production volume operation.

Local experiment history retains rejected implementations for auditability:
`117c81a` (convex clipping), `b3ef50a` (explicit revert), `6570fda` (STRtree trial),
`472ebaf` (ordered conservative bounds). The final report/harness commit follows
these; `git log c155a15..HEAD --oneline` lists the exact local history.

## Boundaries of the result

* No asymptotic improvement is claimed: worst-case dynamic scanning is still O(N).
* Original competition/OSM scenes remain dominated by static geometry work; a
  large speedup there is not established.
* This is not a memory optimization. At 100 consumers the measured warm median
  peak RSS increases by 11.3% while wall time decreases by 30.4%; this trade-off is
  explicit. The same 2 GiB heap/4 GiB container budgets are retained.
* RSS means the JVM process `/proc` RSS, not cgroup memory including filesystem
  cache. Exact native/heap/cgroup decomposition was not measured.
* The 4-million-directed-edge limit remains. A failed late strategy is diagnosed;
  only completed independently validated variants are returned.
* No server benchmark, 3 GiB input experiment, alternate routing engine,
  clustering, parallel-strategy change or production JVM change was performed.
* H6–H8 were not expanded into new experiments after a useful bounded H3 result
  was found. The Mac/VPS timing gap remains unconfirmed.
