package org.fuin.jenkins.sample;

/**
 * Trivial class so the sample plugin contains real (loadable) byte code. The plugin itself has no
 * extensions and no dependencies on other plugins; it only exists as a fixture for the update
 * center end-to-end integration test.
 */
public final class HelloPlugin {

    private HelloPlugin() {
    }

    /**
     * Returns a greeting.
     *
     * @return Constant greeting string.
     */
    public static String greeting() {
        return "Hello from the fuin sample plugin";
    }

}
