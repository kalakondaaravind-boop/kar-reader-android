# Performance plan

No measurements have been taken yet; none are claimed.

**Reference environment (to be confirmed):** a mid-range Android tablet (Android 13+, 4–6 GB RAM) and an e-ink tablet (Boox, Android 11+).

**Corpus:** generated EPUBs (10, 100, 1,000 chapters), a 50 MB image-heavy PDF, a 1,500-page text PDF, a ZIP of 200 books.

**Measure:** cold start; library load with 1,000 books; import throughput; EPUB chapter open; PDF first page and scroll jank; memory while zoomed at 300%; OCR time per page; backup and restore of 10,000 highlights; sync merge of two 5 MB snapshots.

**Built-in safeguards:** bounded PDF bitmap cache (≤1/6 of heap, ≤256 MB), single-threaded renderer access, lazy page rendering, 200 px render-width buckets, capped resource sizes, staging-and-commit imports.
