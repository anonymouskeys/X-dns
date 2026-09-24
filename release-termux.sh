#!/usr/bin/env bash
set -euo pipefail
repo="anonymouskeys/X-dns"
version="0.7.1"
tag="v$version"
branch="release/v$version"
verified="f43a0a5c2be51f48d0ad4e595eb139fd4a4c333e"
key="$HOME/.xdns-signing/X-dns-release.p12"
key_alias="xdns-release"
cd "$HOME/X-dns"
[[ "$(git branch --show-current)" == "$branch" ]] || { echo "Нужна ветка $branch"; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Есть несохранённые изменения:"; git status --short; exit 1; }
git merge-base --is-ancestor "$verified" HEAD
[[ -f "$key" ]] || { echo "Не найден прежний ключ: $key. Новый ключ не создаётся."; exit 1; }
[[ -d "$HOME/storage/downloads" ]] || { echo "Выполни termux-setup-storage"; exit 1; }
for command_name in gh python apksigner; do
  command -v "$command_name" >/dev/null || pkg install "$command_name" -y
done
command -v keytool >/dev/null || pkg install openjdk-21 -y
gh auth status
sha=$(git rev-parse HEAD)
work=$(mktemp -d)
trap 'unset XDNS_KS_PASS; rm -rf -- "$work"' EXIT

# Read-only preflight: never overwrite an already published release.
gh api "repos/$repo/releases?per_page=100" > "$work/releases.json"
state=$(python - "$work/releases.json" "$tag" <<'PY'
import json,sys
for r in json.load(open(sys.argv[1])):
    if r['tag_name']==sys.argv[2]:
        print('draft' if r['draft'] else 'published');break
PY
)
if [[ "$state" == published ]]; then
  echo "$tag уже опубликован. Ничего не перезаписываем."
  gh release view "$tag" --repo "$repo" --json url --jq .url
  exit 0
fi
# Any pre-existing tag must resolve to the exact release commit.
gh api "repos/$repo/git/matching-refs/tags/$tag" > "$work/refs.json"
tag_exists=$(python - "$work/refs.json" "$tag" <<'PY'
import json,sys
print('yes' if any(r['ref']=='refs/tags/'+sys.argv[2] for r in json.load(open(sys.argv[1]))) else 'no')
PY
)
if [[ "$tag_exists" == yes ]]; then
  target=$(gh api "repos/$repo/commits/$tag" --jq .sha)
  [[ "$target" == "$sha" ]] || { echo "Тег $tag указывает на другой коммит. Остановлено."; exit 1; }
fi
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' \
  push "https://github.com/$repo.git" "HEAD:refs/heads/$branch"

list_runs() {
  gh run list --repo "$repo" --workflow release-apk.yml --branch "$branch" \
    --event workflow_dispatch --limit 100 --json databaseId,headSha,status,conclusion > "$work/runs.json"
}
select_run() {
  python - "$work/runs.json" "$sha" "${1:-}" <<'PY'
import json,sys
excluded=set(json.load(open(sys.argv[3]))) if sys.argv[3] else set()
for r in json.load(open(sys.argv[1])):
    if r['databaseId'] not in excluded and r['headSha']==sys.argv[2] and (r['status']!='completed' or r['conclusion']=='success'):
        print(r['databaseId']);break
PY
}
list_runs
run_id=$(select_run)
if [[ -n "$run_id" ]]; then
  count=$(gh api "repos/$repo/actions/runs/$run_id/artifacts" --jq '[.artifacts[] | select(.expired == false)] | length')
  status=$(gh run view "$run_id" --repo "$repo" --json status --jq .status)
  if [[ "$status" == completed && "$count" == 0 ]]; then run_id=""; fi
fi
if [[ -z "$run_id" ]]; then
  python - "$work/runs.json" > "$work/old.json" <<'PY'
import json,sys
print(json.dumps([r['databaseId'] for r in json.load(open(sys.argv[1]))]))
PY
  gh workflow run release-apk.yml --repo "$repo" --ref "$branch"
  for ((attempt=0; attempt<40; attempt++)); do
    list_runs
    run_id=$(select_run "$work/old.json")
    [[ -z "$run_id" ]] || break
    sleep 3
  done
fi
[[ -n "$run_id" ]] || { echo "Сборка ещё не появилась. Повтори bash ~/X-dns/release-termux.sh"; exit 1; }
if ! gh run watch "$run_id" --repo "$repo" --exit-status; then
  gh run view "$run_id" --repo "$repo" --log-failed || true
  exit 1
fi
actual=$(gh run view "$run_id" --repo "$repo" --json headSha --jq .headSha)
[[ "$actual" == "$sha" ]] || { echo "Коммит сборки не совпал."; exit 1; }
gh run download "$run_id" --repo "$repo" --name "X-dns-universal-v$version-unsigned" --dir "$work/unsigned"
unsigned="$work/unsigned/X-dns-universal-v$version-unsigned.apk"
test -s "$unsigned"
if command -v zipalign >/dev/null; then
  zipalign -f 4 "$unsigned" "$work/aligned.apk"
  unsigned="$work/aligned.apk"
fi
# Compare the local key with the public certificate of the working release.
gh release download v0.7.0 --repo "$repo" --pattern X-dns-release-cert.pem --dir "$work/previous"
IFS= read -r -s -p "Пароль прежнего релизного ключа: " XDNS_KS_PASS
printf '\n'
export XDNS_KS_PASS
keytool -exportcert -keystore "$key" -alias "$key_alias" \
  -storepass:env XDNS_KS_PASS -file "$work/current.der" >/dev/null
python - "$work/previous/X-dns-release-cert.pem" "$work/current.der" <<'PY'
import sys,ssl,pathlib,hashlib
old=ssl.PEM_cert_to_DER_cert(pathlib.Path(sys.argv[1]).read_text())
current=pathlib.Path(sys.argv[2]).read_bytes()
if hashlib.sha256(old).digest()!=hashlib.sha256(current).digest():
    raise SystemExit('Этот ключ отличается от сертификата v0.7.0. Публикация остановлена.')
print('Ключ совпадает с релизом v0.7.0')
PY
mkdir -p "$work/assets"
apk="$work/assets/X-dns-universal-v$version.apk"
apksigner sign --ks "$key" --ks-key-alias "$key_alias" \
  --ks-pass env:XDNS_KS_PASS --key-pass env:XDNS_KS_PASS --out "$apk" "$unsigned"
unset XDNS_KS_PASS
apksigner verify --verbose --print-certs "$apk" > "$work/verify.txt"
cat "$work/verify.txt"
python - "$work/current.der" "$work/verify.txt" "$work/assets/SIGNING-CERT-SHA256.txt" <<'PY'
import hashlib,pathlib,re,sys
expected=hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()
# Accept both Signer #1 and V3.0 Signer output formats used by apksigner.
found={s.replace(':','').lower() for s in re.findall(r'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)',pathlib.Path(sys.argv[2]).read_text())}
if found!={expected}:
    raise SystemExit('Сертификат подписанного APK не совпал с проверенным ключом.')
pathlib.Path(sys.argv[3]).write_text('X-dns v0.7.1\nAndroid signing certificate SHA-256:\n'+expected+'\n')
PY
cp "$work/previous/X-dns-release-cert.pem" "$work/assets/X-dns-release-cert.pem"
(cd "$work/assets" && sha256sum "X-dns-universal-v$version.apk") > "$work/assets/SHA256SUMS.txt"
cp release/NOTES-v0.7.1.md "$work/notes.md"
printf '\nBuild commit: `%s`\n' "$sha" >> "$work/notes.md"
cp "$apk" "$HOME/storage/downloads/"

# Upload and verify as a draft; publish only when all asset bytes match.
if [[ "$state" == draft ]]; then
  gh release edit "$tag" --repo "$repo" --target "$sha" --title "X-dns v$version" --notes-file "$work/notes.md"
  gh release upload "$tag" "$apk" "$work/assets/SHA256SUMS.txt" \
    "$work/assets/X-dns-release-cert.pem" "$work/assets/SIGNING-CERT-SHA256.txt" --repo "$repo" --clobber
else
  gh release create "$tag" "$apk" "$work/assets/SHA256SUMS.txt" \
    "$work/assets/X-dns-release-cert.pem" "$work/assets/SIGNING-CERT-SHA256.txt" \
    --repo "$repo" --target "$sha" --title "X-dns v$version" --notes-file "$work/notes.md" --draft
fi
gh release download "$tag" --repo "$repo" --dir "$work/verification" \
  --pattern "X-dns-universal-v$version.apk" --pattern SHA256SUMS.txt \
  --pattern X-dns-release-cert.pem --pattern SIGNING-CERT-SHA256.txt
for name in "X-dns-universal-v$version.apk" SHA256SUMS.txt X-dns-release-cert.pem SIGNING-CERT-SHA256.txt; do
  cmp "$work/assets/$name" "$work/verification/$name"
done
gh release edit "$tag" --repo "$repo" --draft=false --latest
echo "ГОТОВО: $HOME/storage/downloads/X-dns-universal-v$version.apk"
gh release view "$tag" --repo "$repo" --json url --jq .url
