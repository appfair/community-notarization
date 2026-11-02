# Android reference implementation

A working, demonstrable implementation of the community notarization proposal (see the
website's specification) on a stock AOSP emulator image:

* **the coordinator**, a privileged package verifier that PackageManagerService consults before
  every install: it discovers the notaries declared by installed apps, asks the enabled ones in
  parallel over Binder or HTTPS, validates and caches their evidence, applies the
  strict-unanimity policy, and either answers the system directly or shows the review dialog;
* **five example notary apps**, each a real provider with a working, simulated implementation
  of the operation the specification describes (developer registration, malware scan,
  reproducible build, consumer review, age rating), reached over both transports the
  specification defines;
* **a scripted test harness** that boots the emulator, installs everything and drives sixteen
  scenarios through the real installer, pressing the dialog buttons itself. It runs in GitHub
  Actions (`.github/workflows/android-reference.yml`) and on a laptop.

Nothing here needs a change to Android. The coordinator uses the package verification hook
that has existed since API 14, which is the hook Play Protect uses on certified devices, so the
same design can ship as an app update rather than an OS update.

## How it plugs into Android

For each install session, `PackageManagerService` (`VerifyingSession.java`) stages the APK,
holds the install and sends `ACTION_PACKAGE_NEEDS_VERIFICATION` to the *required verifier*: a
system app that holds `PACKAGE_VERIFICATION_AGENT`, discovered at boot. The verifier answers
through `PackageManager.verifyPendingInstall(id, ALLOW | REJECT)`, and the system then sends it
`ACTION_PACKAGE_VERIFIED`. An AOSP image ships without a verifier, so ours becomes it.

```
adb install / on-device installer
        │  PackageInstaller session
        ▼
PackageManagerService ── PACKAGE_NEEDS_VERIFICATION ──▶ VerificationReceiver
   (holds the install)                                     extendVerificationTimeout(10 min)
        ▲                                                           │
        │                                                           ▼
        │                                                  VerificationService
        │                                                  ApkFacts: SHA-256, signer, label
        │                                                  ProviderRegistry: declared, enabled, accepted
        │                                                  Assessor ─┬─ BinderAdapter ──▶ provider services
        │                                                            ├─ HttpsAdapter  ──▶ https endpoints
        │                                                            └─ EvidenceStore (24 h cache)
        │                                                  Policy.decide()  (strict unanimity)
        │                                                    │ ALLOW / BLOCK            │ REVIEW
        │                                                    ▼                          ▼
        │                                             notification + report      ReviewActivity
        │                                                                        Install / Cancel
        └──────── verifyPendingInstall(ALLOW | REJECT) ◀────┴───────────────────────────┘
                  PACKAGE_VERIFIED ──▶ closes an open review
```

The verifier must be signed with the platform key of the image it runs on and live under
`/system`. The SDK's `default` (AOSP, no Google apps) emulator images are signed with the
public AOSP test keys, which `scripts/build.sh` fetches and signs with.

## The provider contract, as implemented

This follows the specification's revision 9 ("Provider registration and messages",
`website/resources/protocol-examples.json`) and fixes what it leaves to the reference
implementation: the canonical signed bytes, the signature envelope, the limits and the AIDL
types. A notary is declared by an ordinary app with one manifest entry on its `<application>`:

```xml
<meta-data android:name="org.appfair.notarization.PROVIDER" android:resource="@xml/notary" />
```

and an XML resource (see `shared/.../NotaryContract.java` for every attribute):

```xml
<notary protocolVersion="1" providerId="org.appfair.notary.repro" transport="service"
        service=".RebuildNotaryService" name="F-Droid Open Source Software Rebuilder"
        question="Can this app be rebuilt from its public source?">
  <data fields="packageName,versionCode,apkSha256,signingCertificateSha256,apkFile" />
</notary>

<notary protocolVersion="1" providerId="org.appfair.notary.scan" transport="https"
        endpoint="https://…/v1/assess" keys="base64 SubjectPublicKeyInfo[,…]" tls="sha256:…"
        name="VirusTotality" question="Has this app been scanned for malware?">
  <data fields="packageName,versionCode,apkSha256,signingCertificateSha256,language" />
</notary>
```

* **Request** (`NotaryRequest`, or JSON over HTTPS): protocol version, request ID, the app
  identity (package name, decimal version-code string, APK SHA-256, signing-certificate
  SHA-256 digests) and, when the declaration lists it, the language. A read-only descriptor
  of the staged APK is attached only to a service whose declaration lists `apkFile` and that
  the user enabled with that access.
