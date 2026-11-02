# Community App Notarization

An exploratory proposal from the App Fair Project for federated Android app notarization,
augmenting Android Developer Verification with independent assessments.

- `website/`: Astro specification and installation simulator, intended for https://appfair.org/community-notarization/.
- `android-reference/`: the on-device coordinator as a real Android package verifier, five example
  notary apps, and a scripted test harness that boots a stock AOSP emulator image, installs
  everything, and drives the install scenarios (also run by GitHub Actions).
  See `android-reference/README.md`.

## Local website

With Node.js 22.23.4 or newer (supported even-numbered release):

```sh
cd website
npm ci
npm run dev
```

Open http://127.0.0.1:4321/community-notarization/.

```sh
npm test              # policy, ADV baseline, and evidence tests
npm run build        # regenerate resources, Astro/TypeScript checks, static build
npm run test:browser # Chromium and WebKit interaction tests
```

For browser tests on a new machine, first run `npx playwright install chromium webkit`.

Edit `website/resources/locales/en.json` for interface text, `website/resources/scenarios.json`
for the synthetic app fixtures, `website/resources/providers.json` for provider package IDs and
default transports, and `website/resources/vectors.json` for vector resources.
The resource generator creates symbolic `res.str`, `res.assets`, and `res.vectors` accessors.
Development watches these catalogs; builds regenerate them. English is the initial locale.

The draft revision is stored in `website/resources/document.json`. The sticky draft
header uses this value. Increment it for substantial changes to the proposal,
such as policy or protocol updates; minor copy and layout edits keep the current revision.

## Simulator policy

Android Developer Verification is always active. Other notaries participate only when checked.
Checking an optional notary adds its app icon to the home screen; clearing it removes the icon.
The example services are VirusTotality, Consumer Reporting, and Common Sensical Media.
For complete APKs that pass any independent ADV check, all participating notaries must approve
for installation without confirmation. An ADV exemption omits that check from the vote.
If all participating notaries disapprove or revoke approval, installation is blocked. Other combinations, including abstentions and age
guidance, show the evidence and offer **Install** or **Cancel**. A revocation counts as a denial.
Online checks request current assessments. Offline checks can use unexpired evidence. Installed icons persist until reset or page reload; repeated installations
do not create duplicates. No real APKs are downloaded and no notary services are contacted.
App names and assessments are synthetic fixtures, not statements by the named organizations.

The landing page's specification is the policy reference. It includes the provider-app design,
assessment contract, offline behavior, and AOSP implementation plan. Five inline SVG diagrams
show the architecture, platform limits, decision rules, ADV baseline, and evidence reuse. All text, including
diagram labels, uses generated localization accessors. A live overview beside the form and phone
follows the selected app, enabled providers,
assessments, connection, cache, transport, and Android integration settings. All three columns
scale together to fit the available width. The introduction and specification follow below.
The overview shows the package-verifier coordinator separately from Android Developer Verifier. Provider app IDs
under `org.example` are illustrative; the diagram does not contact those apps or services.

The default routes use app services: remote checks for the optional providers except F-Droid,
which uses an on-device database of downloaded, authenticated rebuild assessments. The F-Droid
provider integration is proposed; a catalog entry alone is not proof of a reproducible build.
Transport settings remain editable, including the direct HTTPS route declared in app metadata.

Select **CustomResponse.apk** to set each notary's response. The expected result and installation
dialog use these responses. Unchecked notaries are excluded, and their response selectors are
disabled. Custom responses persist when you switch apps. **Reset simulator** restores all
custom responses to **Approve**, or **Registered** for ADV. The **Age 16+** response is available only for Common Sensical
Media. Android Developer Verification's response remains editable even though its checkbox
cannot be cleared.

### ADV-only baseline

