package cn.lunadeer.dominion.misc;

import cn.lunadeer.dominion.configuration.Configuration;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CreationWorldPolicyTest {
    private List<String> previous;

    @BeforeEach
    void setUp() {
        previous = Configuration.town.creationDisabledWorlds;
        Configuration.town.creationDisabledWorlds = new ArrayList<>();
    }

    @AfterEach
    void tearDown() { Configuration.town.creationDisabledWorlds = previous; }

    @Test
    void emptyDefaultDoesNotBlockAnyWorld() {
        assertTrue(new Configuration.Town().creationDisabledWorlds.isEmpty());
        for (String name : List.of("world", "world_nether", "resource")) {
            assertDoesNotThrow(() -> Asserts.assertCreationAllowed(sender(false, false), name));
        }
    }

    @Test
    void onlyConfiguredWorldNamesAreBlockedForNonAdmins() {
        Configuration.town.creationDisabledWorlds = List.of(" resource ", "WORLD_NETHER");
        assertThrows(DominionException.class, () -> Asserts.assertCreationAllowed(sender(false, false), "resource"));
        assertThrows(DominionException.class, () -> Asserts.assertCreationAllowed(sender(false, false), "world_nether"));
        assertDoesNotThrow(() -> Asserts.assertCreationAllowed(sender(false, false), "world"));
        assertDoesNotThrow(() -> Asserts.assertCreationAllowed(sender(false, false), "resource_old"));
    }

    @Test
    void opOrAdminPermissionBypassesWorldPolicy() {
        Configuration.town.creationDisabledWorlds = List.of("resource");
        assertDoesNotThrow(() -> Asserts.assertCreationAllowed(sender(true, false), "resource"));
        assertDoesNotThrow(() -> Asserts.assertCreationAllowed(sender(false, true), "resource"));
        assertFalse(Others.isAdministrator(sender(false, false)));
    }

    private CommandSender sender(boolean op, boolean adminPermission) {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOp" -> op;
                    case "hasPermission" -> adminPermission && "dominion.admin".equals(args[0]);
                    case "getName" -> "test-player";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

}
