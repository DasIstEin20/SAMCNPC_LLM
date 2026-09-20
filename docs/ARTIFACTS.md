# Current selector/connection repair artifacts — 2026-09-21

The matched three-JAR repair build is identified by
[repair checksums](CONNECTION_REPAIR_SHA256SUMS) and
[focused validation](CONNECTION_REPAIR_VALIDATION.json). Its LLM JAR contents
match the standalone clean-build JAR entry for entry. Archive timestamps may
change hashes on rebuilding. Core/Behavior source pins remain unchanged.
Test smoke drivers are absent from the published mod JARs.

The artifact checksums and release checkpoint below are historical and identify
the previous validation build, before the NPC-selector/prompt repair.

# Retained validation artifacts

The retained canonical build contains exactly these three Java 17 mod JARs.
[SHA-256 checksums](ARTIFACT_SHA256SUMS) identify that copied validation build;
binaries are not stored in this source repository. Build with the pinned recursive
submodules using the [installation instructions](LLM_INSTALLATION.md).

Core: `1834e5db8e7c7606f418b06b5e5f9da64837ebfb`.
Behavior: `c6e0273bbbe583cebd20cdd17175aa868686e172`.
LLM implementation checkpoint: `ec33df88b294a0a841c6a246050c92fc94c0e687`;
the release documentation/helper additions do not change production or runtime tests.

JAR tasks retain timestamps, so rebuilding identical source may yield different
bytes. This is not a reproducible-build claim. Older development builds also used
0.1.0; install a matched set, not arbitrary JARs sharing that version number.
Built-in Core/Behavior Forge GameTests remain packaged; compiled `src/test` smoke
drivers are absent. See [packaging evidence](RELEASE_VALIDATION.json).
