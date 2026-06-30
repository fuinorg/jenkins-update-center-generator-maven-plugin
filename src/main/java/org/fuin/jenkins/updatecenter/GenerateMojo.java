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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generates a Jenkins update center layout (the {@code update-center.json} family of files) for the
 * custom Jenkins plugins declared as {@code hpi} / {@code jpi} dependencies of the project (in
 * {@code compile}, {@code runtime}, {@code provided} or {@code system} scope).
 * <p>
 * For every such dependency the plugin reads the {@code MANIFEST.MF}, computes the checksums and
 * builds a download URL pointing below a configurable base URL on an internal (non public) server.
 * The result can optionally be signed with a private key and X.509 certificate so that Jenkins
 * accepts the private update site.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public final class GenerateMojo extends AbstractMojo {

    private static final Set<String> PLUGIN_TYPES = Set.of("hpi", "jpi");

    /**
     * The current Maven project.
     */
    @SuppressWarnings("NullAway.Init") // Injected by Maven
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    /**
     * Identifier of the generated update center.
     */
    @SuppressWarnings("NullAway.Init") // Injected by Maven
    @Parameter(property = "jenkinsuc.id", required = true)
    private String id;

    /**
     * Base URL below which the plugin files are located on the (internal) server. The plugin short
     * name is used as a folder below this URL.
     */
    @SuppressWarnings("NullAway.Init") // Injected by Maven
    @Parameter(property = "jenkinsuc.baseUrl", required = true)
    private String baseUrl;

    /**
     * Template for the download URL of each plugin. Supported placeholders are {@code {baseUrl}}
     * (trailing slash removed), {@code {name}} (plugin short name), {@code {version}} (plugin
     * version) and {@code {fileName}} (file name of the resolved artifact).
     */
    @SuppressWarnings("NullAway.Init") // Has a default value
    @Parameter(property = "jenkinsuc.downloadUrlPattern",
            defaultValue = "{baseUrl}/{name}/{version}/{name}.hpi")
    private String downloadUrlPattern;

    /**
     * URL Jenkins uses to verify connectivity. Defaults to the {@link #baseUrl} when not set.
     */
    @Nullable
    @Parameter(property = "jenkinsuc.connectionCheckUrl")
    private String connectionCheckUrl;

    /**
     * Output directory the update center files are written to.
     */
    @SuppressWarnings("NullAway.Init") // Has a default value
    @Parameter(property = "jenkinsuc.outputDirectory",
            defaultValue = "${project.build.directory}/update-center")
    private File outputDirectory;

    /**
     * Whether to pretty-print the generated JSON.
     */
    @Parameter(property = "jenkinsuc.prettyJson", defaultValue = "true")
    private boolean prettyJson;

    /**
     * PEM encoded RSA private key used to sign the update center JSON. Signing is only performed
     * when both this and {@link #certificate} are set.
     */
    @Nullable
    @Parameter(property = "jenkinsuc.privateKey")
    private File privateKey;

    /**
     * PEM encoded X.509 certificate (optionally a bundle including intermediate certificates of the
     * chain, signer certificate first) matching the {@link #privateKey}.
     */
    @Nullable
    @Parameter(property = "jenkinsuc.certificate")
    private File certificate;

    @Override
    public void execute() throws MojoExecutionException {

        final String checkUrl = (connectionCheckUrl == null || connectionCheckUrl.isEmpty())
                ? baseUrl : connectionCheckUrl;
        final UpdateCenterRoot root = new UpdateCenterRoot(id, checkUrl);

        final List<Artifact> pluginArtifacts = collectPluginArtifacts();
        if (pluginArtifacts.isEmpty()) {
            getLog().warn("No 'hpi'/'jpi' dependencies found - generating an empty update center.");
        }

        for (final Artifact artifact : pluginArtifacts) {
            root.addPlugin(toEntry(artifact));
        }

        final Signer signer = createSigner();

        try {
            root.writeTo(outputDirectory, signer, prettyJson);
        } catch (final IOException | GeneralSecurityException ex) {
            throw new MojoExecutionException("Failed to write update center to " + outputDirectory, ex);
        }

        getLog().info("Generated update center '" + id + "' with " + root.getPlugins().size()
                + " plugin(s) in " + outputDirectory + " (signed: " + signer.isConfigured() + ")");
    }

    private List<Artifact> collectPluginArtifacts() {
        final List<Artifact> result = new ArrayList<>();
        for (final Artifact artifact : project.getArtifacts()) {
            if (PLUGIN_TYPES.contains(artifact.getType()) && artifact.getFile() != null) {
                result.add(artifact);
            }
        }
        return result;
    }

    private PluginEntry toEntry(final Artifact artifact) throws MojoExecutionException {
        final File file = artifact.getFile();
        try {
            final HpiArtifact hpi = new HpiArtifact(file, artifact.getArtifactId());
            final String url = expandUrl(hpi, file);
            return new PluginEntry(hpi, url);
        } catch (final IOException ex) {
            throw new MojoExecutionException("Failed to read plugin metadata from " + file, ex);
        }
    }

    private String expandUrl(final HpiArtifact hpi, final File file) throws IOException {
        final String strippedBase = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return downloadUrlPattern
                .replace("{baseUrl}", strippedBase)
                .replace("{name}", hpi.getShortName())
                .replace("{version}", hpi.getVersion())
                .replace("{fileName}", file.getName());
    }

    private Signer createSigner() throws MojoExecutionException {
        final boolean hasKey = privateKey != null;
        final boolean hasCert = certificate != null;
        if (hasKey != hasCert) {
            throw new MojoExecutionException(
                    "Both 'privateKey' and 'certificate' must be specified to enable signing (or neither).");
        }
        if (!hasKey) {
            return new Signer();
        }
        return new Signer(privateKey, List.of(certificate));
    }

}
