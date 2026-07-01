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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for {@link UpdateCenterRoot} (including the optional signing).
 */
class UpdateCenterRootTest {

    private UpdateCenterRoot rootWithSample() throws Exception {
        final File hpiFile = new File(getClass().getResource("sample.hpi").toURI());
        final HpiArtifact hpi = new HpiArtifact(hpiFile, "sample");
        final UpdateCenterRoot root = new UpdateCenterRoot("fuin", "https://fuinorg.jfrog.io/");
        root.addPlugin(new PluginEntry(hpi,
                "https://fuinorg.jfrog.io/artifactory/jenkins/sample/1.0.0/sample.hpi"));
        return root;
    }

    @Test
    void testWriteUnsigned(@TempDir final File dir) throws Exception {
        rootWithSample().writeTo(dir, new Signer(), true);

        final File actual = new File(dir, UpdateCenterRoot.UPDATE_CENTER_ACTUAL_JSON);
        final File jsonp = new File(dir, UpdateCenterRoot.UPDATE_CENTER_JSON);
        final File html = new File(dir, UpdateCenterRoot.UPDATE_CENTER_JSON_HTML);
        assertThat(actual).exists();
        assertThat(jsonp).exists();
        assertThat(html).exists();

        final JSONObject o = JSON.parseObject(Files.readString(actual.toPath()));
        assertThat(o.getString("updateCenterVersion")).isEqualTo("1");
        assertThat(o.getString("id")).isEqualTo("fuin");
        assertThat(o.getString("connectionCheckUrl")).isEqualTo("https://fuinorg.jfrog.io/");
        assertThat(o.containsKey("signature")).isFalse();

        final JSONObject sample = o.getJSONObject("plugins").getJSONObject("sample");
        assertThat(sample.getString("name")).isEqualTo("sample");
        assertThat(sample.getString("version")).isEqualTo("1.0.0");
        assertThat(sample.getString("url"))
                .isEqualTo("https://fuinorg.jfrog.io/artifactory/jenkins/sample/1.0.0/sample.hpi");
        assertThat(sample.getString("requiredCore")).isEqualTo("2.452.4");
        assertThat(sample.getString("sha1")).isNotBlank();
        assertThat(sample.getString("sha256")).isNotBlank();
        assertThat(sample.getJSONArray("dependencies")).hasSize(2);

        // The JSONP wrapper must contain the post() callback.
        assertThat(Files.readString(jsonp.toPath())).startsWith("updateCenter.post(");
    }

    @Test
    void testWriteSignedAndVerifyLikeJenkins(@TempDir final File dir) throws Exception {
        // Create a throw-away RSA key + self-signed certificate as in-memory PEM text.
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        final KeyPair keyPair = kpg.generateKeyPair();
        final X509Certificate cert = selfSignedCertificate(keyPair);

        final Signer signer = new Signer(toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                toPem("CERTIFICATE", cert.getEncoded()));
        assertThat(signer.isConfigured()).isTrue();

        // Write compact so the file equals "unsigned + signature inserted".
        rootWithSample().writeTo(dir, signer, false);

        final String signedJson = Files.readString(
                new File(dir, UpdateCenterRoot.UPDATE_CENTER_ACTUAL_JSON).toPath());

        // Reproduce exactly what Jenkins does to verify: parse with json-lib, strip "signature",
        // serialize the canonical form, then recompute the digests/signatures over that content.
        final net.sf.json.JSONObject o = net.sf.json.JSONObject.fromObject(signedJson);
        final net.sf.json.JSONObject sig = o.getJSONObject("signature");
        assertThat(sig.isNullObject()).isFalse();
        o.remove("signature");
        final java.io.StringWriter canonical = new java.io.StringWriter();
        o.writeCanonical(canonical);
        final byte[] data = canonical.toString().getBytes(StandardCharsets.UTF_8);

        // Digests
        assertThat(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(data)))
                .isEqualTo(sig.getString("correct_digest"));
        assertThat(toHex(MessageDigest.getInstance("SHA-512").digest(data)))
                .isEqualTo(sig.getString("correct_digest512"));

        // Signatures (validated with the public key from the embedded certificate)
        final net.sf.json.JSONArray certs = sig.getJSONArray("certificates");
        assertThat(certs.size()).isGreaterThan(0);
        final PublicKey publicKey = decodeCertificate(certs.getString(0)).getPublicKey();

        assertThat(verify(data, Base64.getDecoder().decode(sig.getString("correct_signature")),
                publicKey, "SHA1withRSA")).isTrue();
        assertThat(verify(data, fromHex(sig.getString("correct_signature512")),
                publicKey, "SHA512withRSA")).isTrue();
    }

    @Test
    void testWriteSignedRoundTripPretty(@TempDir final File dir) throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        final KeyPair keyPair = kpg.generateKeyPair();
        final X509Certificate cert = selfSignedCertificate(keyPair);

        // Pretty output must still verify (digest is computed over the compact stripped form).
        rootWithSample().writeTo(dir, new Signer(toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                toPem("CERTIFICATE", cert.getEncoded())), true);

        final net.sf.json.JSONObject o = net.sf.json.JSONObject.fromObject(Files.readString(
                new File(dir, UpdateCenterRoot.UPDATE_CENTER_ACTUAL_JSON).toPath()));
        final net.sf.json.JSONObject sig = o.getJSONObject("signature");
        o.remove("signature");
        final java.io.StringWriter canonical = new java.io.StringWriter();
        o.writeCanonical(canonical);
        final byte[] data = canonical.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(data)))
                .isEqualTo(sig.getString("correct_digest"));
    }

    private static X509Certificate selfSignedCertificate(final KeyPair keyPair) throws Exception {
        final X500Name dn = new X500Name("CN=fuin-test, O=local-development");
        final Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        final Date to = new Date(System.currentTimeMillis() + 365L * 86_400_000L);
        final ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(dn, BigInteger.ONE, from, to, dn, keyPair.getPublic())
                        .build(contentSigner));
    }

    private static X509Certificate decodeCertificate(final String base64) throws Exception {
        final byte[] der = Base64.getDecoder().decode(base64);
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static boolean verify(final byte[] data, final byte[] sig, final PublicKey key,
            final String algorithm) throws Exception {
        final Signature signature = Signature.getInstance(algorithm);
        signature.initVerify(key);
        signature.update(data);
        return signature.verify(sig);
    }

    private static String toPem(final String type, final byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
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
