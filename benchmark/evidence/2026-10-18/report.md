# Yearly playlist generation benchmark

Baseline: 5369a02692f026d989b527dfa19ba74799382045. Optimized: 0ae29dd1be1233ca883aeea3586a025eee38447d.

3 paired trials per scenario; alternating revision order per trial; fresh JVM per revision/trial; each JVM does 1 untimed warmup then 1 measured run; warm scenarios additionally prime caches once.

The harness invokes each revision's real yearly, Last.fm, search, REST, and playlist services. Only authentication and upstream transports are simulated. Search responses contain a wrong first candidate and an exact second candidate. Existing playlists start empty; actual pagination and 100-item additions are exercised. Every run asserts ordered contents, empty-year preservation, request counts, and monotonic progress.

| Scenario | Baseline median [min–max], ms | Optimized median [min–max], ms | Speedup | Search calls B → O | Duplicate searches B → O | Peak active searches B → O |
|---|---:|---:|---:|---:|---:|---:|
| sparse-unique-cold | 5413.0 [5395.8–5430.2] | 782.8 [782.2–792.6] | 6.91× | 250 → 250 | 0 → 0 | 1 → 8 |
| sparse-unique-warm | 145.4 [134.3–148.3] | 120.7 [120.1–131.1] | 1.21× | 0 → 0 | 0 → 0 | 0 → 0 |
| dense-unique-cold | 30866.3 [30850.2–30891.7] | 14359.7 [14266.9–14367.5] | 2.15× | 5500 → 5500 | 0 → 0 | 4 → 8 |
| dense-unique-warm | 535.4 [501.9–545.8] | 572.1 [544.6–590.1] | 0.94× | 0 → 0 | 0 → 0 | 0 → 0 |
| sparse-repeated-cold | 735.5 [730.7–754.0] | 272.0 [257.4–279.0] | 2.70× | 25 → 25 | 0 → 0 | 1 → 8 |
| sparse-repeated-warm | 119.0 [114.9–119.5] | 133.7 [126.0–135.7] | 0.89× | 0 → 0 | 0 → 0 | 0 → 0 |
| dense-repeated-cold | 1589.7 [1588.3–1656.4] | 966.9 [878.0–1003.1] | 1.64× | 199 → 50 | 149 → 0 | 4 → 8 |
| dense-repeated-warm | 632.7 [589.9–700.0] | 743.5 [677.7–913.7] | 0.85× | 0 → 0 | 0 → 0 | 0 → 0 |

| Scenario | CPU median, ms B → O | Peak heap, MiB B → O | Peak RSS, MiB B → O |
|---|---:|---:|---:|
| sparse-unique-cold | 1080.0 → 710.0 | 93.4 → 67.2 | 274.2 → 265.3 |
| sparse-unique-warm | 140.0 → 120.0 | 63.2 → 66.0 | 270.2 → 270.3 |
| dense-unique-cold | 3150.0 → 2800.0 | 138.9 → 139.3 | 335.5 → 327.8 |
| dense-unique-warm | 530.0 → 580.0 | 121.9 → 122.6 | 306.5 → 304.4 |
| sparse-repeated-cold | 500.0 → 270.0 | 64.5 → 60.5 | 270.1 → 264.7 |
| sparse-repeated-warm | 110.0 → 130.0 | 71.3 → 69.1 | 270.1 → 271.3 |
| dense-repeated-cold | 1320.0 → 960.0 | 99.3 → 99.6 | 272.3 → 271.8 |
| dense-repeated-warm | 620.0 → 740.0 | 99.0 → 99.7 | 271.7 → 271.9 |

## Reproduction and limits

- Fixed range: 2005–2026. Sparse fixtures populate only 2026; dense fixtures populate all 22 years. Each populated year has 250 scrobbles. Unique fixtures use distinct songs; sparse repeated uses 25 songs × 10 listens, dense repeated uses the same 50 songs × 5 listens in every year.
- Synthetic latency: 20 ms/search, 5 ms/Last.fm page, 5 ms/playlist request. These delays are inputs, not measurements of Spotify or Last.fm.
- Cold trials use fresh in-memory stores. Warm trials prime the same services; playlist state is reset before measurement, so reconciliation still runs. Priming and setup are untimed.
- WSL/Linux one CPU affinity; JVM -Xmx1536m -XX:ActiveProcessorCount=1; no container memory limit; shared host with other builds. RSS and heap are sampled every 10 ms and include the test/mocking runtime. CPU time also includes sampling overhead. Measurements approximate the deployed CPU/heap, not Cloud Run container parity.
- Mocked RestTemplate.exchange bypasses real HTTP connection-pool acquisition and Spotify response serialization. Timings do not measure pool capacity or attribute speedups to pooling.
- The host is shared with other builds. Per-trial start/end load averages are preserved; the run does not claim an isolated machine.
- No real accounts, external HTTP, Firestore, OAuth refresh, network jitter, provider rate limits, or cloud cold starts are measured. No wall-clock threshold is an automated acceptance gate.
- Raw per-trial JSON, flattened raw.csv, aggregate summary.json, command logs, and environment/harness hashes in run.json are retained alongside this report.
