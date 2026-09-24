# Public debug signing key

This is a disposable, public Android DEBUG key (alias androiddebugkey,
password android). It is intentionally committed so CI builds retain the
same signature and can update each other. It must never sign production
releases or be trusted as publisher identity.

Debug package: com.anonymouskeys.xdns.debug (X-dns Debug).
Release package: com.anonymouskeys.xdns (X-dns).
The private release key remains exclusively on the owner's phone.
