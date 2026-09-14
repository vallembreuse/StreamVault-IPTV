# SFTP E2E diagnostic probe

This module exists only on the diagnostic branch. It builds a release/R8 APK that depends on the real `:data` and `:domain` modules and therefore executes the production `SshjNasSftpClient` with the production ProGuard rules.

It is not installed. The APK is pushed to `/data/local/tmp` and executed with `app_process`, so `com.streamvault.app.custom` and its persisted data are untouched.

The SFTP credential is read from the `SV_SFTP_CREDENTIAL` environment variable. Never commit it, print it, or pass it as a literal command-line argument.

Build:

```sh
./gradlew :sftp-e2e:assembleRelease
```

The expected APK is:

```text
sftp-e2e/build/outputs/apk/release/sftp-e2e-release-unsigned.apk
```

Push it to the Redmi:

```sh
adb -s 02bfb398d603 push \
  sftp-e2e/build/outputs/apk/release/sftp-e2e-release-unsigned.apk \
  /data/local/tmp/sv-sftp-e2e.apk
```

Run against the NAS account home (`.`) without exposing the credential in shell history or process arguments:

```sh
read -s "SV_SFTP_CREDENTIAL?Mot de passe SFTP jellyfin: "; echo
printf '%s\n' "$SV_SFTP_CREDENTIAL" | adb -s 02bfb398d603 shell \
  'IFS= read -r SV_SFTP_CREDENTIAL; export SV_SFTP_CREDENTIAL; CLASSPATH=/data/local/tmp/sv-sftp-e2e.apk app_process /system/bin com.streamvault.sftpdiagnostic.Runner 192.168.1.26 22 jellyfin . ECDSA 0e:84:2c:b6:68:58:4b:65:f0:f4:ac:72:b3:28:83:df; unset SV_SFTP_CREDENTIAL'
unset SV_SFTP_CREDENTIAL
```

A complete pass is exactly:

```text
RESULT=SUCCESS directoryVerified=true writeVerified=true
```

The production client itself creates a unique `.streamvault-write-test-*.tmp`, writes one byte, and removes it. Any other result must be treated as a failed E2E validation and investigated before touching the main application.
