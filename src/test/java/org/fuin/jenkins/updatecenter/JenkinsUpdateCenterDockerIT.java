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
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full end-to-end integration test (profile {@code docker-it}). It runs against an Artifactory and a
 * Jenkins container started by the docker-maven-plugin:
 * <ol>
 * <li>generates a signed update center for the real sample plugin (reusing the production classes
 * {@link UpdateCenterRoot} / {@link HpiArtifact} / {@link Signer}),</li>
 * <li>uploads the plugin file and the signed metadata to Artifactory,</li>
 * <li>configures Jenkins (via the update-sites-manager-plugin) to trust the signing certificate,</li>
 * <li>verifies that Jenkins lists <em>and</em> installs the plugin.</li>
 * </ol>
 * Container-to-container traffic uses the static alias {@code http://artifactory:8081}; the test
 * itself talks to the dynamically mapped host ports.
 */
class JenkinsUpdateCenterDockerIT {

    // The default generic local repository that Artifactory OSS creates out of the box (creating a
    // new repository requires the Pro-only repository configuration REST API).
    private static final String REPO = "example-repo-local";

    private static final String PLUGIN_NAME = "fuin-sample";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** Host-side Artifactory base, e.g. http://localhost:32769/artifactory */
    private final String artifactoryHostUrl = required("artifactory.host.url");

    /** Container-side Artifactory base used inside generated URLs, e.g. http://artifactory:8081/artifactory */
    private final String artifactoryNetworkUrl = required("artifactory.network.url");

    /** Host-side Jenkins base, e.g. http://localhost:32770 */
    private final String jenkinsHostUrl = required("jenkins.host.url");

    @Test
    void testEndToEnd() throws Exception {

        // --- 0. The real, loadable sample plugin and its coordinates (reuse production code) ---
        final File hpiFile = new File(getClass().getResource("test-plugin.hpi").toURI());
        final HpiArtifact hpi = new HpiArtifact(hpiFile, PLUGIN_NAME);
        final String version = hpi.getVersion();
        assertThat(hpi.getShortName()).isEqualTo(PLUGIN_NAME);

        // --- 1. Wait for the services (belt and suspenders over the docker:start wait) ---
        waitForStatus(artifactoryHostUrl + "/api/system/ping", 200, 200, Duration.ofMinutes(3));
        waitForStatus(jenkinsHostUrl + "/login", 200, 499, Duration.ofMinutes(3));

        // --- 2. Generate the signed update center pointing at the CONTAINER-side Artifactory URL ---
        final File keyFile = new File(getClass().getResource("test-signing.key").toURI());
        final File certFile = new File(getClass().getResource("test-signing.crt").toURI());
        final String base = artifactoryNetworkUrl + "/" + REPO + "/jenkins";
        final UpdateCenterRoot root = new UpdateCenterRoot("fuin", artifactoryNetworkUrl + "/" + REPO);
        root.addPlugin(new PluginEntry(hpi, base + "/" + PLUGIN_NAME + "/" + version + "/" + PLUGIN_NAME + ".hpi"));
        final File genDir = Files.createTempDirectory("uc-it").toFile();
        root.writeTo(genDir, new Signer(keyFile, List.of(certFile)), true);
        final byte[] updateCenterJsonp = Files.readAllBytes(
                new File(genDir, UpdateCenterRoot.UPDATE_CENTER_JSON).toPath());

        // --- 3. Upload the plugin file + signed metadata to the default repository ---
        // Path must match the URL the generator wrote: <base>/<name>/<version>/<name>.hpi
        put(artifactoryHostUrl + "/" + REPO + "/jenkins/" + PLUGIN_NAME + "/" + version + "/" + PLUGIN_NAME + ".hpi",
                Files.readAllBytes(hpiFile.toPath()));
        put(artifactoryHostUrl + "/" + REPO + "/update-center.json", updateCenterJsonp);

        // --- 4. Configure the managed update site in Jenkins and force a signed fetch ---
        final String caPem = Files.readString(Path.of(getClass().getResource("test-signing.crt").toURI()));
        final String siteUrl = artifactoryNetworkUrl + "/" + REPO + "/update-center.json";
        final String configureScript = readResource("add-managed-update-site.groovy")
                .replace("@SITE_URL@", siteUrl)
                .replace("@CA_CERT@", caPem.strip());

        final String listResult = runGroovy(configureScript);
        assertThat(listResult)
                .as("Jenkins must trust the signed site and list the plugin (actual: %s)", listResult)
                .startsWith("OK_LISTED:")
                .contains(PLUGIN_NAME);

        // --- 5. Install the plugin from the custom update site ---
        final String installResult = runGroovy(readResource("install-plugin.groovy"));
        assertThat(installResult)
                .as("Jenkins must download, verify and install the plugin (actual: %s)", installResult)
                .startsWith("OK_INSTALLED:");
    }

    // ----------------------------------------------------------------------------------------------

    private void put(final String url, final byte[] data) throws Exception {
        final HttpRequest request = artifactory(url)
                .header("X-Checksum-Sha256", sha256Hex(data))
                .PUT(BodyPublishers.ofByteArray(data))
                .build();
        final HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).as("upload %s (%s)", url, response.body()).isIn(200, 201);
    }

    private HttpRequest.Builder artifactory(final String url) {
        final String basic = Base64.getEncoder().encodeToString("admin:password".getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic " + basic);
    }

    /** Runs a Groovy script via the Jenkins script console (fetching a CSRF crumb first). */
    private String runGroovy(final String script) throws Exception {
        final JSONObject crumb = JSON.parseObject(get(jenkinsHostUrl + "/crumbIssuer/api/json"));
        final HttpRequest request = HttpRequest.newBuilder(URI.create(jenkinsHostUrl + "/scriptText"))
                .header(crumb.getString("crumbRequestField"), crumb.getString("crumb"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofMinutes(3))
                .POST(BodyPublishers.ofString("script=" + URLEncoder.encode(script, StandardCharsets.UTF_8)))
                .build();
        final HttpResponse<String> response = HTTP.send(request, BodyHandlers.ofString());
        assertThat(response.statusCode()).as("scriptText (%s)", response.body()).isEqualTo(200);
        // The script console prints the returned value prefixed with "Result: ".
        final String body = response.body().strip();
        return body.startsWith("Result: ") ? body.substring("Result: ".length()) : body;
    }

    private String get(final String url) throws Exception {
        final HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", url).isEqualTo(200);
        return response.body();
    }

    private void waitForStatus(final String url, final int min, final int max, final Duration timeout)
            throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try {
                final int code = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10)).GET().build(), BodyHandlers.discarding()).statusCode();
                if (code >= min && code <= max) {
                    return;
                }
            } catch (final Exception ex) {
                last = ex;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Timed out waiting for " + url, last);
    }

    private String readResource(final String name) throws Exception {
        return Files.readString(Path.of(getClass().getResource(name).toURI()), StandardCharsets.UTF_8);
    }

    private static String sha256Hex(final byte[] data) throws Exception {
        final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder sb = new StringBuilder(digest.length * 2);
        for (final byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String required(final String key) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + key);
        }
        return value;
    }

}
