package cn.lunadeer.dominion.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lunadeer.dominion.utils.XVersionManager.ImplementationVersion.*;

class XVersionManagerTest {
    @Test void resolvesActual26_2ServerVersionFormats() {
        for (String version : new String[]{"26.2", "26.2.build.7-beta", "26.2-R0.1-SNAPSHOT", "26.2-Lophine-thw-20261009-versionfix"}) {
            assertEquals(v26_2, XVersionManager.resolveVersion(version), version);
        }
    }
    @Test void preservesOlderMappingsAndRejectsUnknownVersions() {
        assertEquals(v1_21_9, XVersionManager.resolveVersion("1.21.11-R0.1-SNAPSHOT"));
        assertEquals(v1_21_4, XVersionManager.resolveVersion("1.21.5-R0.1-SNAPSHOT"));
        assertEquals(v1_20_1, XVersionManager.resolveVersion("1.20.1-R0.1-SNAPSHOT"));
        for (String version : new String[]{"26.1", "26.3", "26.20", "unknown"}) {
            assertNull(XVersionManager.resolveVersion(version), version);
        }
    }
    @Test void sortsTheNewNumberingAfterLegacyImplementations() {
        assertTrue(v26_2.compareWith(v1_21_9) > 0);
        assertEquals(v1_21_9, v26_2.getPrevious());
    }
}
