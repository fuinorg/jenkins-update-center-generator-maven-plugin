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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Generates a Jenkins update center layout (the {@code update-center.json} family of files) for the
 * custom Jenkins plugins listed in the {@code plugins} configuration as Maven artifact coordinates.
 * <p>
 * Each coordinate is resolved from the configured repositories and the plugin reads its
 * {@code MANIFEST.MF}, computes the checksums and builds a download URL pointing below a configurable
 * base URL on an internal (non public) server. The result can optionally be signed with a private
 * key and X.509 certificate so that Jenkins accepts the private update site.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PACKAGE)
public final class GenerateMojo extends AbstractMojo {

    private static final Set<String> PLUGIN_TYPES = Set.of("hpi", "jpi");

    private static final String DEFAULT_PLUGIN_TYPE = "hpi";

    /**
     * The Maven artifact coordinates of the Jenkins plugins to include in the update center. Each
     * entry has the form {@code groupId:artifactId:version} with an optional type and classifier
     * ({@code groupId:artifactId:version[:type[:classifier]]}); the type defaults to {@code hpi}.
     * The coordinates are resolved from the project's repositories (exactly the listed artifacts,
     * without transitive resolution).
     */
    @Nullable
    @Parameter(property = "jenkinsuc.plugins")
    private List<String> plugins;

    /**
     * Entry point used to resolve the plugin artifacts from the repositories.
     */
    @SuppressWarnings("NullAway.Init") // Injected by Maven
    @Component
    private RepositorySystem repositorySystem;

    /**
     * The current repository/network configuration of Maven.
     */
    @SuppressWarnings("NullAway.Init") // Injected by Maven
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repositorySession;

    /**
     * The project's remote repositories to use for resolving the plugin artifacts.
     */
    @SuppressWarnings("NullAway.Init") // Injected by Maven
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepositories;

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

    /**
     * Name of an environment variable whose value is the PEM encoded RSA private key used to sign
     * the update center JSON. Mutually exclusive with {@link #privateKey}. Useful in CI to avoid
     * writing the secret key to disk.
     */
    @Nullable
    @Parameter(property = "jenkinsuc.privateKeyEnv")
    private String privateKeyEnv;

    /**
     * Name of an environment variable whose value is the PEM encoded X.509 certificate (optionally a
     * chain, signer certificate first) matching the private key. Mutually exclusive with
     * {@link #certificate}.
     */
    @Nullable
    @Parameter(property = "jenkinsuc.certificateEnv")
    private String certificateEnv;

    @Override
    public void execute() throws MojoExecutionException {

        final String checkUrl = (connectionCheckUrl == null || connectionCheckUrl.isEmpty())
                ? baseUrl : connectionCheckUrl;
        final UpdateCenterRoot root = new UpdateCenterRoot(id, checkUrl);

        final List<Artifact> pluginArtifacts = resolvePluginArtifacts();
        if (pluginArtifacts.isEmpty()) {
            getLog().warn("No plugins configured - generating an empty update center.");
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

    private List<Artifact> resolvePluginArtifacts() throws MojoExecutionException {
        final List<Artifact> result = new ArrayList<>();
        if (plugins == null) {
            return result;
        }
        for (final String coordinate : plugins) {
            final ArtifactRequest request =
                    new ArtifactRequest(parseCoordinate(coordinate), remoteRepositories, null);
            try {
                final ArtifactResult resolved = repositorySystem.resolveArtifact(repositorySession, request);
                result.add(resolved.getArtifact());
            } catch (final ArtifactResolutionException ex) {
                throw new MojoExecutionException("Failed to resolve plugin '" + coordinate + "'", ex);
            }
        }
        return result;
    }

    /**
     * Parses a plugin coordinate of the form {@code groupId:artifactId:version[:type[:classifier]]}
     * into an artifact to resolve. The type defaults to {@code hpi}.
     *
     * @param coordinate Coordinate string as configured.
     *
     * @return Artifact to resolve.
     *
     * @throws MojoExecutionException The coordinate is malformed or uses an unsupported type.
     */
    static Artifact parseCoordinate(final String coordinate) throws MojoExecutionException {
        final String[] parts = coordinate.trim().split(":");
        if (parts.length < 3 || parts.length > 5) {
            throw new MojoExecutionException("Invalid plugin coordinate '" + coordinate
                    + "', expected 'groupId:artifactId:version[:type[:classifier]]'.");
        }
        final String groupId = parts[0];
        final String artifactId = parts[1];
        final String version = parts[2];
        final String type = parts.length >= 4 && !parts[3].isBlank() ? parts[3] : DEFAULT_PLUGIN_TYPE;
        final String classifier = parts.length == 5 ? parts[4] : "";
        if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
            throw new MojoExecutionException("Invalid plugin coordinate '" + coordinate
                    + "', groupId, artifactId and version must not be empty.");
        }
        if (!PLUGIN_TYPES.contains(type)) {
            throw new MojoExecutionException("Invalid type '" + type + "' in plugin coordinate '"
                    + coordinate + "', expected one of " + PLUGIN_TYPES + ".");
        }
        return new DefaultArtifact(groupId, artifactId, classifier, type, version);
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
        final String keyPem = resolvePem(privateKey, privateKeyEnv, "privateKey", "privateKeyEnv", System::getenv);
        final String certPem = resolvePem(certificate, certificateEnv, "certificate", "certificateEnv", System::getenv);
        final boolean hasKey = keyPem != null;
        final boolean hasCert = certPem != null;
        if (hasKey != hasCert) {
            throw new MojoExecutionException("Both a private key (via 'privateKey' or 'privateKeyEnv') and a "
                    + "certificate (via 'certificate' or 'certificateEnv') must be specified to enable signing "
                    + "(or neither).");
        }
        return new Signer(keyPem, certPem);
    }

    /**
     * Resolves a PEM source that may be given either as a file or via the name of an environment
     * variable holding the PEM text. At most one of the two may be set.
     *
     * @param file      File parameter value, or {@code null} if not set.
     * @param envName   Name of the environment variable to read, or {@code null} if not set.
     * @param fileParam Name of the file parameter (for error messages).
     * @param envParam  Name of the environment variable parameter (for error messages).
     * @param env       Environment lookup (injected so the logic is testable without a real
     *                  environment); returns the variable's value or {@code null} if unset.
     *
     * @return The PEM text, or {@code null} if neither source is set.
     *
     * @throws MojoExecutionException Both sources are set, the file cannot be read, or the referenced
     *                                environment variable is unset or empty.
     */
    @Nullable
    static String resolvePem(@Nullable final File file, @Nullable final String envName,
            final String fileParam, final String envParam,
            final Function<String, @Nullable String> env) throws MojoExecutionException {

        if (file != null && envName != null) {
            throw new MojoExecutionException(
                    "Only one of '" + fileParam + "' or '" + envParam + "' may be specified, not both.");
        }
        if (file != null) {
            try {
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            } catch (final IOException ex) {
                throw new MojoExecutionException("Failed to read '" + fileParam + "' from " + file, ex);
            }
        }
        if (envName != null) {
            final String value = env.apply(envName);
            if (value == null) {
                throw new MojoExecutionException("Environment variable '" + envName
                        + "' referenced by '" + envParam + "' is not set.");
            }
            if (value.isBlank()) {
                throw new MojoExecutionException("Environment variable '" + envName
                        + "' referenced by '" + envParam + "' is empty.");
            }
            return value;
        }
        return null;
    }

}
