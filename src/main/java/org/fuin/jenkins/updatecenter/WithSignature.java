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
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringWriter;
import java.security.GeneralSecurityException;

/**
 * Base class for a JSON document that can carry an optional digital {@code signature} block for its
 * own content.
 * <p>
 * Generating the signed document is a two-pass process (identical to the Jenkins
 * {@code update-center2} generator): the signature is computed over the <em>compact</em> JSON
 * serialized <em>without</em> the {@code signature} field, then the document is serialized again
 * (compact or pretty-printed) <em>with</em> the signature field appended. This is exactly what
 * makes Jenkins' "parse, strip signature, recompute digest" verification succeed.
 */
public abstract class WithSignature {

    @Nullable
    private JsonSignature signature;

    /**
     * Returns the signature block. This is the only getter whose result is allowed to change after
     * the first serialization pass; all other content reachable for JSON generation must be stable.
     *
     * @return Signature or {@code null} if the document is not signed.
     */
    @Nullable
    @JSONField(name = "signature", ordinal = 100)
    public JsonSignature getSignature() {
        return signature;
    }

    /**
     * Serializes this document to a JSON string, adding a signature block when the signer is
     * configured.
     *
     * @param signer Signer to use (may be unconfigured, then the result is unsigned).
     * @param pretty Whether to pretty-print the resulting JSON.
     *
     * @return JSON string (signed when the signer is configured).
     *
     * @throws IOException              Error reading key/certificate files during signing.
     * @throws GeneralSecurityException Error during signing.
     */
    public final String toJson(final Signer signer, final boolean pretty)
            throws IOException, GeneralSecurityException {
        this.signature = null;
        final String unsigned = JSON.toJSONString(this, SerializerFeature.DisableCircularReferenceDetect);
        // Sign the canonical form Jenkins uses for verification (key-sorted JSON without the
        // signature block), not the raw fastjson output.
        this.signature = signer.sign(canonicalize(unsigned));
        if (pretty) {
            return JSON.toJSONString(this, SerializerFeature.DisableCircularReferenceDetect,
                    SerializerFeature.PrettyFormat);
        }
        return JSON.toJSONString(this, SerializerFeature.DisableCircularReferenceDetect);
    }

    /**
     * Produces the canonical JSON representation of the given JSON document, using the exact library
     * and method ({@code net.sf.json.JSON#writeCanonical}) Jenkins uses when it verifies an update
     * site signature. Signing this guarantees the digest matches what Jenkins recomputes.
     *
     * @param json JSON document (without a signature block).
     *
     * @return Canonical (key-sorted) JSON.
     *
     * @throws IOException Error generating the canonical form.
     */
    static String canonicalize(final String json) throws IOException {
        final StringWriter writer = new StringWriter();
        net.sf.json.JSONObject.fromObject(json).writeCanonical(writer);
        return writer.toString();
    }

}
