# Community App Notarization

A proposal from the App Fair Project for federated Android app notarization, augmenting
Android Developer Verification with independent assessments.

- `website/`: the specification and installation simulator, published at
  https://appfair.org/community-notarization/. The specification is the single reference for
  the policy, the provider contract and the platform integration.
- `android-reference/`: a working implementation of the specification on a stock AOSP emulator
  image, with example notary apps and the test harness that CI runs. See its README.

```sh
cd website && npm ci && npm run dev        # the site at http://127.0.0.1:4321/community-notarization/
cd website && npm test && npm run build    # the site's checks
android-reference/scripts/ci.sh            # host tests, build, emulator, every scenario
```
