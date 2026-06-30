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

import com.soebes.itf.jupiter.extension.MavenGoal;
import com.soebes.itf.jupiter.extension.MavenJupiterExtension;
import com.soebes.itf.jupiter.extension.MavenTest;
import com.soebes.itf.jupiter.maven.MavenExecutionResult;
import org.assertj.core.api.Assertions;

import java.io.File;
import java.nio.file.Files;

import static com.soebes.itf.extension.assertj.MavenITAssertions.assertThat;

/**
 * Integration test for the {@link GenerateMojo} executed inside a real Maven build.
 */
@MavenJupiterExtension
class GenerateMojoTestIT {

    private static final File TEST_DIR = new File("target/maven-it/"
            + GenerateMojoTestIT.class.getPackage().getName().replace(".", "/") + "/"
            + GenerateMojoTestIT.class.getSimpleName()
            + "/testGenerate/project");

    @MavenTest
    @MavenGoal("package")
    void testGenerate(MavenExecutionResult result) throws Exception {
        assertThat(result).isSuccessful();

        final File outputDir = new File(TEST_DIR, "target/update-center");
        final File actual = new File(outputDir, UpdateCenterRoot.UPDATE_CENTER_ACTUAL_JSON);
        final File jsonp = new File(outputDir, UpdateCenterRoot.UPDATE_CENTER_JSON);
        final File html = new File(outputDir, UpdateCenterRoot.UPDATE_CENTER_JSON_HTML);

        Assertions.assertThat(actual).exists();
        Assertions.assertThat(jsonp).exists();
        Assertions.assertThat(html).exists();

        final String json = Files.readString(actual.toPath());
        Assertions.assertThat(json).contains("\"id\":\"fuin\"");
        Assertions.assertThat(json).contains("\"name\":\"sample\"");
        // The custom baseUrl + downloadUrlPattern must be honored.
        Assertions.assertThat(json)
                .contains("https://repo.example.org/jenkins/sample/1.0.0/sample.hpi");
    }

}
