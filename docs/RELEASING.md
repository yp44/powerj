# Releasing PowerJ

How a version is cut. The workflow [`release.yml`](../.github/workflows/release.yml) does the building; a person decides the
version, reads the draft and publishes it. **The workflow has never run**: it was written in Phase 5 of the
[architecture review](REVUE-ARCHITECTURE.md) and will be tried with the first tag. Expect to correct it then.

## What a release is made of

| File | Made by |
|---|---|
| `powerj-<version>-linux-x64.zip`, `…-windows-x64.zip`, `…-macos-aarch64.zip` | `-Pdist` on each system: the application image (jlink runtime and jpackage launcher), archived |
| `powerj-<version>.exe` (installer) | `-Pinstaller` on Windows (WiX) |
| `powerj-fx-<version>-linux.zip`, `…-win.zip`, `…-mac-aarch64.zip` | the optional graphical module with JavaFX, one per system |
| `powerj-<version>-sbom.cdx.json` | `-Psbom`: the CycloneDX bill of materials of what ships |
| `SHA256SUMS` | the last job, over every file above (`sha256sum -c SHA256SUMS`) |

**Not signed.** The checksums say that the files are the ones the workflow produced and that they were not damaged on the
way; they do not say who produced them. Signing (a GPG key, or Sigstore through the OIDC identity of the workflow) needs a
decision of the maintainer about the key, and is not done. The draft says so, and so must the published notes.

## Procedure

1. **Main is green** (CI, all jobs: the build, the window tests, the build without the graphical module, macOS, Windows),
   and [CHANGELOG.md](../CHANGELOG.md) describes the version: rename "in development" to the version and the date.
2. **Set the version**, without `-SNAPSHOT`, in every module, and the date of the archives:

   ```bash
   ./mvnw -B versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
   # then project.build.outputTimestamp in the parent pom: the date of the release
   ./mvnw -B -Pdist,sbom verify && ci/smoke/launcher.sh   # as the workflow will
   ```

   Commit it (`build: release 0.2.0`) and merge it.
3. **Tag the merge commit** and push the tag; the workflow refuses a tag that is not the version of the project, and a
   `-SNAPSHOT`:

   ```bash
   git tag -a v0.2.0 -m "PowerJ 0.2.0" && git push origin v0.2.0
   ```

4. **Read the draft** on the releases page: every file is there, `SHA256SUMS` matches (`sha256sum -c SHA256SUMS` on a
   download), the installer installs and `powerj --version` says the version. Write the notes from the CHANGELOG, say that the
   files are not signed, then **publish**.
5. **Back to development**: `./mvnw versions:set -DnewVersion=0.3.0-SNAPSHOT -DgenerateBackupPoms=false`, a new "in development"
   section in the CHANGELOG.

A failed workflow leaves the draft; delete the draft and the tag (`git push --delete origin v0.2.0`), fix, tag again. A tag
that was published is not moved.

## What to check on the first run

- `gh release upload` globs (the names of the files above) and the macOS job: the macOS image is a bundle
  (`powerj.app`), which the archive step of `powerj-dist` now expects but nobody has run.
- `help:evaluate` in the first job needs the network; the workflow runs on a clean runner, so it has it.
- The permissions: `contents: write` is given to the jobs that touch the release, nowhere else.
