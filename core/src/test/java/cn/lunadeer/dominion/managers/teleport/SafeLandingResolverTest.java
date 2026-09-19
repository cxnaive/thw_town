package cn.lunadeer.dominion.managers.teleport;

import org.bukkit.Material;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.lunadeer.dominion.managers.teleport.TeleportTarget.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeLandingResolverTest {
    private final Map<Integer, Material> blocks = new HashMap<>();
    private final Map<Integer, List<BoundingBox>> shapes = new HashMap<>();
    private boolean insideBorder = true;
    private int ceilingHeight = 320;
    private final SafeLandingResolver.Terrain terrain = new SafeLandingResolver.Terrain() {
        public int minHeight() { return -64; }
        public int maxHeight() { return 320; }
        public int ceilingHeight() { return ceilingHeight; }
        public int highestBlockYAt(int x, int z) {
            return blocks.entrySet().stream().filter(entry -> entry.getValue() != Material.AIR)
                    .mapToInt(Map.Entry::getKey).max().orElse(-65);
        }
        public boolean permitsColumn(int x, double y, int z) { return insideBorder; }
        public SafeLandingResolver.Cell cell(int x, int y, int z) {
            Material type = blocks.getOrDefault(y, Material.AIR);
            return new SafeLandingResolver.Cell(SafeLandingResolver.hazard(type), shapes.getOrDefault(y,
                    type.isSolid() ? List.of(new BoundingBox(0, 0, 0, 1, 1, 1)) : List.of()));
        }
    };
    private final TeleportTarget target = new TeleportTarget(1, 1, UUID.randomUUID(),
            new Point(100.5, 80, 100.5, 90, 25), false, new Bounds(90, -64, 90, 110, 320, 110), null);

    @Test
    void fullSupportAndClearBodyAreSafeWithOrientationIntact() {
        blocks.put(79, Material.STONE);
        assertTrue(safe(target.point()));
        assertEquals(90, target.point().yaw());
        assertEquals(25, target.point().pitch());
    }

    @Test
    void unsupportedAndHalfBlockInsideGroundAreRejected() {
        assertFalse(safe(target.point()));
        blocks.put(79, Material.STONE);
        assertFalse(safe(new Point(100.5, 79.5, 100.5, 0, 0)));
    }

    @Test
    void headCollisionIsRejected() {
        blocks.put(79, Material.STONE);
        blocks.put(81, Material.STONE);
        assertFalse(safe(target.point()));
    }

    @Test
    void slabOnlyWorksAtItsActualSurfaceNotIntegerFeet() {
        blocks.put(79, Material.STONE_SLAB);
        shapes.put(79, List.of(new BoundingBox(0, 0, 0, 1, .5, 1)));
        assertFalse(safe(target.point()));
        assertTrue(safe(new Point(100.5, 79.5, 100.5, 0, 0)));
    }

    @Test
    void dangerousSupportAndLiquidsAreRejected() {
        for (Material type : List.of(Material.MAGMA_BLOCK, Material.CACTUS, Material.CAMPFIRE, Material.LAVA, Material.WATER)) {
            blocks.put(79, type);
            assertFalse(safe(target.point()), type.name());
        }
        blocks.put(79, Material.STONE);
        blocks.put(80, Material.SWEET_BERRY_BUSH);
        assertFalse(safe(target.point()));
    }

    @Test
    void claimBodyBoundaryWorldBorderAndHeightAreEnforced() {
        blocks.put(79, Material.STONE);
        assertFalse(safe(new Point(109.9, 80, 100.5, 0, 0)));
        assertFalse(safe(new Point(100.5, 319, 100.5, 0, 0)));
        insideBorder = false;
        assertFalse(safe(target.point()));
    }

    @Test
    void chunkEdgeDoesNotCauseOffRegionShapeReads() {
        blocks.put(79, Material.STONE);
        assertFalse(safe(new Point(96, 80, 100, 0, 0)));
        assertTrue(safe(new Point(100, 80, 100, 90, 25)));
    }

    @Test
    void ejectionMustLeaveExcludedClaim() {
        var outside = new TeleportTarget(1, -1, target.worldId(), target.point(), false, null, target.inside());
        assertFalse(outside.permits(target.point()));
        assertTrue(outside.permits(new Point(112.5, 80, 100.5, 0, 0)));
    }

    @Test
    void candidateOrderIsBoundedUniqueAndUsesFloorForNegativeCoordinates() {
        Point origin = new Point(-.1, 80, -16.1, 0, 0);
        var columns = SafeLandingResolver.columns(origin, 8);
        assertEquals(289, columns.size());
        assertEquals(columns.size(), columns.stream().distinct().count());
        assertEquals(new SafeLandingResolver.Column(-1, -17), columns.get(0));
        assertTrue(columns.stream().allMatch(column -> Math.abs(column.x() + 1) <= 8 && Math.abs(column.z() + 17) <= 8));
    }

    @Test
    void automaticSearchUsesExposedSurfaceAndNeverFallsThroughItIntoCaves() {
        blocks.put(20, Material.STONE); // Cave floor, much closer to this claim's center Y.
        blocks.put(70, Material.STONE); // Surface above that cave.
        var destination = search(automatic(new Bounds(90, 0, 90, 110, 100, 110)), true, 4096);
        assertNotNull(destination);
        assertEquals(71, destination.point().y());
        assertFalse(destination.airborne());
    }

    @Test
    void undergroundOnlyClaimCannotUseAirFallbackToBypassSkyRequirement() {
        blocks.put(20, Material.STONE);
        blocks.put(70, Material.STONE);
        assertNull(search(automatic(new Bounds(90, 10, 90, 110, 50, 110)), true, 4096));
    }

    @Test
    void explicitIndoorPointIsUsedWithoutSkyOrLargeRoomRequirements() {
        blocks.put(79, Material.STONE);
        blocks.put(82, Material.STONE); // Low room ceiling, with a roof far above it.
        blocks.put(100, Material.STONE);
        var destination = SafeLandingResolver.probe(terrain, target, target.point(), SafeLandingResolver.Rule.EXPLICIT,
                true, new SafeLandingResolver.Budget(1));
        assertNotNull(destination);
        assertEquals(target.point(), destination.point());
        assertFalse(destination.airborne());
    }

    @Test
    void explicitlySelectedAirIsAllowedAndKeepsExactHeightAndOrientation() {
        var destination = SafeLandingResolver.probe(terrain, target, target.point(), SafeLandingResolver.Rule.EXPLICIT,
                true, new SafeLandingResolver.Budget(1));
        assertNotNull(destination);
        assertEquals(target.point(), destination.point());
        assertTrue(destination.airborne());
    }

    @Test
    void emptyHighClaimFallsBackToClearAirInsideItsOwnBounds() {
        blocks.put(70, Material.STONE); // Ground is outside this claim; do not teleport below y1.
        var sky = automatic(new Bounds(90, 200, 90, 110, 230, 110));
        var destination = search(sky, true, 4096);
        assertNotNull(destination);
        assertTrue(destination.airborne());
        assertEquals(215, destination.point().y());
        assertTrue(sky.permits(destination.point()));
        assertTrue(SafeLandingResolver.valid(terrain, sky, destination));
    }

    @Test
    void recheckKeepsAirPolicyButRejectsNewObstaclesAndLostGround() {
        var air = new SafeLandingResolver.Landing(target.point(), true, SafeLandingResolver.Rule.EXPLICIT);
        assertTrue(SafeLandingResolver.valid(terrain, target, air));
        blocks.put(80, Material.STONE);
        assertFalse(SafeLandingResolver.valid(terrain, target, air));
        blocks.clear();
        var ground = new SafeLandingResolver.Landing(target.point(), false, SafeLandingResolver.Rule.EXPLICIT);
        assertFalse(SafeLandingResolver.valid(terrain, target, ground));
        blocks.put(79, Material.STONE);
        assertTrue(SafeLandingResolver.valid(terrain, target, ground));
    }

    @Test
    void ejectionDoesNotGainVoluntaryAirFallback() {
        assertNull(search(target, false, 4096));
        assertNull(SafeLandingResolver.probe(terrain, target, target.point(), SafeLandingResolver.Rule.EXPLICIT,
                false, new SafeLandingResolver.Budget(1)));
    }

    @Test
    void blockedExplicitPointSearchesLocallyNotDownToDistantCaveFloor() {
        blocks.put(80, Material.STONE);
        blocks.put(20, Material.STONE);
        var destination = search(target, true, 4096);
        assertNotNull(destination);
        assertTrue(Math.abs(destination.point().y() - target.point().y()) <= 4.5);
        assertNotEquals(21, destination.point().y());
    }

    @Test
    void automaticSurfaceCanBeHalfSlab() {
        blocks.put(79, Material.STONE_SLAB);
        shapes.put(79, List.of(new BoundingBox(0, 0, 0, 1, .5, 1)));
        var destination = search(automatic(target.inside()), true, 4096);
        assertNotNull(destination);
        assertEquals(79.5, destination.point().y());
        assertFalse(destination.airborne());
    }

    @Test
    void passablePlantsArePartOfOpenSpaceRatherThanAFalseFloor() {
        blocks.put(79, Material.STONE);
        blocks.put(80, Material.TALL_GRASS);
        blocks.put(81, Material.TALL_GRASS);
        var destination = search(automatic(target.inside()), true, 4096);
        assertNotNull(destination);
        assertFalse(destination.airborne());
        assertEquals(80, destination.point().y());
    }

    @Test
    void groundOutranksAirRegardlessOfChunkTraversalOrder() {
        var air = new SafeLandingResolver.Landing(target.point(), true, SafeLandingResolver.Rule.OUTDOOR);
        var nearGround = new SafeLandingResolver.Landing(new Point(102.5, 80, 100.5, 0, 0), false, SafeLandingResolver.Rule.OUTDOOR);
        var farGround = new SafeLandingResolver.Landing(new Point(107.5, 80, 100.5, 0, 0), false, SafeLandingResolver.Rule.OUTDOOR);
        for (var order : List.of(List.of(air, nearGround, farGround), List.of(farGround, air, nearGround))) {
            var selection = new SafeLandingResolver.Selection(target.point(), false);
            order.forEach(selection::offer);
            assertEquals(nearGround, selection.result());
        }
    }

    @Test
    void budgetIsBoundedAndNeverFallsBackToUncheckedCoordinates() {
        var budget = new SafeLandingResolver.Budget(1);
        blocks.put(80, Material.STONE);
        assertNull(SafeLandingResolver.probe(terrain, target, target.point(), SafeLandingResolver.Rule.EXPLICIT, true, budget));
        blocks.clear();
        assertNull(SafeLandingResolver.probe(terrain, target, target.point(), SafeLandingResolver.Rule.EXPLICIT, true, budget));
        assertEquals(0, budget.remaining);
    }

    @Test
    void netherIgnoresBedrockRoofAndUsesFirstSpaciousChamber() {
        ceilingHeight = 128;
        blocks.put(127, Material.BEDROCK);
        blocks.put(115, Material.NETHERRACK);
        blocks.put(79, Material.NETHERRACK);
        var destination = search(automatic(new Bounds(90, 50, 90, 110, 100, 110)), true, 4096);
        assertNotNull(destination);
        assertEquals(80, destination.point().y());
        assertFalse(destination.airborne());
        assertEquals(SafeLandingResolver.Rule.NETHER, destination.rule());
    }

    @Test
    void netherNarrowTunnelDoesNotQualifyForAutomaticLanding() {
        ceilingHeight = 128;
        for (int y = 50; y <= 127; y++) if (y < 80 || y > 81) blocks.put(y, Material.NETHERRACK);
        assertNull(search(automatic(new Bounds(90, 50, 90, 110, 100, 110)), true, 4096));
    }

    private TeleportTarget automatic(Bounds bounds) {
        return new TeleportTarget(1, 1, target.worldId(),
                new Point(100.5, (bounds.y1() + bounds.y2()) / 2.0, 100.5, 0, 0), true, bounds, null);
    }

    private SafeLandingResolver.Landing search(TeleportTarget target, boolean allowAir, int limit) {
        var selection = new SafeLandingResolver.Selection(target.point(), !target.terrain());
        var budget = new SafeLandingResolver.Budget(limit);
        for (var column : SafeLandingResolver.columns(target.point(), 8)) {
            if (budget.remaining <= 0) break;
            SafeLandingResolver.inspectColumn(terrain, target, column, allowAir, budget, selection);
        }
        return selection.result();
    }

    @Test
    void storedPositionsKeepOrientationAndRejectInvalidNumbers() {
        assertEquals(new Point(-1, 80, 2, 90, 25), TeleportTarget.parse("-1:80:2:90:25"));
        assertEquals(new Point(1, 80, 2, 0, 0), TeleportTarget.parse("1:80:2"));
        assertThrows(IllegalArgumentException.class, () -> TeleportTarget.parse("1:2"));
        assertThrows(IllegalArgumentException.class, () -> TeleportTarget.parse("NaN:80:2"));
        assertThrows(IllegalArgumentException.class, () -> TeleportTarget.parse("1:80:2:Infinity:0"));
    }

    private boolean safe(Point point) { return SafeLandingResolver.safe(terrain, target, point); }
}
