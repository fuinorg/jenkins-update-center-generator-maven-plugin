#!groovy
// Test-only configuration: complete the install state and disable security so the integration
// test can drive Jenkins through the script console. NEVER use this in production.
import jenkins.model.Jenkins
import jenkins.install.InstallState
import hudson.security.AuthorizationStrategy
import hudson.security.SecurityRealm

def jenkins = Jenkins.get()
jenkins.setInstallState(InstallState.INITIAL_SETUP_COMPLETED)
jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION)
jenkins.setAuthorizationStrategy(AuthorizationStrategy.UNSECURED)
jenkins.save()
