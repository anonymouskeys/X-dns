#!/usr/bin/env bash
set -euo pipefail

VERSION="0.7.0"
TAG="v${VERSION}"
REPO="anonymouskeys/X-dns"

ARTIFACT="X-dns-universal-v${VERSION}-unsigned"
SIGNED_NAME="X-dns-universal-v${VERSION}.apk"

SIGN_DIR="$HOME/.xdns-signing"
KEYSTORE="$SIGN_DIR/X-dns-release.p12"
CERT="$SIGN_DIR/X-dns-release-cert.pem"
KEY_INFO="$SIGN_DIR/KEY-INFO.txt"
ALIAS="xdns-release"

WORK="$HOME/X-dns-release-work/$VERSION"

if [[ -d "$HOME/storage/downloads" ]]; then
  DOWNLOADS="$HOME/storage/downloads"
else
  DOWNLOADS="$HOME"
fi

FINAL_APK="$DOWNLOADS/$SIGNED_NAME"

echo
echo "=========================================="
echo " X-dns $VERSION release signer"
echo "=========================================="
echo
echo "The private signing key stays on this device."
echo "GitHub gets only the signed APK and public certificate."
echo

command -v gh >/dev/null 2>&1 || pkg install gh -y
command -v apksigner >/dev/null 2>&1 || pkg install apksigner -y
command -v keytool >/dev/null 2>&1 || pkg install openjdk-21 -y

gh auth status >/dev/null

mkdir -p "$SIGN_DIR"
chmod 700 "$SIGN_DIR"

while true; do
  printf "Signing-key password (minimum 6 characters): "
  stty -echo
  IFS= read -r XDNS_KS_PASS
  stty echo
  printf "\n"

  if [[ ${#XDNS_KS_PASS} -ge 6 ]]; then
    break
  fi

  echo "Password is too short."
done

export XDNS_KS_PASS

if [[ ! -f "$KEYSTORE" ]]; then
  echo "Creating NEW X-dns release signing key..."

  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 10000 \
    -dname "CN=anonymouskeys, OU=X-dns, O=anonymouskeys" \
    -storepass:env XDNS_KS_PASS \
    -keypass:env XDNS_KS_PASS

  chmod 600 "$KEYSTORE"
else
  echo "Reusing existing release signing key."

  keytool -list \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -storepass:env XDNS_KS_PASS \
    >/dev/null
fi

keytool -exportcert \
  -rfc \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -storepass:env XDNS_KS_PASS \
  -file "$CERT" \
  >/dev/null

FINGERPRINT="$(
  keytool -list -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -storepass:env XDNS_KS_PASS \
    2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' \
    | head -n 1
)"

cat > "$KEY_INFO" <<INFO
X-dns Android release signing key

Repository: https://github.com/$REPO
Alias: $ALIAS
Keystore: $KEYSTORE
Public certificate: $CERT
SHA-256 certificate fingerprint:
$FINGERPRINT

IMPORTANT:
Keep X-dns-release.p12 forever.
Future APK updates must be signed by the same key.
The password is NOT stored in this file.
INFO

chmod 600 "$KEY_INFO"

echo
echo "Certificate SHA-256:"
echo "$FINGERPRINT"
echo

rm -rf "$WORK"
mkdir -p "$WORK"

echo "Triggering GitHub unsigned release build..."
gh workflow run release-apk.yml \
  --repo "$REPO" \
  --ref main

sleep 5

RUN_ID="$(
  gh run list \
    --repo "$REPO" \
    --workflow release-apk.yml \
    --branch main \
    --event workflow_dispatch \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId'
)"

if [[ -z "$RUN_ID" || "$RUN_ID" == "null" ]]; then
  echo "Could not find the new GitHub Actions run."
  exit 1
fi

echo "GitHub Actions run: $RUN_ID"

gh run watch "$RUN_ID" \
  --repo "$REPO" \
  --exit-status

echo "Downloading unsigned artifact..."

gh run download "$RUN_ID" \
  --repo "$REPO" \
  -n "$ARTIFACT" \
  -D "$WORK"

UNSIGNED="$WORK/X-dns-universal-v${VERSION}-unsigned.apk"

if [[ ! -f "$UNSIGNED" ]]; then
  echo "Unsigned APK not found: $UNSIGNED"
  exit 1
fi

ALIGNED="$WORK/X-dns-universal-v${VERSION}-aligned.apk"

ZIPALIGN="$(command -v zipalign || true)"

if [[ -z "$ZIPALIGN" && -d "$HOME/android-sdk/build-tools" ]]; then
  ZIPALIGN="$(
    find "$HOME/android-sdk/build-tools" \
      -type f \
      -name zipalign \
      -perm -u+x \
      2>/dev/null \
      | sort -V \
      | tail -n 1
  )"
