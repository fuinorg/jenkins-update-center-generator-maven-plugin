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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Root object of the Jenkins update center metadata. Serialized to the {@code update-center.json}
 * family of files. Only the fields required for a minimal, custom update center are included
 * ({@code core}, {@code warnings} and {@code deprecations} of the full Jenkins generator are
 * intentionally omitted).
 */
public final class UpdateCenterRoot extends WithSignature {

    /** Name of the JSONP file actually downloaded by Jenkins. */
    public static final String UPDATE_CENTER_JSON = "update-center.json";

    /** Name of the raw JSON file (for programmatic clients). */
    public static final String UPDATE_CENTER_ACTUAL_JSON = "update-center.actual.json";

    /** Name of the HTML wrapper file (legacy browser based metadata download). */
    public static final String UPDATE_CENTER_JSON_HTML = "update-center.json.html";

    private static final String EOL = "\n";

    @JSONField(name = "updateCenterVersion", ordinal = 1)
    private final String updateCenterVersion = "1";

    @JSONField(name = "id", ordinal = 2)
    private final String id;

    @JSONField(name = "connectionCheckUrl", ordinal = 3)
    private final String connectionCheckUrl;

    @JSONField(name = "plugins", ordinal = 4)
    private final Map<String, PluginEntry> plugins = new TreeMap<>();

    /**
     * Constructor with mandatory attributes.
     *
     * @param id                 Identifier of this update center.
     * @param connectionCheckUrl URL Jenkins uses to check internet/server connectivity.
     */
    public UpdateCenterRoot(final String id, final String connectionCheckUrl) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("'id' is required");
        }
        if (connectionCheckUrl == null || connectionCheckUrl.isEmpty()) {
            throw new IllegalArgumentException("'connectionCheckUrl' is required");
        }
        this.id = id;
        this.connectionCheckUrl = connectionCheckUrl;
    }

    /**
     * Returns the version of the update center format (always {@code "1"}).
     *
     * @return Update center version.
     */
    public String getUpdateCenterVersion() {
        return updateCenterVersion;
    }

    /**
     * Returns the identifier of this update center.
     *
     * @return ID.
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the connection check URL.
     *
     * @return URL.
     */
    public String getConnectionCheckUrl() {
        return connectionCheckUrl;
    }

    /**
     * Returns the plugins of this update center keyed by their short name.
     *
     * @return Modifiable map of plugins.
     */
    public Map<String, PluginEntry> getPlugins() {
        return plugins;
    }

    /**
     * Adds a plugin entry (keyed by its {@link PluginEntry#getName() name}).
     *
     * @param entry Entry to add.
     */
    public void addPlugin(final PluginEntry entry) {
        plugins.put(entry.getName(), entry);
    }

    /**
     * Writes the three update center files ({@value #UPDATE_CENTER_JSON},
     * {@value #UPDATE_CENTER_ACTUAL_JSON} and {@value #UPDATE_CENTER_JSON_HTML}) to the given
     * directory.
     *
     * @param outputDir Target directory (created if necessary).
     * @param signer    Signer to use (unsigned when not configured).
     * @param pretty    Whether to pretty-print the JSON.
     *
     * @throws IOException              Error writing the files or reading key/certificate files.
     * @throws GeneralSecurityException Error during signing.
     */
    public void writeTo(final File outputDir, final Signer signer, final boolean pretty)
            throws IOException, GeneralSecurityException {
        Files.createDirectories(outputDir.toPath());
        final String json = toJson(signer, pretty);
        write(new File(outputDir, UPDATE_CENTER_ACTUAL_JSON), json);
        write(new File(outputDir, UPDATE_CENTER_JSON), postCallJson(json));
        write(new File(outputDir, UPDATE_CENTER_JSON_HTML), postMessageHtml(json));
    }

    private static String postCallJson(final String json) {
        return "updateCenter.post(" + EOL + json + EOL + ");";
    }

    private static String postMessageHtml(final String json) {
        // The DOCTYPE / BOM are required to make JSON.stringify work in legacy browsers.
        return "﻿<!DOCTYPE html><html><head><meta http-equiv='Content-Type' content='text/html;charset=UTF-8' />"
                + "</head><body><script>window.onload = function () { window.parent.postMessage(JSON.stringify(" + EOL
                + json + EOL + "),'*'); };</script></body></html>";
    }

    private static void write(final File file, final String content) throws IOException {
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

}
