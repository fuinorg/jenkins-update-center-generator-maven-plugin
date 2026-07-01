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

import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.artifact.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test for {@link GenerateMojo}, focusing on {@link GenerateMojo#resolvePem} which resolves a PEM
 * source that may be given either as a file or via the name of an environment variable.
 */
class GenerateMojoTest {

    /** Environment lookup that never has any variable set. */
    private static final Function<String, String> EMPTY_ENV = name -> null;

    @Test
    void testResolveNeither() throws Exception {
        assertThat(resolve(null, null, EMPTY_ENV)).isNull();
    }

    @Test
    void testResolveFromFile(@TempDir final File dir) throws Exception {
        final File file = new File(dir, "key.pem");
        Files.writeString(file.toPath(), "PEM-FROM-FILE", StandardCharsets.UTF_8);

        assertThat(resolve(file, null, EMPTY_ENV)).isEqualTo("PEM-FROM-FILE");
    }

    @Test
    void testResolveFromEnv() throws Exception {
        final Function<String, String> env = Map.of("MY_KEY", "PEM-FROM-ENV")::get;

        assertThat(resolve(null, "MY_KEY", env)).isEqualTo("PEM-FROM-ENV");
    }

    @Test
    void testResolveEnvNotSet() {
        assertThatThrownBy(() -> resolve(null, "MISSING", EMPTY_ENV))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("MISSING")
                .hasMessageContaining("is not set");
    }

    @Test
    void testResolveEnvBlank() {
        final Function<String, String> env = name -> "   \n\t ";

        assertThatThrownBy(() -> resolve(null, "BLANK", env))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("BLANK")
                .hasMessageContaining("is empty");
    }

    @Test
    void testResolveBothFileAndEnv(@TempDir final File dir) {
        final File file = new File(dir, "key.pem");

        assertThatThrownBy(() -> resolve(file, "MY_KEY", EMPTY_ENV))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Only one of")
                .hasMessageContaining("privateKey")
                .hasMessageContaining("privateKeyEnv");
    }

    private static String resolve(final File file, final String envName, final Function<String, String> env)
            throws MojoExecutionException {
        return GenerateMojo.resolvePem(file, envName, "privateKey", "privateKeyEnv", env);
    }

    @Test
    void testParseCoordinateDefaultsToHpi() throws Exception {
        final Artifact artifact = GenerateMojo.parseCoordinate("org.example:my-plugin:1.0.0");

        assertThat(artifact.getGroupId()).isEqualTo("org.example");
        assertThat(artifact.getArtifactId()).isEqualTo("my-plugin");
        assertThat(artifact.getVersion()).isEqualTo("1.0.0");
        assertThat(artifact.getExtension()).isEqualTo("hpi");
        assertThat(artifact.getClassifier()).isEmpty();
    }

    @Test
    void testParseCoordinateWithType() throws Exception {
        final Artifact artifact = GenerateMojo.parseCoordinate("org.example:my-plugin:1.0.0:jpi");

        assertThat(artifact.getExtension()).isEqualTo("jpi");
    }

    @Test
    void testParseCoordinateWithClassifier() throws Exception {
        final Artifact artifact = GenerateMojo.parseCoordinate("org.example:my-plugin:1.0.0:hpi:special");

        assertThat(artifact.getExtension()).isEqualTo("hpi");
        assertThat(artifact.getClassifier()).isEqualTo("special");
    }

    @Test
    void testParseCoordinateTooFewParts() {
        assertThatThrownBy(() -> GenerateMojo.parseCoordinate("org.example:my-plugin"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid plugin coordinate");
    }

    @Test
    void testParseCoordinateBlankComponent() {
        assertThatThrownBy(() -> GenerateMojo.parseCoordinate("org.example::1.0.0"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void testParseCoordinateUnsupportedType() {
        assertThatThrownBy(() -> GenerateMojo.parseCoordinate("org.example:my-plugin:1.0.0:jar"))
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("Invalid type 'jar'");
    }

}
