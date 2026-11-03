# Android reference implementation

The website specification, implemented on a stock AOSP emulator image: the coordinator as the
device's package verifier, five example notary apps with simulated implementations, and a
scripted scenario harness. The specification is the reference for what this code does; this
file only says how to build and run it.

```
coordinator/   the package verifier (Java, SDK tools only); test/ holds host-side checks
shared/        the provider contract: AIDL, parcelables, canonical signed bytes, service base class
providers/     adv, scan, repro, report, media: the example notary apps
samples/       the app under test (built as v1, v2 and v1 with another signing key)
scripts/       everything below, plus notary-server.py (the HTTPS notary, run on the host)
```

Requirements: an Android SDK with `platforms;android-36`, build-tools 36, the emulator and
`cmdline-tools;latest`; a JDK 17 or newer; python3, openssl and curl.

```sh
scripts/test-host.sh            # policy, attestation and canonical-bytes checks; no device
scripts/create-avd.sh           # installs the AOSP image and creates the AVD "notarization-aosp36"
scripts/start-emulator.sh       # boots it; HEADLESS=1 for no window; prints ANDROID_SERIAL
scripts/build.sh                # every APK into out/, keys into keys/
scripts/install-coordinator.sh  # pushes the coordinator into /system/app and reboots
scripts/install-providers.sh    # installs and enables the notaries (rerun after every build.sh)
scripts/notary-server.sh start  # the HTTPS notary, reached from the emulator as 10.0.2.2
scripts/run-scenario.sh all     # every scenario; screenshots and logs in out/scenarios/
scripts/ci.sh                   # all of the above on a fresh machine (what GitHub Actions runs)
```

By hand: `adb install --no-incremental out/sample-v2.apk` blocks until the dialog is answered;
`adb logcat -s CommunityNotarization` shows the handshake; the notary settings screen is
`adb shell am start -n org.appfair.notarization/.SettingsActivity`.

Scenarios: `approve review cancel deny exempt advonly timeout cache invalid crash offline
disabled identity update incremental settings`. Each one's header line in `run-scenario.sh`
says what it exercises.

Scripted scenarios set two global settings that have no production counterpart:
`community_notarization_providers` (the enabled set) and
`community_notarization_adb_as_standard` (treat adb installs as standard installs, so the ADV
notary takes part). Provider behaviour is scripted through `notary_override_<id>` settings and
the HTTPS server's `/control` endpoint; see `shared/.../NotaryContract.java`.
