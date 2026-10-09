package cn.lunadeer.dominion.misc;

import cn.lunadeer.dominion.configuration.Language;
import cn.lunadeer.dominion.doos.TownTrustDOO;
import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;

import java.util.ArrayList;
import java.util.List;

/** Player-facing labels. Stored values and command arguments remain unchanged. */
public final class DisplayNames {
    private DisplayNames() { }

    public static class PlayerDisplayText extends ConfigurationPart {
        public String ordinaryLand = "Ordinary dominion";
        public String foundingLand = "Founding dominion";
        public String unknownLand = "Dominion";
        public String townScope = "All town land";
        public String outpostScope = "This dominion only";
        public String allow = "Allow";
        public String deny = "Deny";
        public String enabled = "Enabled";
        public String disabled = "Disabled";
        public String chestMenu = "Chest menu";
        public String chatMenu = "Chat menu";
        public String trustBuild = "Build";
        public String trustDestroy = "Destroy";
        public String trustSwitch = "Switches and containers";
        public String trustItemUse = "Item use";
        public String noAdditionalTrust = "None";
    }

    public static String claimKind(String kind) {
        return switch (kind) {
            case "NORMAL" -> Language.playerDisplayText.ordinaryLand;
            case "FOUNDING" -> Language.playerDisplayText.foundingLand;
            default -> Language.playerDisplayText.unknownLand;
        };
    }

    public static String trustScope(TownTrustDOO.TrustScope scope) {
        return scope == TownTrustDOO.TrustScope.TOWN
                ? Language.playerDisplayText.townScope : Language.playerDisplayText.outpostScope;
    }

    public static String permission(boolean allowed) {
        return allowed ? Language.playerDisplayText.allow : Language.playerDisplayText.deny;
    }

    public static String feature(boolean enabled) {
        return enabled ? Language.playerDisplayText.enabled : Language.playerDisplayText.disabled;
    }

    /** Category order follows the four independent trust controls. */
    public static List<String> trustCategories() {
        return List.of(Language.playerDisplayText.trustBuild, Language.playerDisplayText.trustDestroy,
                Language.playerDisplayText.trustSwitch, Language.playerDisplayText.trustItemUse);
    }

    public static String additionalTrust(TownTrustDOO trust) {
        List<String> labels = trustCategories();
        boolean[] values = {trust.isBuild(), trust.isDestroy(), trust.isSwitch(), trust.isItemUse()};
        List<String> allowed = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i]) allowed.add(labels.get(i));
        }
        return allowed.isEmpty() ? Language.playerDisplayText.noAdditionalTrust : String.join(" · ", allowed);
    }
}
