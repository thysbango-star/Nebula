`nebula.jks` is Nebula's app signing key. It's encrypted with a strong password that is
stored only as the `NEBULA_KEY_PASSWORD` GitHub Actions secret, so the file alone can't sign anything.
Keeping the same key means every new APK installs as an update over the previous one.
