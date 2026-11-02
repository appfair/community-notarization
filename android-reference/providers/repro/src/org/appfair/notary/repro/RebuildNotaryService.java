package org.appfair.notary.repro;

import android.os.ParcelFileDescriptor;

import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;
import org.appfair.notarization.provider.NotaryRequest;
import org.appfair.notarization.provider.NotaryService;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Simulates a reproducible-build notary: fetches the rebuild index for the package (a bundled
 * file standing in for the online index; the override can take it "offline"), then hashes the
 * staged APK through the read-only descriptor the coordinator attached and compares it with
 * the digest the public rebuild produced. Matching -> approve; a known version whose bytes
 * differ -> disapprove; no rebuild for this version -> abstain.
 */
public class RebuildNotaryService extends NotaryService {
    @Override
    protected String providerId() {
        return "org.appfair.notary.repro";
    }

    @Override
    protected NotaryAssessment assess(NotaryRequest request, DemoOverride override) throws Exception {
        if (override.offline) throw new Unavailable("The rebuild index could not be fetched.");
        Thread.sleep(400);   // the simulated index fetch
        JSONObject entry = lookup(request.packageName, request.versionCode);
        if (entry == null) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_ABSTAIN, "NO_REBUILD",
                    "No verified rebuild is available for this version of the app.");
        }
        String digest = request.apk != null ? sha256(request.apk) : request.apkSha256;
        if (digest.equalsIgnoreCase(entry.optString("sha256"))) {
            return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_APPROVE, "REPRODUCED",
                    "The app was reproducibly rebuilt from public source"
                            + (request.apk != null ? " and the staged file matches the rebuild byte for byte." : "."));
        }
        return NotaryAssessment.answer(providerId(), request, NotaryContract.VERDICT_DENY, "REBUILD_MISMATCH",
                "The staged file differs from the public rebuild of this version.");
    }

    private JSONObject lookup(String packageName, long versionCode) throws Exception {
        try (InputStream in = getAssets().open("rebuilds.json")) {
            JSONArray list = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("rebuilds");
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                if (packageName.equals(r.optString("package")) && r.optLong("version_code") == versionCode) return r;
            }
        }
        return null;
    }

    private static String sha256(ParcelFileDescriptor pfd) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
