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

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Reads the metadata of a single Jenkins plugin file ({@code *.hpi} / {@code *.jpi}) from its
 * {@code META-INF/MANIFEST.MF} and computes the checksums and size of the file.
 * <p>
 * This is a minimal, local-file replacement for the {@code HPI} / {@code MavenArtifact} classes of
 * the Jenkins {@code update-center2} generator. It only reads what is required to build a valid
 * update center entry and never accesses the network.
 */
public final class HpiArtifact {

    private final File file;

    private final Attributes manifest;

    private final String fallbackArtifactId;

    @Nullable
    private String sha1;

    @Nullable
    private String sha256;

    private long size = -1;

    /**
     * Constructor with file and a fallback artifact ID.
     *
     * @param file               Plugin file to read (a JAR named {@code *.hpi} or {@code *.jpi}).
     * @param fallbackArtifactId Maven artifact ID used as the plugin short name in case the manifest
     *                           does not define a {@code Short-Name} attribute.
     *
     * @throws IOException The file could not be read or has no manifest.
     */
    public HpiArtifact(final File file, final String fallbackArtifactId) throws IOException {
        this.file = file;
        this.fallbackArtifactId = fallbackArtifactId;
        try (JarFile jar = new JarFile(file)) {
            final Manifest mf = jar.getManifest();
            if (mf == null) {
                throw new IOException("Plugin file has no MANIFEST.MF: " + file);
            }
            this.manifest = mf.getMainAttributes();
        }
    }

    @Nullable
    private String value(final String name) {
        final String v = manifest.getValue(name);
        if (v == null) {
            return null;
        }
        final String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Returns the unique short name of the plugin. This is the value of the manifest attribute
     * {@code Short-Name} or the Maven artifact ID if that attribute is missing.
     *
     * @return Plugin short name (never empty).
     */
    public String getShortName() {
        final String shortName = value("Short-Name");
        return shortName == null ? fallbackArtifactId : shortName;
    }

    /**
     * Returns the human readable name of the plugin ({@code Long-Name} manifest attribute) or the
     * {@link #getShortName() short name} if that attribute is missing.
     *
     * @return Display name (never empty).
     */
    public String getLongName() {
        final String longName = value("Long-Name");
        return longName == null ? getShortName() : longName;
    }

    /**
     * Returns the version of the plugin ({@code Plugin-Version} manifest attribute).
     *
     * @return Plugin version.
     *
     * @throws IOException The mandatory {@code Plugin-Version} attribute is missing.
     */
    public String getVersion() throws IOException {
        final String version = value("Plugin-Version");
        if (version == null) {
            throw new IOException("Missing 'Plugin-Version' in manifest of: " + file);
        }
        return version;
    }

    /**
     * Returns the minimal required Jenkins core version. This is the value of the manifest attribute
     * {@code Jenkins-Version}, with a fallback to {@code Hudson-Version} and finally to
     * {@code 1.398} (as done by the Jenkins update-center2 generator for very old plugins).
     *
     * @return Required Jenkins core version.
     */
    public String getRequiredCore() {
        String v = value("Jenkins-Version");
        if (v != null) {
            return v;
        }
        v = value("Hudson-Version");
        if (v != null && !"null".equals(v)) {
            return v;
        }
        return "1.398";
    }

    /**
     * Returns the version since which the plugin is backward compatible ({@code Compatible-Since-Version}
     * manifest attribute).
     *
     * @return Compatible-since version or {@code null} if not defined.
     */
    @Nullable
    public String getCompatibleSinceVersion() {
        return value("Compatible-Since-Version");
    }

    /**
     * Returns the documentation URL of the plugin ({@code Url} manifest attribute).
     *
     * @return Plugin URL or {@code null} if not defined.
     */
    @Nullable
    public String getUrl() {
        return value("Url");
    }

    /**
     * Returns the dependencies of the plugin as defined by the {@code Plugin-Dependencies} manifest
     * attribute (a comma separated list of {@code name:version} entries, optionally suffixed with
     * {@code ;resolution:=optional}).
     *
     * @return List of dependencies (never {@code null}, possibly empty).
     */
    public List<Dependency> getDependencies() {
        final String deps = value("Plugin-Dependencies");
        final List<Dependency> result = new ArrayList<>();
        if (deps == null) {
            return result;
        }
        for (final String token : deps.split(",")) {
            final String t = token.trim();
            if (!t.isEmpty()) {
                result.add(new Dependency(t));
            }
        }
        return result;
    }

    /**
     * Returns the size of the plugin file in bytes.
     *
     * @return File size.
     */
    public long getSize() {
        if (size < 0) {
            size = file.length();
        }
        return size;
    }

    /**
     * Returns the Base64 encoded SHA-1 digest of the plugin file (as expected by Jenkins).
     *
     * @return Base64 encoded SHA-1.
     *
     * @throws IOException Error reading the file.
     */
    public String getSha1Base64() throws IOException {
        computeDigests();
        return sha1;
    }

    /**
     * Returns the Base64 encoded SHA-256 digest of the plugin file (as expected by Jenkins).
     *
     * @return Base64 encoded SHA-256.
     *
     * @throws IOException Error reading the file.
     */
    public String getSha256Base64() throws IOException {
        computeDigests();
        return sha256;
    }

    @SuppressWarnings("NullAway") // sha1/sha256 are assigned before this method returns
    private void computeDigests() throws IOException {
        if (sha1 != null && sha256 != null) {
            return;
        }
        try {
            final MessageDigest md1 = MessageDigest.getInstance("SHA-1");
            final MessageDigest md256 = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            try (InputStream raw = Files.newInputStream(file.toPath());
                    DigestInputStream in1 = new DigestInputStream(raw, md1);
                    DigestInputStream in = new DigestInputStream(in1, md256)) {
                while (in.read(buffer) != -1) {
                    // Reading updates both digests.
                }
            }
            final Base64.Encoder encoder = Base64.getEncoder();
            sha1 = encoder.encodeToString(md1.digest());
            sha256 = encoder.encodeToString(md256.digest());
        } catch (final NoSuchAlgorithmException ex) {
            throw new IOException("Failed to compute digests for: " + file, ex);
        }
    }

    /**
     * A single dependency of a plugin parsed from the {@code Plugin-Dependencies} manifest attribute.
     */
    public static final class Dependency {

        private final String name;

        private final String version;

        private final boolean optional;

        /**
         * Constructor parsing a single dependency token like {@code structs:1.20} or
         * {@code credentials:1300.v1d2;resolution:=optional}.
         *
         * @param token Single dependency token.
         */
        public Dependency(final String token) {
            String t = token;
            this.optional = t.endsWith(OPTIONAL_RESOLUTION);
            if (optional) {
                t = t.substring(0, t.length() - OPTIONAL_RESOLUTION.length());
            }
            final String[] pieces = t.split(":");
            this.name = pieces[0];
            this.version = pieces.length > 1 ? pieces[1] : "";
        }

        /**
         * Returns the short name of the dependency plugin.
         *
         * @return Dependency name.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the required version of the dependency plugin.
         *
         * @return Dependency version.
         */
        public String getVersion() {
            return version;
        }

        /**
         * Returns whether the dependency is optional.
         *
         * @return {@code true} if optional.
         */
        public boolean isOptional() {
            return optional;
        }

        private static final String OPTIONAL_RESOLUTION = ";resolution:=optional";
    }

}