With only ADV active, the simulator models Google's announced worldwide requirements for a
certified Android phone. It does not model the regional rollout schedule. Other installation
permissions and safety checks are assumed to pass. Registration means that the package and
signing certificate are linked to a verified developer; developer identity alone is insufficient.
See the [registration status API](https://developer.android.com/developer-verification/guides/check-registration-status).

Registered apps proceed without an additional ADV restriction. Unregistered apps show
**Install (high-friction)** and an explanation of the advanced flow. The phone simulates setup,
restart, the 24-hour delay, authentication, and choosing a seven-day or indefinite allowance.
After setup, subsequent unregistered installations show **Install anyway** without repeating
the delay. The simulation does not collect credentials or actually wait. The selected state
persists across app selections until reset; use **Advance seven days** to test allowance expiry.
See [Google's advanced-flow instructions](https://support.google.com/android/answer/17588095?hl=en)
and [ADV FAQ](https://developer.android.com/developer-verification/guides/faq).

Expand **ADV baseline settings** to choose the advanced-flow state. The installation method
is in **Android integration settings**. Independent platform ADV checks still apply where present.
ADB and organization-store installations on managed devices are modeled as exempt from ADV.
With ADV alone, missing verification results and the fictional revoked-registration case, and reactivation after allowance expiry are marked as unresolved:
the published guidance does not establish their complete on-device behavior, so the simulator
does not invent an installation outcome. Limited-distribution device authorization, Google’s offline cache
behavior, and independent controls such as Play Protect are not modeled.

### Community evidence and provider settings

With multiple notaries active, expand **Connection, evidence, and provider settings** to change
the connection, APK digest, and assessment source. Providers can use a direct HTTPS endpoint,
an app service that contacts a server, or an app service that assesses locally. Local services
can answer offline. A checked notary represents a provider app that is installed and explicitly
enabled; installing an app alone does not establish trust. ADV remains enabled.

Custom responses include timeouts, unavailable services, stopped services, and invalid responses.
Failures prevent unanimity unless valid cached evidence can replace the missing response.
Invalid responses cannot use that fallback. Evidence expires after 24 simulated hours.
An enabled provider can return a saved attestation issued at simulation hour 0 that expires at
hour 24; repeated use does not extend its lifetime. The legacy hook provides no channel for a
store to attach attestations to the install request. A store supplies evidence as a provider app. **Advance 24 hours** tests expiry.

Each installation evaluates the available assessments. If they require review, the user must
choose **Install** or **Cancel** for that attempt, including when reinstalling the same APK.
Changing trust or transport settings clears cached evidence. **Clear saved data** removes
cached assessments. Reset or page reload clears the entire simulation.

With multiple notaries active, reviewing assessments is sufficient to install an unregistered
app on the legacy verification path. This requires adoption by Google or the device builder.
On builds with independent platform ADV, that check must also be coordinated; the package
verifier cannot override a native denial. With ADV alone, the baseline above applies, subject
to the selected platform path.

The [earlier AOSP prototype](https://github.com/appfair/aosp-verifier/) uses a compiled provider
registry and ignores abstentions when deciding whether approvals or denials agree. This revision
uses provider apps and strict unanimity. It caches assessments but does not save installation choices. The original
source remains unchanged; adapting the Android implementation is a later phase.

### Android integration and deadlines

R9 targets Android 12L–17 (API 32–37) through the existing package-verification hook.
The coordinator is the designated package verifier: the Play Store in the proposed Google
deployment or the privileged reference app on AOSP. It owns review activities and reports;
PackageInstaller does not need new screens. Google adoption requires changes to the Play Store
and Android Developer Verifier. Installing an ordinary APK cannot replace the designated verifier.

**Android integration settings** selects the legacy path, independent platform ADV, or an
AOSP fixture. The platform developer-verification API is documented from version 36.1; device
builds determine its availability. Its failure routing depends on the **installer app’s** target
SDK and permissions, not those of the APK being installed. A native denial can stop community
review. The proposed coordinated mode assumes Google updates its integration to defer or
coordinate the native check. The simulator models the ADV-only confirmation when an advanced-flow allowance is active;
other OS-controlled failure flows remain unresolved. An allowance does not change developer registration.
See the [PackageInstaller failure contract](https://developer.android.com/reference/android/content/pm/PackageInstaller#EXTRA_DEVELOPER_VERIFICATION_FAILURE_REASON).

For complete APKs, request the timeout extension immediately. The default window is ten seconds;
the maximum extension is one hour. R9 reserves eleven minutes, including eight seconds for notaries
and ten minutes for review. The extension API has no success acknowledgement. A known extension failure or review timeout
rejects the attempt; restarting assessment does not renew the overall deadline. Advanced-flow
setup occurs outside the held install; the user retries after setup, rather than keeping a session
open across a reboot or the 24-hour delay.

Incremental mode models the short streaming path, normally three seconds. It never offers review.
Use **Prepare evidence** to populate the coordinator cache without installing, then select
**Incremental: short verification window**. Approval requires authenticated identification of the exact APK and
unanimous, unexpired cached approval. Missing identity, missing evidence, or any other verdict
rejects. Retry with a complete APK to use the review flow. If the coordinator crashes or misses
the short deadline, an unchanged OS can still allow the install by default; this design does not
guarantee fail-closed behavior without device policy or installer cooperation.

A policy rejection first shows a generic installer error. Dismiss it and open **Installation report**
on the phone to see the coordinator’s reasons. This report is separate from the system installer.
The browser simulates notification delivery and deadline outcomes; it does not exercise Android
background activity restrictions or permissions.

### Implementation boundaries

The browser models policy and state transitions. Transport, cryptographic validation, app identity,
and Android IPC are simulated. The contract specifies what the AOSP implementation must validate;
it does not claim the browser implements those security boundaries. The example handles one APK
and one personal-device profile. The initial implementation must reject split-APK and multi-package sessions until it can
validate their complete contents. Administrator-controlled provider settings need additional work.
Synthetic manifest, provider configuration, and message examples are bundled in
`website/resources/protocol-examples.json`. Canonical signing bytes and the versioned AIDL
interface remain implementation work.

The AOSP plan is to adapt the privileged package verifier, discover provider declarations, implement
HTTPS and versioned Binder adapters, and test the same scenarios on an emulator. Provider services
run under their own UIDs. The coordinator owns consent, evidence validation, and the installation
result. Integration must reject expired install sessions and return a decision before the
verification deadline. `android-reference/` implements R9 on an Android 16 AOSP emulator image:
the coordinator as the package verifier (provider discovery, Binder and HTTPS adapters, evidence
validation and caching, the policy order, the review screen, reports), five example notary apps
with working simulated implementations, and a scripted scenario harness that GitHub Actions runs
on the emulator (`.github/workflows/android-reference.yml`). It fixes the canonical signed bytes,
the signature envelope, the limits and the AIDL types. Still open: the API 32–33 compatibility
work, split-APK identity, and an authenticated APK identity for incremental installs, which the
reference currently rejects.

Background: [Android Developer Verification Discourse](https://gist.github.com/agnostic-apollo/b8d8daa24cbdd216687a6bef53d417a6).
The local source reviewed for this first iteration was `/opt/src/github/appfair/aosp-verifier/`,
including its proposal, policy, and verdict protocol.

## GitHub Pages

The workflow in `.github/workflows/website.yml` builds and tests the website on pull requests,
and deploys on pushes to `main` or manual dispatch. Select **GitHub Actions** in the repository's
Pages settings. The Astro site is `https://appfair.org` with base `/community-notarization`;
the project assumes that the organization's Pages domain already uses `appfair.org`.
There is no project-level CNAME overriding that domain.

No Git repository was initialized, committed, or pushed during the initial local setup.
