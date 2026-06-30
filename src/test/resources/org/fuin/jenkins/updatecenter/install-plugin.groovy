import jenkins.model.Jenkins

// Installs the sample plugin from the configured custom update site and dynamically loads it.
def jenkins = Jenkins.get()
def uc = jenkins.getUpdateCenter()

def plugin = uc.getPlugin("fuin-sample")
if (plugin == null) {
    return "ERROR: plugin 'fuin-sample' not offered by any update site"
}

try {
    // deploy(true) = download (with checksum verification against the signed metadata) + dynamic load.
    plugin.deploy(true).get()
} catch (Exception e) {
    return "ERROR: install failed: " + e.toString()
}

def installed = jenkins.pluginManager.getPlugin("fuin-sample")
if (installed == null) {
    return "ERROR: plugin not present after install"
}
return "OK_INSTALLED:" + installed.getVersion()
