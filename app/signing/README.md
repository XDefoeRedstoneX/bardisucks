# Shared build key

`shared.keystore` signs every build of the app (password `android`, alias `bardisucks`). It is **in the
repository on purpose**: Android only installs an update over an existing app when both are signed with the
same key, and this way an APK built anywhere (GitHub Actions, any PC) can update the one on your phone
without uninstalling it and losing your lamps and groups.

It is **not a secret**, so it proves nothing about who built an APK. Only install APKs you built yourself or
got from this repository's own builds. Before sharing the app beyond family, switch to a private key
(a keystore kept out of the repository, e.g. in GitHub Actions secrets).
