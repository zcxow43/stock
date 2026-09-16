package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the institutional-trade (T86) background catch-up (spec:
 * specs/backend/institutional-trade-ingestion.md, 觸發時機). There is no separate progress table
 * and no HTTP endpoint for this feature, so this is the only externally-configurable knob: a
 * single switch, checked at the start of every trigger. Must be disabled in the test profile so
 * tests never depend on the real TWSE T86 endpoint being reachable (same reasoning as
 * {@link BackfillProperties.StartupCatchUp} / {@link MasterSyncProperties.Startup}).
 */
@Component
@ConfigurationProperties(prefix = "app.institutional-trade")
public class InstitutionalTradeProperties {

    private final CatchUp catchUp = new CatchUp();

    public CatchUp getCatchUp() {
        return catchUp;
    }

    public static class CatchUp {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
