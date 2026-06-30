/**
 * Copyright (C) 2026 Future Invent Informationsmanagement GmbH. All rights
 * reserved. <http://www.fuin.org/>
 * <p>
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option) any
 * later version.
 * <p>
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.fuin.jenkins.updatecenter;

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.assertj.core.api.Assertions;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

/**
 * Integration test for the {@link GenerateMojo} that runs a real Maven build with signing enabled
 * and then verifies the signature exactly the way Jenkins does (parse, strip the {@code signature}
 * block, recompute the digests and validate the RSA signatures against the embedded certificate).
 */
@MavenJupiterExtension
class GenerateSignedMojoTestIT {

    private static final File TEST_DIR = new File("target/maven-it/"
            + GenerateSignedMojoTestIT.class.getPackage().getName().replace(".", "/") + "/"
            + GenerateSignedMojoTestIT.class.getSimpleName()
            + "/testGenerateSigned/project");

    @MavenTest
    @MavenGoal("package")
    void testGenerateSigned(MavenExecutionResult result) throws Exception {
        assertThat(result).isSuccessful();

        final File actual = new File(TEST_DIR, "target/update-center/" + UpdateCenterRoot.UPDATE_CENTER_ACTUAL_JSON);
        Assertions.assertThat(actual).exists();

        // Parse with the exact json-lib library Jenkins uses for verification.
        final net.sf.json.JSONObject root = net.sf.json.JSONObject.fromObject(Files.readString(actual.toPath()));

        final net.sf.json.JSONObject signature = root.getJSONObject("signature");
        Assertions.assertThat(signature.isNullObject()).as("signature block must be present").isFalse();
        Assertions.assertThat(signature.getString("correct_digest")).isNotBlank();
        Assertions.assertThat(signature.getString("correct_digest512")).isNotBlank();
        Assertions.assertThat(signature.getString("correct_signature")).isNotBlank();
        Assertions.assertThat(signature.getString("correct_signature512")).isNotBlank();

        final net.sf.json.JSONArray certificates = signature.getJSONArray("certificates");
        Assertions.assertThat(certificates.size()).isGreaterThan(0);

        // The certificate embedded in the metadata must be exactly our test certificate.
        final X509Certificate embedded = decodeCertificate(certificates.getString(0));
        Assertions.assertThat(Base64.getEncoder().encodeToString(embedded.getEncoded()))
                .isEqualTo(Base64.getEncoder().encodeToString(loadResourceCertificate().getEncoded()));

        // Recompute the content that was signed: the canonical form without the signature block.
        root.remove("signature");
        final java.io.StringWriter canonical = new java.io.StringWriter();
        root.writeCanonical(canonical);
        final byte[] data = canonical.toString().getBytes(StandardCharsets.UTF_8);

        // Digests must match.
        Assertions.assertThat(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(data)))
                .isEqualTo(signature.getString("correct_digest"));
        Assertions.assertThat(toHex(MessageDigest.getInstance("SHA-512").digest(data)))
                .isEqualTo(signature.getString("correct_digest512"));

        // Signatures must validate against the public key of the embedded certificate.
        final PublicKey publicKey = embedded.getPublicKey();
        Assertions.assertThat(verify(data, Base64.getDecoder().decode(signature.getString("correct_signature")),
                publicKey, "SHA1withRSA")).as("SHA1withRSA signature must validate").isTrue();
        Assertions.assertThat(verify(data, fromHex(signature.getString("correct_signature512")),
                publicKey, "SHA512withRSA")).as("SHA512withRSA signature must validate").isTrue();
    }

    private X509Certificate loadResourceCertificate() throws Exception {
        try (var in = getClass().getResourceAsStream("test-signing.crt")) {
            Assertions.assertThat(in).as("test-signing.crt must be on the test classpath").isNotNull();
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static X509Certificate decodeCertificate(final String base64) throws Exception {
        final byte[] der = Base64.getDecoder().decode(base64);
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static boolean verify(final byte[] data, final byte[] sig, final PublicKey key,
            final String algorithm) throws Exception {
        final Signature signature = Signature.getInstance(algorithm);
        signature.initVerify(key);
        signature.update(data);
        return signature.verify(sig);
    }

    private static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

}
