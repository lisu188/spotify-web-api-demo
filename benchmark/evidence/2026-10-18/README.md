# Complete yearly playlist benchmark evidence

[Executed hosted run](https://github.com/lisu188/spotify-web-api-demo/actions/runs/39154709369)
completed all 48 measured trials at harness commit
`3818234c8d3393f208f82c9dacebdc6846e328a0`. The measured production revisions are
baseline `5369a02692f026d989b527dfa19ba74799382045` and optimized
`0ae29dd1be1233ca883aeea3586a025eee38447d`.

`report.md`, `summary.json` and `raw.csv` expose the results directly. `evidence.zip`
retains every trial JSON, command log, exact source archive, hash manifest and run
metadata beyond the hosted artifact's 30-day retention. `verification.json`
records the independent archive/trial checks and exact report recomputation.

Cold scenario median speedups are 6.91x, 2.15x, 2.70x and 1.64x. Warm scenarios
are mixed: 1.21x, 0.94x, 0.89x and 0.85x. Three warm cases are slower, with median
increases of about 37 ms, 15 ms and 111 ms. Dense repeated cold searches fall from
199 to 50, eliminating the measured duplicate requests, and optimized concurrent
searches never exceed eight.

These are synthetic provider-transport measurements on one CPU with a requested
`-Xmx1536m` heap. Every JVM reports 1,556,938,752 maximum usable heap bytes in the
raw evidence. They do not measure actual HTTP pool acquisition, real Spotify,
Firestore, Cloud Run or a real account. Consult the report for full limits.

`integration-verification.json` confirms that the rebased PR's entire `src/main`
delta against merged security main is byte-identical as a full-index Git patch to
the production delta between the two measured revisions. The benchmark does not
claim measurements of the final rebased commit or of the separate security fixes.
