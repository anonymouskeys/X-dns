# X-dns releases

Official project channel:

https://t.me/anonymouskeys

Release model:

1. GitHub Actions builds an unsigned universal release APK.
2. `release-termux.sh` downloads that artifact.
3. Termux signs it with the local X-dns release key.
4. The signed APK, checksum, public certificate and certificate fingerprint
   are uploaded to the GitHub Release.

The private signing key is intentionally not stored in GitHub.
