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

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Creates the {@link JsonSignature} for an update center JSON document. Signing requires a PEM
 * encoded RSA private key and the matching X.509 certificate (optionally followed by intermediate
 * certificates of the chain). When neither is configured the document is left unsigned.
 * <p>
 * The digest/signature algorithms and encodings match exactly what Jenkins expects when verifying
 * the signature of an update site (compatible with the Jenkins {@code update-center2} generator).
 */
public final class Signer {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Nullable
    private final File privateKey;

    private final List<File> certificates;

    /**
     * Constructor for an unsigned generator (no key, no certificate).
     */
    public Signer() {
        this(null, new ArrayList<>());
    }

    /**
     * Constructor with private key and certificate chain.
     *
     * @param privateKey   PEM encoded RSA private key or {@code null} to disable signing.
     * @param certificates X.509 certificate chain (signer certificate first); may be empty to
     *                     disable signing.
     */
    public Signer(@Nullable final File privateKey, final List<File> certificates) {
        this.privateKey = privateKey;
        this.certificates = certificates;
    }

    /**
     * Checks whether the signer is configured to actually create a signature.
     *
     * @return {@code true} if both a private key and at least one certificate are set.
     *
     * @throws IllegalStateException Only one of private key / certificate is set.
     */
    public boolean isConfigured() {
        if (privateKey != null && !certificates.isEmpty()) {
            return true;
        }
        if (privateKey != null || !certificates.isEmpty()) {
            throw new IllegalStateException("Both 'privateKey' and 'certificate' must be specified for signing");
        }
        return false;
    }

    /**
     * Signs the given JSON string.
     *
     * @param json Unsigned JSON document (without the {@code signature} field).
     *
     * @return Signature block or {@code null} if signing is not {@link #isConfigured() configured}.
     *
     * @throws GeneralSecurityException Error during signing or signature self-verification.
     * @throws IOException              Error reading the key or certificate files.
     */
    @Nullable
    public JsonSignature sign(final String json) throws GeneralSecurityException, IOException {
        if (!isConfigured()) {
            return null;
        }

        final List<X509Certificate> certs = loadCertificateChain();
        final PublicKey publicKey = certs.get(0).getPublicKey();
        final PrivateKey key = loadPrivateKey();
        final byte[] data = json.getBytes(StandardCharsets.UTF_8);

        final JsonSignature result = new JsonSignature();

        // SHA-1 digest (Base64) and SHA1withRSA signature (Base64)
        final byte[] digest1 = MessageDigest.getInstance("SHA-1").digest(data);
        final byte[] sig1 = sign(data, key, "SHA1withRSA");
        verify(data, sig1, publicKey, "SHA1withRSA");
        result.setDigest(Base64.getEncoder().encodeToString(digest1));
        result.setSignature(Base64.getEncoder().encodeToString(sig1));

        // SHA-512 digest (hex) and SHA512withRSA signature (hex)
        final byte[] digest512 = MessageDigest.getInstance("SHA-512").digest(data);
        final byte[] sig512 = sign(data, key, "SHA512withRSA");
        verify(data, sig512, publicKey, "SHA512withRSA");
        result.setDigest512(toHex(digest512));
        result.setSignature512(toHex(sig512));

        // Base64 encoded certificate chain
        final List<String> encoded = new ArrayList<>();
        for (final X509Certificate cert : certs) {
            encoded.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        }
        result.setCertificates(encoded);

        return result;
    }

    private static byte[] sign(final byte[] data, final PrivateKey key, final String algorithm)
            throws GeneralSecurityException {
        final Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    private static void verify(final byte[] data, final byte[] sig, final PublicKey key, final String algorithm)
            throws GeneralSecurityException {
        final Signature signature = Signature.getInstance(algorithm);
        signature.initVerify(key);
        signature.update(data);
        if (!signature.verify(sig)) {
            throw new GeneralSecurityException("Signature (" + algorithm
                    + ") failed to validate. The certificate and the private key are probably not matching.");
        }
    }

    private PrivateKey loadPrivateKey() throws IOException {
        try (PEMParser pem = new PEMParser(Files.newBufferedReader(privateKey.toPath(), StandardCharsets.UTF_8))) {
            final Object obj = pem.readObject();
            final JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            if (obj instanceof PEMKeyPair pair) {
                return converter.getKeyPair(pair).getPrivate();
            }
            throw new IOException("Unexpected type for private key in " + privateKey + ": "
                    + (obj == null ? "null" : obj.getClass().getName()));
        }
    }

    private List<X509Certificate> loadCertificateChain() throws GeneralSecurityException, IOException {
        final CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final List<X509Certificate> result = new ArrayList<>();
        for (final File file : certificates) {
            try (InputStream in = new FileInputStream(file)) {
                // A single file may contain a whole chain (signer certificate first).
                for (final java.security.cert.Certificate cert : cf.generateCertificates(in)) {
                    final X509Certificate x509 = (X509Certificate) cert;
                    x509.checkValidity();
                    result.add(x509);
                }
            }
        }
        if (result.isEmpty()) {
            throw new GeneralSecurityException("No X.509 certificate found in: " + certificates);
        }
        return result;
    }

    private static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

}
