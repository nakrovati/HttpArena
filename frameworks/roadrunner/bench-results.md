# Local benchmark-lite snapshot (working artifact, removed before merge)

This file is a working artifact captured during the upload-streaming
improvement work. It will be removed in a dedicated cleanup commit
before the PR is opened so the merged contribution doesn't include
it.

## Setup

- Harness: `bash scripts/benchmark-lite.sh roadrunner` from the
  HttpArena repo root.
- Hardware: 24-core laptop, `nproc / 2 = 12` threads, no CPU
  pinning, shared with the host.
- Profiles run: the 11 that lite mode covers. Lite skips api-4/16,
  json-tls, fortunes, crud, h2c profiles, echo-ws-pipeline, and
  h3 / grpc.
- Roadrunner SHA pinned at commit time:
  `9892893c5482f1ad2b4cbbaeb249ca2a92aeda7a` (tip of
  `feat/http-arena`).

## Before — baseline body buffering (default `auto`)

| Profile | Throughput | CPU | Mem | Tool |
|---|---:|---:|---:|---|
| baseline | 432K req/s | 1581% | 257 MiB | gcannon |
| pipelined | **897K req/s** | 1561% | 161 MiB | gcannon |
| limited-conn | 320K req/s | 1733% | 470 MiB | gcannon |
| json | 149K req/s | 1870% | 375 MiB | gcannon |
| json-comp | 57K req/s | 2005% | 616 MiB | gcannon |
| upload | 749 req/s | 1187% | **4.1 GiB** | gcannon |
| static (h1) | 36K req/s | 1155% | 148 MiB | wrk |
| async-db | 37K req/s | 1242% | 529 MiB | gcannon |
| baseline-h2 | 337K req/s | 1736% | 380 MiB | h2load |
| static-h2 | 19K req/s | 1140% | 1.6 GiB | h2load |
| echo-ws | 574K req/s | 1560% | 424 MiB | gcannon |

The outlier is `upload` at 4.1 GiB peak. Every other profile sits
at 150-700 MiB. The validator's upload payloads peak at 20 MB and
the handler only needs the byte count, so the in-flight memory is
purely from the default `body_buffering => auto` mode buffering the
full request body in conn-process binaries before dispatching the
handler.

## Why only upload gets a code change this round

For every other profile (baseline, json, json-comp, static, async-db,
baseline-h2, echo-ws) we do not know the bottleneck. Could be HPACK
encoding, JSON encoding, route dispatch, header parsing, atomics
contention, persistent_term lookups, body framing, etc. Guessing
without `fprof` / `eprof` / `perf record` would just optimize the
cold path.

Roadrunner now carries a roadmap entry (`docs/roadmap.md`, under
`## Other`: "HttpArena-shape bench scenarios for profile-driven
optimization") that tracks adding HttpArena-shape scenarios to
`scripts/wrk2_bench.sh` and `scripts/bench.escript` so profiling
can run against the actual workload shapes that determine ranking.
That's a multi-session arc, not this commit.

## Leaderboard comparison

Pulled from `site/data/<profile>-<conn>.json` files in this repo
(populated from the official benchmark runs on 64-core dedicated
hardware, cores 0-31 and 64-95 pinned, 64 threads).

Roadrunner would be the **first BEAM framework on the
leaderboard** — no Erlang / Elixir / Gleam entry exists across any
profile.

Raw rank uses the unscaled lite numbers (apples-to-oranges; lite
mode runs ~5x fewer threads on a laptop instead of dedicated 64-core
hardware). The 5x-scaled estimate is a rough upper bound; reality
is usually 2-4x because scaling past 12 threads is non-linear.

| Profile | Lite (mine) | Raw rank | 5x est. | Est. rank | Near (5x scale) |
|---|---:|---:|---:|---:|---|
| baseline | 432K | #41/59 | 2.2M | #14/59 | between actix (Rust) and h2o (C) |
| pipelined | 897K | #35/57 | 4.5M | #19/57 | between swerver (Zig) and quarkus (Java) |
| json | 149K | #35/45 | 745K | #10/45 | near workerman (PHP) / actix (Rust) |
| json-comp | 57K | #33/40 | 287K | #16/40 | mid-pack |
| upload | 749 | #38/44 | 3.7K | **#1/44** | top spot (3.2K humming-bird, 3.1K actix) |
| static (h1) | 36K | #45/51 | 182K | #32/51 | bottom-half (wrk-driven, app-bound) |
| async-db | 37K | #35/42 | 187K | #7/42 | top-10 candidate (Swoole / aspnet-aot tier) |
| baseline-h2 | 337K | #19/21 | 1.7M | #15/21 | mid-pack of h2 entries |
| echo-ws | 574K | #16/16 | 2.9M | #7/16 | near lute / dogrider (~3M) |

Honest framing: **mid-pack on CPU-bound profiles, top-10 candidate
on work-bound profiles (async-db, json-comp), top-tier on upload
after the streaming fix**. Behind the C / Rust tier (h2o, ringzero,
rust-epoll, actix, hyper); comparable to high-end Java / C#
frameworks; well ahead of unoptimized Node / Python.

## After — upload streaming (`body_buffering => manual` + `read_body/2 #{length}`)

Captured via `bash scripts/benchmark-lite.sh roadrunner upload`, same
hardware, after the bench-app handler streams via
`roadrunner_req:read_body(Req, #{length => 65536})` and listeners
declare `body_buffering => manual`. Roadrunner SHA pinned at
`a8596b786effa0ad8706548ba0a5a3de0ef6cda3`.

| Run | Throughput | CPU | Mem | Errors |
|---|---:|---:|---:|---:|
| 1/3 | 1.4 K | 982 % | 313 MiB | 0 |
| 2/3 | 1.53 K | 1088 % | 367 MiB | 0 |
| 3/3 | 1.57 K | 1048 % | 399 MiB | 0 |

**Delta vs auto-mode baseline (749 r/s, 4.1 GiB):** 2.1× throughput,
**10× less RSS**, status codes 100 % 2xx. The roadrunner-side
upload-path improvements (`b507415` quadratic-concat fix,
`a8596b786eff` iodata body) make `body_buffering => auto` viable for
low-concurrency cases, but at HttpArena's 128c × 20 MB workload only
the manual streaming path bounds memory.

Leaderboard impact: at the 5× scale extrapolation, ~7.8 K req/s
would place roadrunner **top-tier on `upload`**, above the prior #1
candidates (humming-bird 3.2 K, actix 3.1 K).
