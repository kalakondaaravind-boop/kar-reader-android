# Security

## Threat model

All imported files, ZIP archives, EPUB content, Calibre databases, backups and sync snapshots are treated as untrusted.

| Risk | Mitigation |
|---|---|
| ZIP path traversal / absolute paths / drive letters / NUL | `ZipPaths.normalizeEntryName` + canonical-path containment (`SafeZipExtractor`); unit-tested |
| Decompression bombs | Limits on entry count, per-entry and total bytes measured while decompressing, and compression ratio; partial files written as `.part` and removed on failure |
| EPUB path traversal | Every resource path normalized and resolved only inside the archive |
| XML external entities | Entity expansion off; entity resolver returns empty input; unit-tested |
| Publisher scripts / remote resources in EPUB | WebView answers only `https://book.kar.local/`; everything else is refused; CSP `script-src 'none'`; no file or content access |
| Network exfiltration | Standard build has no network permission (CI-enforced); library-merged network permissions are removed |
| Hostile images | Cover decoding is bounded with `inSampleSize` and pixel limits |
| Corrupt or forged backups | Fixed entry whitelist, size caps, manifest version check, SHA-256 checksum; restore in one transaction |
| Sync snapshots | Size cap; merged in one transaction; Wi-Fi frames authenticated with HMAC-SHA256 keyed by the pairing code; private addresses only |
| Profile PINs | PBKDF2-HMAC-SHA256, 120,000 iterations, random salt |
| Screenshots / recents preview | `FLAG_SECURE` when app lock is on |
| Logs | The app does not log document content, notes or extracted text |

## Not claimed

KAR READER does not claim encryption at rest beyond Android's device encryption, secure deletion, or redaction.

## Reporting

Please open a private security advisory on this repository.