* **Response** (`NotaryAssessment`): provider ID, request ID, the same app identity, verdict
  (approve, deny, revoke, abstain, advisory with `minAge`), reason code, optional explanation,
  `issuedAt` and `expiresAt` as ISO-8601 UTC. A service may also say it cannot answer now
  (`onUnavailable`), which is a missing assessment, not an abstention.
* **Authentication**: a bound service is authenticated by Binder; the coordinator binds only to
  the explicit component of the accepted package and signer, and the service checks that its
  caller holds `PACKAGE_VERIFICATION_AGENT`. HTTPS answers carry ECDSA P-256 signatures from
  the declared `keys`; a service may return a portable attestation signed by its declared
  `issuerKeys`. Both sign the canonical bytes fixed by `Attestation.java` (a version line plus
  sorted-key, whitespace-free JSON of the response without request ID and signatures). The
  live-response envelope additionally binds the attestation signature to the request ID.
  `scripts/notary-server.py` produces the same bytes in Python; the host tests compare them.
* **Validation**: unknown version, mismatched provider, request ID or identity, unknown
  verdict, oversized text or body, expired or future-dated, redirected, or unsigned where a
  signature is required all make the response *invalid*, which also deletes cached evidence
  for that provider and app.
* **Lifecycle**: enabling a notary records its package, signer and data access
  (`ProviderStore`). A provider later installed with another signer is invalid until enabled
  again. If an active provider is removed or replaced while a check runs, the results are
  discarded and collection restarts once within the original deadline. Changing the enabled
  set clears evidence.

## The five example notaries

| Provider ID | App | Simulates | Route |
|---|---|---|---|
| `org.appfair.notary.adv` | Android Developer Verification (`providers/adv`) | Registry lookup by package and certificate: registered, mismatch, unregistered, revoked, or "could not check" | Binder; required whenever installed |
| `org.appfair.notary.scan` | VirusTotality (`providers/scan`) | Hash lookup against a scanner: clean or malware | HTTPS to `scripts/notary-server.py` on the host, certificate-pinned, signed and enveloped |
| `org.appfair.notary.repro` | F-Droid Open Source Software Rebuilder (`providers/repro`) | Fetches the rebuild index, hashes the staged APK through the descriptor and compares byte for byte | Binder, with APK access |
| `org.appfair.notary.report` | Consumer Reporting (`providers/report`) | Returns the organisation's signed review of the package | Binder, on-device, portable attestations checked against `issuerKeys` |
| `org.appfair.notary.media` | Common Sensical Media (`providers/media`) | On-device age-rating database: all ages, 16+ advisory, or unsuitable | Binder, on-device, works offline |

Each service extends `NotaryService`, which does the contract work (caller check, worker
thread, cancel, descriptor cleanup) and honours a demonstration override in the global setting
`notary_override_<last segment of the ID>`: a JSON object with `verdict`, `reason`,
`delay_ms`, `silent`, `crash`, `invalid` or `offline`. The HTTPS server has the same controls
on `/control`. That is how the scenarios make real providers time out, crash or answer
invalidly without special builds.

## Layout

```
coordinator/   the verifier (Java, SDK only, no Gradle); test/ holds host-side checks
shared/        the provider contract: AIDL, parcelables, NotaryContract, Attestation, NotaryService
providers/     adv, scan, repro, report, media: the five example notary apps
samples/       the app under test (built as v1, v2 and v1 with another signing key)
scripts/       create-avd, start-emulator, build, install-coordinator, install-providers,
               notary-server, run-scenario, test-host, ci
```

## Running it

Requirements: an Android SDK with `platforms;android-36`, build-tools 36, the emulator and
`cmdline-tools;latest` (for the image and AVD), a JDK 17 or newer, python3, openssl and curl.

```sh
scripts/test-host.sh            # policy, attestation and canonical-bytes checks, no device needed
scripts/create-avd.sh           # installs the AOSP image and creates the AVD "notarization-aosp36"
scripts/start-emulator.sh       # boots it with -writable-system; HEADLESS=1 for no window
export ANDROID_SERIAL=...       # printed by start-emulator.sh when several emulators run
scripts/build.sh                # every APK, into out/; keys into keys/
scripts/install-coordinator.sh  # pushes the coordinator into /system/app and reboots
scripts/install-providers.sh    # installs and enables the five notaries
scripts/notary-server.sh start  # the HTTPS notary, reachable from the emulator as 10.0.2.2
scripts/run-scenario.sh all     # drives every scenario; screenshots and logs in out/scenarios/
```

