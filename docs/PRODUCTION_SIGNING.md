# Production Signing — The Livre Magicae

The app has two intentionally separate signing identities:

- **Permanent debug key** — used by normal GitHub CI debug builds and local development.
- **Permanent production key** — used for release APKs intended for public distribution.

Never reuse or replace the production key after publishing the first production build.
Future APKs must be signed by the same production key to upgrade an installed app.

## Create the production keystore once

Run on a secure machine:

```bash
keytool -genkeypair \
  -v \
  -keystore the-livre-magicae-release.jks \
  -alias the-livre-magicae \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Use passwords that are not stored in source control. Back up the keystore and its
credentials securely, preferably in more than one protected location.

## Local release

Place the keystore at the path configured by `keystore.properties` and create:

```text
storeFile=app/the-livre-magicae-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=the-livre-magicae
keyPassword=YOUR_KEY_PASSWORD
```

Both files are gitignored.

Run:

```bash
./scripts/release.sh
```

The script fails if `keystore.properties` is absent and verifies the resulting APK
with `apksigner`.

## GitHub Actions

Add these repository secrets:

```text
EPUB_APP_RELEASE_KEYSTORE_BASE64
EPUB_APP_RELEASE_KEYSTORE_PASSWORD
EPUB_APP_RELEASE_KEY_ALIAS
EPUB_APP_RELEASE_KEY_PASSWORD
```

Create the Base64 value from the keystore file. On PowerShell:

```powershell
$bytes = [System.IO.File]::ReadAllBytes(".\the-livre-magicae-release.jks")
$base64 = [System.Convert]::ToBase64String($bytes)
$base64 | Set-Content ".\the-livre-magicae-release.jks.base64" -NoNewline
```

Copy the file contents into the GitHub secret. Do not commit the Base64 file.

The release workflow writes a temporary `keystore.properties`, builds the release,
verifies its signing certificate, checks that INTERNET permission is absent, and then
publishes the APK.

## Before the first public release

Record the production certificate SHA-256 fingerprint somewhere secure. Verify that
future production APKs report the same certificate before publishing them.

The debug key and production key are deliberately different. A debug APK is not a
substitute for a production release APK.
