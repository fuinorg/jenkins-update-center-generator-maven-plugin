import jp.ikedam.jenkins.plugins.updatesitesmanager.ManagedUpdateSite
import hudson.model.UpdateSite
import jenkins.model.Jenkins

// Placeholders replaced by the integration test before posting to the script console.
def id = "fuin"
def url = "@SITE_URL@"
def caPem = '''@CA_CERT@'''

def jenkins = Jenkins.get()
def uc = jenkins.getUpdateCenter()

// Build a managed update site that trusts our own signing certificate.
def site = new ManagedUpdateSite(id, url, true, caPem, "fuin test update center", false)

// Replace any existing site with the same id, keep all other (default) sites.
def sites = new ArrayList<UpdateSite>()
for (s in uc.getSites()) {
    if (s.getId() != id) {
        sites.add(s)
    }
}
sites.add(site)
uc.getSites().replaceBy(sites)
uc.save()

// Force a signed fetch now. A bad/untrusted signature makes this fail and leaves data == null.
try {
    uc.getSite(id).updateDirectlyNow()
} catch (Exception e) {
    return "ERROR: fetch failed: " + e.toString()
}

def data = uc.getSite(id).getData()
if (data == null) {
    return "ERROR: no data after fetch (signature verification or download failed)"
}
return "OK_LISTED:" + data.plugins.keySet().join(",")
