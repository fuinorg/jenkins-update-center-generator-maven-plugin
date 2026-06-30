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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A single plugin entry in the update center JSON metadata. The JSON field names match what Jenkins
 * expects (compatible with the Jenkins {@code update-center2} generator), reduced to the fields that
 * are relevant for a minimal, custom update center.
 */
public final class PluginEntry {

    @JSONField(name = "name", ordinal = 1)
    private final String name;

    @JSONField(name = "version", ordinal = 2)
    private final String version;

    @JSONField(name = "url", ordinal = 3)
    private final String url;

    @JSONField(name = "title", ordinal = 4)
    private final String title;

    @Nullable
    @JSONField(name = "wiki", ordinal = 5)
    private final String wiki;

    @JSONField(name = "requiredCore", ordinal = 6)
    private final String requiredCore;

    @Nullable
    @JSONField(name = "compatibleSinceVersion", ordinal = 7)
    private final String compatibleSinceVersion;

    @JSONField(name = "dependencies", ordinal = 8)
    private final List<DependencyEntry> dependencies;

    @JSONField(name = "sha1", ordinal = 9)
    private final String sha1;

    @JSONField(name = "sha256", ordinal = 10)
    private final String sha256;

    @JSONField(name = "size", ordinal = 11)
    private final long size;

    /**
     * Constructor building an entry from a plugin artifact and a pre-computed download URL.
     *
     * @param hpi Plugin artifact providing the metadata.
     * @param url Download URL the entry should point to.
     *
     * @throws IOException Error reading the plugin metadata (manifest / checksums).
     */
    public PluginEntry(final HpiArtifact hpi, final String url) throws IOException {
        this.name = hpi.getShortName();
        this.version = hpi.getVersion();
        this.url = url;
        this.title = hpi.getLongName();
        this.wiki = hpi.getUrl();
        this.requiredCore = hpi.getRequiredCore();
        this.compatibleSinceVersion = hpi.getCompatibleSinceVersion();
        this.dependencies = new ArrayList<>();
        for (final HpiArtifact.Dependency dep : hpi.getDependencies()) {
            this.dependencies.add(new DependencyEntry(dep));
        }
        this.sha1 = hpi.getSha1Base64();
        this.sha256 = hpi.getSha256Base64();
        this.size = hpi.getSize();
    }

    /**
     * Returns the plugin short name.
     *
     * @return Short name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the plugin version.
     *
     * @return Version.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the download URL.
     *
     * @return URL.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the display name.
     *
     * @return Title.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Returns the documentation URL.
     *
     * @return Wiki URL or {@code null}.
     */
    @Nullable
    public String getWiki() {
        return wiki;
    }

    /**
     * Returns the minimal required Jenkins core version.
     *
     * @return Required core.
     */
    public String getRequiredCore() {
        return requiredCore;
    }

    /**
     * Returns the version since which the plugin is backward compatible.
     *
     * @return Compatible-since version or {@code null}.
     */
    @Nullable
    public String getCompatibleSinceVersion() {
        return compatibleSinceVersion;
    }

    /**
     * Returns the plugin dependencies.
     *
     * @return Dependencies.
     */
    public List<DependencyEntry> getDependencies() {
        return dependencies;
    }

    /**
     * Returns the Base64 encoded SHA-1 checksum.
     *
     * @return SHA-1.
     */
    public String getSha1() {
        return sha1;
    }

    /**
     * Returns the Base64 encoded SHA-256 checksum.
     *
     * @return SHA-256.
     */
    public String getSha256() {
        return sha256;
    }

    /**
     * Returns the size of the plugin file in bytes.
     *
     * @return Size.
     */
    public long getSize() {
        return size;
    }

    /**
     * A single dependency in the update center JSON metadata.
     */
    public static final class DependencyEntry {

        @JSONField(name = "name", ordinal = 1)
        private final String name;

        @JSONField(name = "version", ordinal = 2)
        private final String version;

        @JSONField(name = "optional", ordinal = 3)
        private final boolean optional;

        /**
         * Constructor based on a parsed plugin dependency.
         *
         * @param dependency Source dependency.
         */
        public DependencyEntry(final HpiArtifact.Dependency dependency) {
            this.name = dependency.getName();
            this.version = dependency.getVersion();
            this.optional = dependency.isOptional();
        }

        /**
         * Returns the dependency short name.
         *
         * @return Name.
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the dependency version.
         *
         * @return Version.
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

    }

}
