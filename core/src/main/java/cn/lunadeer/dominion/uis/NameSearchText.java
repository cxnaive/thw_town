package cn.lunadeer.dominion.uis;

import cn.lunadeer.dominion.utils.configuration.ConfigurationPart;

public class NameSearchText extends ConfigurationPart {
    public String button = "Search";
    public String hint = "Enter part of a name. Ignore case; ordered characters and space-separated keywords also work. Example: Oak finds OakTown. Up to 64 characters.";
    public String description = "Search names in this list. Partial names and ordered characters work; English case is ignored.";
    public String townTitle = "Search towns";
    public String landTitle = "Search dominions";
    public String allTowns = "All towns";
    public String ownLand = "Your owned and managed dominions";
    public String townLand = "Outposts of {0}";
    public String adminLand = "All dominions on this server (admin)";
    public String summary = "Search: {0} | Results: {1}";
    public String empty = "No matching names. Try a shorter keyword.";
    public String tooLong = "Use at most {0} characters.";
    public String back = "Back to list";
    public String change = "Change search";
    public String clear = "Clear search";
    public String previous = "Previous page";
    public String next = "Next page";
    public String page = "Page {0}/{1}";
    public String teleport = "Teleport";
    public String townDetails = "Mayor: {0} | Members: {1}";
    public String townOwner = "Town: {0}";
    public String personalOwner = "Owner: {0}";
    public String location = "Server: {0} | World: {1}";
    public String townClick = "Left-click: view town";
    public String landClick = "Left-click: view / manage; right-click: teleport";
    public String remote = "This dominion is on {0}, world {1}. Travel there to manage it; use Teleport to visit.";
    public String missing = "This result no longer exists. Search again to refresh the list.";
    public String townUsage = "/town search <keyword> [page] - search all town names";
    public String landUsage = "/dom search <keyword> [page] - search your owned and managed dominions";
}
