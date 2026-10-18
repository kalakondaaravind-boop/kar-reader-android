# Contributing

1. Branch from `main`; open a pull request. CI must pass (build both flavours, unit tests, lint, offline-permission check).
2. Keep pure logic in `core.domain` without Android imports and cover it with unit tests.
3. Never add a network permission to the standard build, and never log document content or notes.
4. User data must never be silently lost: deletions go to the recycle bin; schema changes need a Room migration and a migration test.
5. Do not mark a feature VERIFIED in `docs/REQUIREMENTS_MATRIX.md` until it has been checked on a device.
6. Test fixtures must be generated or legally redistributable; do not commit copyrighted books.
