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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test for {@link HpiArtifact}.
 */
class HpiArtifactTest {

    private HpiArtifact sample() throws Exception {
        final URL url = getClass().getResource("sample.hpi");
        assertThat(url).as("sample.hpi must be on the test classpath").isNotNull();
        return new HpiArtifact(new File(url.toURI()), "fallback-id");
    }

    @Test
    void testManifestAttributes() throws Exception {
        final HpiArtifact hpi = sample();
        assertThat(hpi.getShortName()).isEqualTo("sample");
        assertThat(hpi.getLongName()).isEqualTo("Sample Plugin");
        assertThat(hpi.getVersion()).isEqualTo("1.0.0");
        assertThat(hpi.getRequiredCore()).isEqualTo("2.452.4");
        assertThat(hpi.getCompatibleSinceVersion()).isEqualTo("2.440.1");
        assertThat(hpi.getUrl()).isEqualTo("https://github.com/fuinorg/sample-plugin");
    }

    @Test
    void testShortNameFallsBackToArtifactId() throws Exception {
        final HpiArtifact hpi = new HpiArtifact(new File(getClass().getResource("sample.hpi").toURI()), "fallback-id");
        // The fixture defines Short-Name, so the fallback is NOT used.
        assertThat(hpi.getShortName()).isEqualTo("sample");
    }

    @Test
    void testDependencyParsing() throws Exception {
        final List<HpiArtifact.Dependency> deps = sample().getDependencies();
        assertThat(deps).hasSize(2);

        assertThat(deps.get(0).getName()).isEqualTo("structs");
        assertThat(deps.get(0).getVersion()).isEqualTo("1.20");
        assertThat(deps.get(0).isOptional()).isFalse();

        assertThat(deps.get(1).getName()).isEqualTo("credentials");
        assertThat(deps.get(1).getVersion()).isEqualTo("1300.v1d2");
        assertThat(deps.get(1).isOptional()).isTrue();
    }

    @Test
    void testDigestsAndSize() throws Exception {
        final HpiArtifact hpi = sample();
        assertThat(hpi.getSize()).isGreaterThan(0L);
        assertThat(Base64.getDecoder().decode(hpi.getSha1Base64())).hasSize(20);
        assertThat(Base64.getDecoder().decode(hpi.getSha256Base64())).hasSize(32);
    }

}
