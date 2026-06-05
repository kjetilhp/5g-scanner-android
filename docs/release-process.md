# Release Process

Use this when preparing a release or release commit. Normal coding work should stay lightweight; release prep is intentionally more careful.

## Versioning

The app uses SemVer:

- MAJOR: breaking compatibility after a release/shared baseline exists.
- MINOR: backward-compatible features or behavior.
- PATCH: fixes, docs, and polish.

Prototype/private field-test builds may remain in `0.x` while compatibility promises are still being shaped. The current initial version is `0.1.0`.

Repeated release-prep runs are idempotent for the current pending release. Re-running `prepare release` should refresh the audit, docs, notes, and verification for the same version. It must not bump to a new version unless the developer explicitly supplies or approves a different SemVer.

## Release Prep Checklist

1. Inspect changed files and version metadata.
2. Classify compatibility impact:
   - Room persistence/schema/settings changes
   - JSONL reporting contract changes
   - CSV export contract changes
   - user-facing behavior/text changes
   - docs/test-only changes
3. Ask for decisions when needed:
   - SemVer bump
   - clean-install dev baseline or Room migration
   - backend/reporting coordination
   - CSV/export compatibility note
4. Update release files:
   - `app/build.gradle.kts` `versionName` and `versionCode`
   - `README.md`
   - `RELEASE_NOTES.md`
   - relevant architecture/product/testing docs
5. Run verification:
   - `.\gradlew.bat assembleDebug`
   - `.\gradlew.bat testDebugUnitTest`
6. Final response should include:
   - release version
   - compatibility decisions
   - verification results
   - suggested commit message

Do not stage, commit, push, branch, or open a pull request unless explicitly asked.

## Release Commands

Use these prompts to keep responsibilities clear:

```text
prepare release
```

Runs release prep only: audit changes, ask for SemVer/migration decisions when needed, update version metadata/docs/release notes, run verification, and suggest a commit message. If a pending release already exists, update that same release instead of bumping the version. It does not stage or commit.

```text
prepare release 0.2.0
```

Same as above, but with the intended SemVer supplied up front.

```text
commit
```

Only after release prep is reviewed. If the user explicitly asks for `commit`, the agent may perform the requested Git commit action. Otherwise, the developer commits manually.

Avoid using `prepare release commit` to mean both prep and commit. Prefer preparing first, reviewing the result, then committing as a separate explicit action.

## Release Notes Practice

`RELEASE_NOTES.md` is the canonical release notes file. Every release prep must add or update exactly one entry for the release being prepared. If the entry already exists, update it in place as the pending release notes:

```text
## x.y.z

- Relevant change since the previous release.
- Compatibility or migration decision, if any.
- Important known limit, if release-relevant.
```

Keep entries concise and release-relevant. Do not list every internal refactor.

Include bullets for:

- user-visible behavior changes
- compatibility/migration decisions
- data/reporting/export changes
- release-relevant known limits

Exclude routine internal refactors, formatting churn, and implementation details that do not change behavior, risk, compatibility, or testing.

## Compatibility Buckets

Room migration decisions are only for local persisted data/settings compatibility:

- Room entities, tables, and columns
- DAO assumptions tied to stored data shape
- indices that affect the installed schema
- database version/provider setup
- persisted settings keys/default semantics when old saved settings must survive

JSONL reporting contract changes affect backend/reference-scanner compatibility. CSV export changes affect the user-facing export contract. Neither requires a Room migration unless the local stored data shape also changes.