fi

if [[ -n "$ZIPALIGN" ]]; then
  echo "zipalign: $ZIPALIGN"
  "$ZIPALIGN" -f 4 "$UNSIGNED" "$ALIGNED"
else
  echo "zipalign not found; signing Gradle output as produced."
  cp "$UNSIGNED" "$ALIGNED"
fi

echo "Signing APK locally in Termux..."

apksigner sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$ALIAS" \
  --ks-pass env:XDNS_KS_PASS \
  --key-pass env:XDNS_KS_PASS \
  --out "$FINAL_APK" \
  "$ALIGNED"

echo
echo "Verifying APK signature..."

apksigner verify \
  --verbose \
  --print-certs \
  "$FINAL_APK"

unset XDNS_KS_PASS
XDNS_KS_PASS=""

SHA_FILE="$WORK/SHA256SUMS.txt"
CERT_COPY="$WORK/X-dns-release-cert.pem"
FINGERPRINT_FILE="$WORK/SIGNING-CERT-SHA256.txt"
NOTES="$WORK/RELEASE-NOTES.md"

cp "$CERT" "$CERT_COPY"

(
  cd "$(dirname "$FINAL_APK")"
  sha256sum "$(basename "$FINAL_APK")"
) > "$SHA_FILE"

cat > "$FINGERPRINT_FILE" <<INFO
X-dns v$VERSION
Android signing certificate SHA-256:

$FINGERPRINT
INFO

cat > "$NOTES" <<'NOTES'
# X-dns v0.7.0

DoH + Dragon DPI • Route Rescue

Highlights:
- Universal Android APK
- DoH resolver testing and saved resolver database
- Dragon DPI full-traffic mode
- learned route memory and failed-route cooldown
- alternate DoH route rescue
- YouTube / TikTok route health visibility
- new X-dns winged dragon launcher icon
- @anonymouskeys branding and Telegram link inside the app

Telegram:
https://t.me/anonymouskeys

The APK in this release is signed locally by the project release key.
The public signing certificate and SHA-256 certificate fingerprint are
published with the release for verification.
NOTES

echo
echo "Publishing GitHub Release $TAG ..."

if gh release view "$TAG" \
      --repo "$REPO" \
      >/dev/null 2>&1; then

  gh release upload "$TAG" \
    "$FINAL_APK" \
    "$SHA_FILE" \
    "$CERT_COPY" \
    "$FINGERPRINT_FILE" \
    --repo "$REPO" \
    --clobber

  gh release edit "$TAG" \
    --repo "$REPO" \
    --title "X-dns v$VERSION" \
    --notes-file "$NOTES"

else
  gh release create "$TAG" \
    "$FINAL_APK" \
    "$SHA_FILE" \
    "$CERT_COPY" \
    "$FINGERPRINT_FILE" \
    --repo "$REPO" \
    --target main \
    --title "X-dns v$VERSION" \
    --notes-file "$NOTES"
fi

echo
echo "=========================================="
echo " RELEASE READY"
echo "=========================================="
echo "Signed APK:"
echo "$FINAL_APK"
echo
echo "Signing key:"
echo "$KEYSTORE"
echo
echo "Public certificate:"
echo "$CERT"
echo
echo "DO NOT DELETE THE SIGNING KEY."
echo "All future X-dns updates must use the same key."
echo
echo "GitHub release:"

gh release view "$TAG" \
  --repo "$REPO" \
  --json url \
  --jq '.url'
