#!groovy
// Test-only: Artifactory OSS cannot grant anonymous repository read without the Pro/UI permission
// APIs, and Jenkins' update-site downloader does not send credentials from the URL. So we register
// a JVM-global authenticator that answers Artifactory's "401 WWW-Authenticate: Basic" challenge
// with the default Artifactory admin credentials. Artifactory is the only server that issues a
// Basic challenge in this throw-away test environment.
import java.net.Authenticator
import java.net.PasswordAuthentication

Authenticator.setDefault(new Authenticator() {
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication("admin", "password".toCharArray())
    }
})
