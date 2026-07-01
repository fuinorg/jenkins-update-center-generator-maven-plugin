# jenkins-update-center-generator-maven-plugin

[![Java Maven Build](https://github.com/fuinorg/jenkins-update-center-generator-maven-plugin/actions/workflows/maven.yml/badge.svg)](https://github.com/fuinorg/jenkins-update-center-generator-maven-plugin/actions/workflows/maven.yml)
[![Maven Central](https://img.shields.io/maven-central/v/org.fuin/jenkins-update-center-generator-maven-plugin.svg)](https://central.sonatype.com/artifact/org.fuin/jenkins-update-center-generator-maven-plugin)
[![LGPLv3 License](http://img.shields.io/badge/license-LGPLv3-blue.svg)](https://www.gnu.org/licenses/lgpl.html)
[![Java Development Kit 25](https://img.shields.io/badge/JDK-25-green.svg)](https://openjdk.java.net/projects/jdk/25/)

A Maven plugin that generates a [Jenkins update center](https://www.jenkins.io/doc/book/managing/plugins/)
layout (the `update-center.json` family of files) for a small set of **custom** Jenkins plugins that
are hosted on a private server.

It is a minimal, self-contained alternative to the
[`update-center2`](https://github.com/jenkins-infra/update-center2) tool used by the Jenkins project:
instead of crawling an artifact repository and contacting GitHub, popularity statistics and so on, it
simply reads the plugins you declare as dependencies, extracts the metadata from their manifest,
computes the checksums and writes the update center files — optionally digitally signed so Jenkins
accepts your private update site.

See [changelog](CHANGELOG.md) for release information.

## What it does

For every `hpi` / `jpi` dependency of the project the plugin:

* reads `META-INF/MANIFEST.MF` (`Short-Name`, `Long-Name`, `Plugin-Version`, `Jenkins-Version`,
  `Plugin-Dependencies`, `Url`, `Compatible-Since-Version`),
* computes the `sha1` / `sha256` checksums and the size of the file,
* builds a download URL pointing below a configurable base URL, and

writes three files to the output directory (default `target/update-center`):

| File                          | Purpose                                                       |
|-------------------------------|--------------------------------------------------------------|
| `update-center.json`          | JSONP (`updateCenter.post(...)`) — the file Jenkins downloads |
| `update-center.actual.json`   | The raw JSON (for programmatic clients)                       |
| `update-center.json.html`     | HTML wrapper (legacy browser based metadata download)         |

## Usage

Declare your custom plugins as dependencies and bind the `generate` goal:

```xml
<project>
    ...
    <dependencies>
        <!-- Example Jenkins plugin to add to the update center... -->
        <dependency>
            <groupId>org.example.jenkins</groupId>
            <artifactId>my-plugin</artifactId>
            <version>1.0.0</version>
            <type>hpi</type>
        </dependency>
        <!-- Add more plugins here... -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.fuin</groupId>
                <artifactId>jenkins-update-center-generator-maven-plugin</artifactId>
                <version>1.0.0-SNAPSHOT</version>
                <configuration>
                    <id>my-update-center</id>
                    <baseUrl>https://repo.example.org/artifactory/jenkins</baseUrl>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>generate</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

Run it with `mvn package` (the goal is bound to the `package` phase by default).

## Configuration

| Parameter            | Property                       | Default                                                | Description                                                                 |
|----------------------|--------------------------------|--------------------------------------------------------|-----------------------------------------------------------------------------|
| `id`                 | `jenkinsuc.id`                 | *(required)*                                           | Identifier of the update center.                                            |
| `baseUrl`            | `jenkinsuc.baseUrl`            | *(required)*                                           | Base URL below which the plugin files are located.                         |
| `downloadUrlPattern` | `jenkinsuc.downloadUrlPattern` | `{baseUrl}/{name}/{version}/{name}.hpi`                | Template for each download URL (see placeholders below).                    |
| `connectionCheckUrl` | `jenkinsuc.connectionCheckUrl` | value of `baseUrl`                                     | URL Jenkins uses to check connectivity.                                    |
| `outputDirectory`    | `jenkinsuc.outputDirectory`    | `${project.build.directory}/update-center`            | Directory the files are written to.                                        |
| `prettyJson`         | `jenkinsuc.prettyJson`         | `true`                                                 | Whether to pretty-print the JSON.                                          |
| `privateKey`         | `jenkinsuc.privateKey`         | *(none)*                                               | PEM encoded RSA private key file for signing.                              |
| `certificate`        | `jenkinsuc.certificate`        | *(none)*                                               | PEM encoded X.509 certificate (chain) file matching the private key.       |
| `privateKeyEnv`      | `jenkinsuc.privateKeyEnv`      | *(none)*                                               | Name of an env var holding the PEM private key (instead of `privateKey`).  |
| `certificateEnv`     | `jenkinsuc.certificateEnv`     | *(none)*                                               | Name of an env var holding the PEM certificate (instead of `certificate`). |

### URL layout

All URLs are configurable; nothing is hardcoded. The `downloadUrlPattern` supports these
placeholders:

* `{baseUrl}` — the `baseUrl` with a trailing slash removed
* `{name}` — the plugin short name (used as a folder below the base URL)
* `{version}` — the plugin version
* `{fileName}` — the file name of the resolved artifact

With a `baseUrl` of `https://repo.example.org/artifactory/jenkins` and the default pattern, a plugin
`git` version `5.2.1` resolves to `https://repo.example.org/artifactory/jenkins/git/5.2.1/git.hpi`.

## Signing

To have Jenkins accept a private update site, the metadata must be signed by a certificate that
Jenkins trusts. Provide a key and certificate:

```xml
<configuration>
    <id>my-update-center</id>
    <privateKey>${project.basedir}/keys/update-center.key</privateKey>
    <certificate>${project.basedir}/keys/update-center.crt</certificate>
</configuration>
```

When both are set the `signature` block is added to the JSON (SHA-1/SHA-512 digests and
SHA1withRSA/SHA512withRSA signatures plus the Base64 encoded certificate chain). When neither is
set the output is left unsigned.

### Passing key and certificate via environment variables

To avoid writing signing secrets to disk (e.g. in CI), the key and/or certificate can instead be
supplied through environment variables. Set `privateKeyEnv` / `certificateEnv` to the *names* of the
environment variables; their values must be the raw PEM text (the exact content the file would hold,
including the `-----BEGIN ...-----` markers):

```xml
<configuration>
    <id>my-update-center</id>
    <privateKeyEnv>JENKINS_UC_KEY</privateKeyEnv>
    <certificateEnv>JENKINS_UC_CERT</certificateEnv>
</configuration>
```

For each side, provide either the file parameter or the `*Env` parameter, not both (the two sides
may be mixed, e.g. a file key with an env certificate). Referencing an env variable that is unset or
empty fails the build.

A development certificate can be created with OpenSSL:

```bash
openssl genrsa -out update-center.key 4096
openssl req -new -x509 -days 180 -key update-center.key -out update-center.crt \
    -subj "/O=local-development/CN=local-development"
```

For Jenkins to trust a self-signed/private root, copy the certificate into the directory
`update-center-rootCAs/` inside the Jenkins home directory (create it if necessary) and restart
Jenkins. Then configure the update site URL (e.g. `https://.../update-center.json` or a `file://`
URL) under *Manage Jenkins » Plugins » Advanced settings*.

## Building

Requires JDK 25 (enforced by the parent POM). Use the bundled Maven wrapper:

```bash
./mvnw clean verify
```

`verify` runs the full test suite, including an **end-to-end integration test** that starts
**Artifactory** and **Jenkins** (with the
[update-sites-manager-plugin](https://github.com/jenkinsci/update-sites-manager-plugin)) in
containers via the [docker-maven-plugin](https://github.com/fabric8io/docker-maven-plugin),
uploads a signed update center for a real sample plugin, and verifies that Jenkins trusts the
signature, lists the plugin and installs it. A running container engine is therefore required:

* **Docker** (e.g. on CI / GitHub Actions): `./mvnw clean verify` works out of the box.
* **rootless podman** (local development): activate the `podman` profile so the containers use the
  `pasta` network (which podman tears down cleanly):

  ```bash
  export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
  ./mvnw -Ppodman clean verify
  ```

The first run pulls the Artifactory image (~1.8 GB) and builds the Jenkins image, so it takes a
while; subsequent runs are cached.

## License

Copyright (C) Future Invent Informationsmanagement GmbH. Licensed under the
[LGPL v3](http://www.gnu.org/licenses/lgpl.html).
