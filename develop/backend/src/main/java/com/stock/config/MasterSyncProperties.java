package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the startup stock-master sync step (spec: 啟動時同步股票主檔). This is a
 * separate, independently-toggleable switch from {@link BackfillProperties.StartupCatchUp} —
 * the spec requires the two to be "各自獨立開關" even though they always run in the same
 * startup sequence (master sync first, then price catch-up).
 */
@Component
@ConfigurationProperties(prefix = "app.master-sync")
public class MasterSyncProperties {

    private final Startup startup = new Startup();

    public Startup getStartup() {
        return startup;
    }

    /**
     * Enabled by default so a freshly reset database (only the V007 dev seed) grows its stock
     * master to the real listed universe on its own. Must be disabled in the test profile so
     * tests never depend on the real TWSE snapshot endpoint being reachable.
     */
    public static class Startup {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
