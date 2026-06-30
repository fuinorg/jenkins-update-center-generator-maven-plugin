# Jenkins image for the update center end-to-end integration test.
# Pinned to an LTS >= 2.504.1 (required by update-sites-manager 91.x).
FROM jenkins/jenkins:2.504.3-jdk17

# Skip the setup wizard.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# Install the update sites manager plugin (+ its 'credentials' dependency).
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Test-only setup: mark install complete and disable security so the IT can use the script console.
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/