`scripts/ci.sh` does all of that on a fresh machine and is what GitHub Actions runs.

Then try it by hand:

```sh
adb install --no-incremental out/sample-v2.apk   # blocks until you answer the dialog on the device
adb logcat -s CommunityNotarization Notary.adv Notary.fossr Notary.cr Notary.cs
adb shell am start -n org.appfair.notarization/.SettingsActivity   # the notary settings screen
```

## Scenarios

| Scenario | What it exercises | Expected |
|---|---|---|
| `approve` | v1: every notary approves, over all three routes | installed silently; notification |
| `review` | v2: age-16 advisory, no rebuild | review dialog; **Install** succeeds |
| `cancel` | same | **Cancel** rejects (`INSTALL_FAILED_VERIFICATION_FAILURE`) |
| `deny` | certificate mismatch, malware, failed rebuild, revoked review, unsuitable | blocked silently; reasons in the notification |
| `exempt` | no optional notaries, adb exempt from ADV | no checks remain; installed |
| `advonly` | only ADV participates | registered installs; mismatch is blocked (ADV-only baseline) |
| `timeout` | HTTPS notary silent past 8 s | timed-out row; review |
| `cache` | approve, then the scan times out | cached evidence used; installed silently |
| `invalid` | foreign signature, then wrong identity | invalid rows; cache not used; review |
| `crash` | provider process dies mid-assessment | service-stopped row; review |
| `offline` | HTTPS server down, ADV and rebuilder offline | unavailable rows; local notaries answer; review |
| `disabled` | only the age notary enabled | the others are not asked |
| `identity` | review provider reinstalled with another key | invalid until enabled again |
| `update` | v1 cached, then v2 | fresh check of the new file |
| `incremental` | streaming install | rejected before the short deadline, with a report; nothing hashed or asked |
| `settings` | the settings screen | every notary listed with route and data access |

## Things to know

* The system builds its verifier list at boot: installing or updating the verifier needs a
  reboot, which `install-coordinator.sh` does.
* SDK images ship with `verifier_verify_adb_installs` set to 0, which is the platform's own
  ADB exemption. The scripts set it to 1 so adb installs are verified, and set
  `community_notarization_adb_as_standard` so the ADV notary takes part in them; by default the
  coordinator skips ADV for adb installs, as the specification's baseline says.
* `adb install` is incremental by default, which gives verifiers only a few seconds. Use
  `--no-incremental` to see the full flow. The specification lets a streaming install succeed
  only on already cached, authenticated evidence for an authenticated APK identity; this
  reference has no such identity for an incomplete file, so it rejects streaming installs with
  a report that says to retry with a complete APK.
* `PACKAGE_VERIFIED` is an implicit broadcast that Android does not deliver to manifest
  receivers of apps targeting API 26 or later. The service registers for it at runtime.
* The default verification budget is 10 seconds. The receiver asks for eleven minutes before
  doing anything else; the review ends after ten minutes or sixty seconds before that deadline,
  whichever comes first, and a review that does not conclude rejects with a report. The system
  caps extensions at one hour.
* The system installer only reports a generic verification failure. The coordinator's
  notification carries the reasons and opens the full report.
* Nothing is remembered between installs. Evidence is cached for 24 hours; choices are not.
* Enabling a notary from the settings screen records the accepted identity at that moment.
  When the enabled set is changed with `adb shell settings` instead, the identity is bound on
  first use.

## Where this departs from, or adds to, the specification

* The bound-service contract has an explicit "cannot answer now" callback (`onUnavailable`).
  Without it a service whose own network is down can only time out, which costs the full
  eight seconds and is indistinguishable from a hung provider.
* ADV-only baseline: a registration check that cannot be completed (unavailable, invalid,
  stopped) is rejected with a report. The specification leaves offline checks in this mode
  unspecified; rejecting is the conservative reading.
* A session whose staging directory holds more than one APK is rejected as unsupported, as the
  specification's scope statement asks.
* The settings store is a global setting plus the coordinator's own acceptance records; a
  production coordinator would own a real settings page.
