# Release signing (issue #32 §1)

Every published Calque APK is signed with the **Calque release key**. Android
treats the signing certificate as the app's identity: an APK signed with a
different key cannot be installed over an existing install (the package
manager answers `INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Losing the key therefore
means every current user must uninstall — and uninstalling wipes app-private
documents and snapshots — before they can install an update.

## Where the secrets live

GitHub → `cleeryy/openclip` → **Settings → Secrets and variables → Actions**:

| Secret | Content |
|---|---|
| `CALQUE_KEYSTORE_BASE64` | the whole `.jks` file, base64-encoded (`base64 -w0 calque-release.jks`) |
| `CALQUE_KEYSTORE_PASSWORD` | keystore password |
| `CALQUE_KEY_ALIAS` | key alias (`calque`) |
| `CALQUE_KEY_PASSWORD` | key password |

The keystore itself is **never** committed: it lives off-repo on the
maintainer machine (`~/calque-keys/calque-release.jks`) and in the GitHub
secret above. `.gitignore` already excludes `*.jks` / `*.keystore`, and no
password belongs in the repo, a `gradle.properties`, a commit message or a
log.

Fingerprint of the current key (SHA-256 of the signing certificate):

```
61:38:09:B3:14:77:71:47:A8:73:0D:23:16:2D:E3:6B:43:8B:2A:2B:47:A1:B0:46:85:30:B8:E1:86:FA:21:F3
```

Rotating the key changes this value: update it in `.github/workflows/android.yml`
(`Verify release signature` step) and in this document **in the same commit**
as the rotation.

## Local release build

```bash
CALQUE_KEYSTORE_FILE=/path/to/calque-release.jks \
CALQUE_KEYSTORE_PASSWORD="$(cat /path/to/keystore.pass)" \
CALQUE_KEY_ALIAS=calque \
CALQUE_KEY_PASSWORD="$(cat /path/to/keystore.pass)" \
  ./gradlew :app:assembleRelease

# Prove the signer without installing anything:
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/*-universal.apk
```

Keep the password in a file the shell reads (`$(cat …)`), never on the
command line of a shared machine, and never inside the repository.

Without those variables the release variant is **debug-signed** instead of
failing, so forks and pull requests still build installable APKs:

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/*-universal.apk
# Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
```

That fallback is a convenience, not a release path: CI fails the tag build if
the keystore is present but the APK signer digest is not the expected one.

## CI flow (`android.yml`)

1. `publish` job, `Decode release keystore`: `base64 --decode` the secret into
   `$RUNNER_TEMP/calque.jks`, `chmod 600`, and export
   `CALQUE_KEYSTORE_FILE` / `CALQUE_KEYSTORE_PASSWORD` / `CALQUE_KEY_ALIAS` /
   `CALQUE_KEY_PASSWORD` through `$GITHUB_ENV`. No echo of any value.
2. `assembleRelease` picks the `release` signing config (see
   `app/build.gradle.kts`, `hasReleaseSigning`).
3. `Verify release signature` runs `apksigner verify --print-certs` and
   compares the normalized SHA-256 digest with the expected value above.
4. `Remove decoded keystore` (`if: always()`) deletes the file; runners are
   ephemeral, the step is belt and braces.

If the secrets are missing (fork, manual run on a repo without them) the
decode step emits a warning and the job continues with debug signing.

## First signed release (switch from the debug-signed alpha)

Every alpha up to `v0.2.0-alpha` was debug-signed, and the CI debug key is
generated per runner — those installs can never update in place, whatever we
do now. The first **release-key** build therefore needs one uninstall of the
old alpha (export documents first: they live in app-private storage).
From `v0.3.0-alpha` on, every build uses the key above and updates in place.

## Regenerating (rotation)

Reasons to rotate: suspected leak, expired certificate, or a maintainer
losing the file. A rotation is a **hard break** for existing installs.

```bash
mkdir -p ~/calque-keys && cd ~/calque-keys
keytool -genkeypair -v \
  -keystore calque-release.jks -alias calque \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Calque, O=OpenClip, C=FR"
keytool -list -v -keystore calque-release.jks -alias calque | grep SHA256
base64 -w0 calque-release.jks > calque-release.jks.b64   # paste into the secret
```

Then:

1. Update the four GitHub secrets (`CALQUE_KEYSTORE_BASE64`,
   `CALQUE_KEYSTORE_PASSWORD`, `CALQUE_KEY_ALIAS`, `CALQUE_KEY_PASSWORD`).
2. Update the expected digest in `android.yml` and in this file.
3. Document the break in the release notes: "This build uses a new signing
   key; uninstall the previous alpha before installing. Export documents you
   want to keep first — they live in the app's private storage."
4. Bump `versionCode` (already required for any store upload).

Keeping the old keystore backed up (offline, two copies) is the only way to
avoid that break: the certificate is bound to the key, not to the version
number.

## Checklist before tagging

- [ ] `./gradlew :app:assembleRelease` with the four variables set.
- [ ] `apksigner verify --print-certs` shows the fingerprint above.
- [ ] `versionCode` / `versionName` bumped.
- [ ] Tag pushed (`v*`); CI `publish` job green, release assets contain the
      per-ABI + universal APK and their `.sha256`.
- [ ] Download the universal APK anonymously and install it over the previous
      alpha (documents preserved, no `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
