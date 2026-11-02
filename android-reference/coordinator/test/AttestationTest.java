import org.appfair.notarization.provider.Attestation;
import org.appfair.notarization.provider.NotaryAssessment;
import org.appfair.notarization.provider.NotaryContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Checks the signed forms of an assessment (specification, "Authentication"): a portable
 * attestation verifies with the declared key only, any changed field or a foreign key
 * invalidates it, the live-response envelope binds it to one request ID, and the canonical
 * bytes are fixed. When run with a file argument, writes the canonical bytes of the reference
 * payload there so that scripts/test-host.sh can compare them with the Python implementation
 * in scripts/notary-server.py.
 */
public class AttestationTest {
    static int failures = 0, checks = 0;

    static void check(String what, boolean ok) {
        checks++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    /** The specification's example response, with an explanation that needs escaping. */
    static NotaryAssessment sample(String requestId) {
        return new NotaryAssessment(NotaryContract.PROTOCOL_VERSION, "org.example.consumerreporting", requestId,
                "org.example.freecalculator", 1, "0".repeat(64), new String[] {"1".repeat(64)},
                "approve", "NO_CONCERNS", "Review found no concerns: \"quoted\", back\\slash, tab\t, ünïcödé.", 0,
                "2026-11-02T18:00:00Z", "2026-11-03T18:00:00Z", null, null);
    }

    static NotaryAssessment with(NotaryAssessment a, String requestId, String packageName, String verdict,
                                 String expiresAt, String att, String env) {
        return new NotaryAssessment(a.protocolVersion, a.providerId, requestId, packageName, a.versionCode,
                a.apkSha256, a.signingCertificateSha256, verdict, a.reasonCode, a.explanation, a.minAge,
                a.issuedAt, expiresAt, att, env);
    }

    public static void main(String[] args) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair provider = g.generateKeyPair(), stranger = g.generateKeyPair();
        List<PublicKey> accepted = Arrays.asList(provider.getPublic());
        List<PublicKey> rotated = Arrays.asList(stranger.getPublic(), provider.getPublic());

        // canonical bytes: version line, sorted keys at every level, no whitespace, stable escaping
        String canonical = new String(Attestation.canonical(sample("example-1")), StandardCharsets.UTF_8);
        String expected = Attestation.ATTESTATION_HEADER
                + "{\"app\":{\"apkSha256\":\"" + "0".repeat(64) + "\",\"packageName\":\"org.example.freecalculator\","
                + "\"signingCertificateSha256\":[\"" + "1".repeat(64) + "\"],\"versionCode\":\"1\"},"
                + "\"expiresAt\":\"2026-11-03T18:00:00Z\","
                + "\"explanation\":\"Review found no concerns: \\\"quoted\\\", back\\\\slash, tab\\t, ünïcödé.\","
                + "\"issuedAt\":\"2026-11-02T18:00:00Z\",\"protocolVersion\":1,"
                + "\"providerId\":\"org.example.consumerreporting\",\"reasonCode\":\"NO_CONCERNS\",\"verdict\":\"approve\"}";
        check("canonical bytes match the reference", canonical.equals(expected));
        check("canonical excludes the request id and signatures", !canonical.contains("example-1") && !canonical.contains("Signature"));
        check("advisory adds minAge to the canonical form", new String(Attestation.canonical(new NotaryAssessment(1, "p", null,
                "a", 1, "b", new String[0], "advisory", "AGE", "", 16, "2026-11-02T18:00:00Z", "2026-11-03T18:00:00Z", null, null)),
                StandardCharsets.UTF_8).contains("\"minAge\":16,"));
        String iso = NotaryAssessment.isoTime(1_793_661_436_833L);
        check("whole-second ISO times round-trip", iso.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
                && NotaryAssessment.parseTime(iso) == 1_793_661_436_000L
                && NotaryAssessment.parseTime("2026-11-03T18:00:00Z") > NotaryAssessment.parseTime("2026-11-02T18:00:00Z")
                && NotaryAssessment.parseTime("not a time") == -1);
        if (args.length > 0) Files.write(Paths.get(args[0]), canonical.getBytes(StandardCharsets.UTF_8));

        // portable attestation
        NotaryAssessment signed = Attestation.sign(sample("example-1"), provider.getPrivate(), false);
        check("portable attestation verifies with the declared key", Attestation.verify(signed, accepted, false));
        check("portable attestation verifies among rotated keys", Attestation.verify(signed, rotated, false));
        check("portable attestation fails with a stranger's key", !Attestation.verify(signed, Arrays.asList(stranger.getPublic()), false));
        check("portable attestation fails with no keys", !Attestation.verify(signed, Arrays.asList(), false));
        check("portable attestation is not accepted where an envelope is required", !Attestation.verify(signed, accepted, true));
        check("same attestation carried for another request still verifies",
                Attestation.verify(with(signed, "example-2", signed.packageName, signed.verdict, signed.expiresAt,
                        signed.attestationSignature, null), accepted, false));

        // tampering
        check("changed verdict invalidates the attestation", !Attestation.verify(with(signed, signed.requestId,
                signed.packageName, "deny", signed.expiresAt, signed.attestationSignature, null), accepted, false));
        check("changed expiry invalidates the attestation", !Attestation.verify(with(signed, signed.requestId,
                signed.packageName, signed.verdict, "2026-11-04T18:00:00Z", signed.attestationSignature, null), accepted, false));
        check("changed package invalidates the attestation", !Attestation.verify(with(signed, signed.requestId,
                "org.example.other", signed.verdict, signed.expiresAt, signed.attestationSignature, null), accepted, false));
        check("garbage signature is rejected, not thrown",
                !Attestation.verify(signed.withSignatures("not base64!", null), accepted, false)
                && !Attestation.verify(signed.withSignatures(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}), null), accepted, false));

        // live-response envelope
        NotaryAssessment live = Attestation.sign(sample("example-1"), provider.getPrivate(), true);
        check("live response verifies with its envelope", Attestation.verify(live, accepted, true));
        check("envelope replayed for another request id fails", !Attestation.verify(with(live, "example-2",
                live.packageName, live.verdict, live.expiresAt, live.attestationSignature, live.envelopeSignature), accepted, true));
        check("envelope with a stranger's key fails", !Attestation.verify(live, Arrays.asList(stranger.getPublic()), true));
        check("attestation inside a live response is itself portable",
                Attestation.verify(live.withSignatures(live.attestationSignature, null), accepted, false));
        check("envelope canonical bytes are fixed", new String(Attestation.canonicalEnvelope("example-1", "SIG"),
                StandardCharsets.UTF_8).equals(Attestation.ENVELOPE_HEADER + "{\"attestationSignature\":\"SIG\",\"requestId\":\"example-1\"}"));

        // key encoding round trip (the metadata carries base64 SubjectPublicKeyInfo)
        String spki = Base64.getEncoder().encodeToString(provider.getPublic().getEncoded());
        check("public key round-trips through the metadata encoding",
                Attestation.verify(live, Arrays.asList(Attestation.publicKey(spki)), true));

        System.out.println(failures == 0 ? "all " + checks + " attestation checks passed"
                : failures + " of " + checks + " attestation checks failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
