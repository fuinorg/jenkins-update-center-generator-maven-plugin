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

import com.alibaba.fastjson.annotation.JSONField;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Signature block of an update center JSON file. The field names match exactly what Jenkins expects
 * when verifying the signature of an update site (compatible with the Jenkins {@code update-center2}
 * generator).
 */
public final class JsonSignature {

    @Nullable
    @JSONField(name = "certificates")
    private List<String> certificates;

    @Nullable
    @JSONField(name = "correct_digest")
    private String digest;

    @Nullable
    @JSONField(name = "correct_signature")
    private String signature;

    @Nullable
    @JSONField(name = "correct_digest512")
    private String digest512;

    @Nullable
    @JSONField(name = "correct_signature512")
    private String signature512;

    /**
     * Returns the Base64 encoded X.509 certificate chain (signer certificate first).
     *
     * @return Certificate chain.
     */
    @Nullable
    public List<String> getCertificates() {
        return certificates;
    }

    /**
     * Sets the Base64 encoded X.509 certificate chain (signer certificate first).
     *
     * @param certificates Certificate chain.
     */
    public void setCertificates(final List<String> certificates) {
        this.certificates = certificates;
    }

    /**
     * Returns the Base64 encoded SHA-1 digest of the unsigned JSON.
     *
     * @return SHA-1 digest.
     */
    @Nullable
    public String getDigest() {
        return digest;
    }

    /**
     * Sets the Base64 encoded SHA-1 digest of the unsigned JSON.
     *
     * @param digest SHA-1 digest.
     */
    public void setDigest(final String digest) {
        this.digest = digest;
    }

    /**
     * Returns the Base64 encoded SHA1withRSA signature of the unsigned JSON.
     *
     * @return SHA-1 signature.
     */
    @Nullable
    public String getSignature() {
        return signature;
    }

    /**
     * Sets the Base64 encoded SHA1withRSA signature of the unsigned JSON.
     *
     * @param signature SHA-1 signature.
     */
    public void setSignature(final String signature) {
        this.signature = signature;
    }

    /**
     * Returns the hex encoded SHA-512 digest of the unsigned JSON.
     *
     * @return SHA-512 digest.
     */
    @Nullable
    public String getDigest512() {
        return digest512;
    }

    /**
     * Sets the hex encoded SHA-512 digest of the unsigned JSON.
     *
     * @param digest512 SHA-512 digest.
     */
    public void setDigest512(final String digest512) {
        this.digest512 = digest512;
    }

    /**
     * Returns the hex encoded SHA512withRSA signature of the unsigned JSON.
     *
     * @return SHA-512 signature.
     */
    @Nullable
    public String getSignature512() {
        return signature512;
    }

    /**
     * Sets the hex encoded SHA512withRSA signature of the unsigned JSON.
     *
     * @param signature512 SHA-512 signature.
     */
    public void setSignature512(final String signature512) {
        this.signature512 = signature512;
    }

}
