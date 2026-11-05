package dev.leo.permapack;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SlotLedgerTest {
    @Test void bucketStatesReuseTheSameAllocation() {
        var ledger=new SlotLedger();ledger.claim(0,"minecraft:bucket");
        for(String state:List.of("minecraft:water_bucket","minecraft:bucket","minecraft:lava_bucket","minecraft:bucket")) {
            var layout=emptyLayout();layout.set(10,state);assertTrue(ledger.arrange(layout));assertEquals(1,ledger.count());
            assertEquals(1,ledger.reserved(state));assertTrue(ledger.contains(state));
        }
    }
    @Test void legacyBucketMigrationUsesTheLargestVariantCountAndKeepsOccupiedStacks() {
        var saved=emptyLayout();saved.set(0,"minecraft:bucket");saved.set(1,"minecraft:water_bucket");saved.set(2,"minecraft:lava_bucket");saved.set(3,"minecraft:water_bucket");
        var ledger=new SlotLedger(saved);assertEquals(2,ledger.count());
        var layout=emptyLayout();layout.set(5,"minecraft:bucket");layout.set(6,"minecraft:water_bucket");layout.set(7,"minecraft:lava_bucket");
        assertTrue(ledger.arrange(layout));assertEquals(3,ledger.count());
        assertEquals(ledger.snapshot(),new SlotLedger(ledger.snapshot()).snapshot());
    }
    @Test void explicitReleaseRemovesOnlyOneAllocationAndMakesRoomForANewType() {
        var ledger=new SlotLedger();ledger.claim(0,"minecraft:dirt");ledger.claim(1,"minecraft:dirt");
        assertEquals("minecraft:dirt",ledger.release(0));assertEquals(1,ledger.count());
        ledger.claim(0,"minecraft:diamond");assertEquals(2,ledger.count());assertEquals(1,ledger.reserved("minecraft:dirt"));
    }
    @Test void bucketsStillRequireSeparateAllocationsForSeparateOccupiedStacks() {
        var ledger=new SlotLedger();var layout=emptyLayout();layout.set(0,"minecraft:water_bucket");layout.set(1,"minecraft:lava_bucket");
        assertTrue(ledger.arrange(layout));assertEquals(2,ledger.count());
        assertTrue(ledger.releaseOne("minecraft:lava_bucket"));assertEquals(1,ledger.count());
    }
    private List<String> emptyLayout() { return new ArrayList<>(Collections.nCopies(36,"")); }
    @Test void ordinaryRearrangementFollowsTheStacksInsteadOfPinningPositions() {
        var ledger=new SlotLedger(); ledger.claim(0,"minecraft:dirt");ledger.claim(1,"minecraft:iron_sword");
        var contents=emptyLayout();contents.set(0,"minecraft:iron_sword");contents.set(20,"minecraft:dirt");
        assertTrue(ledger.arrange(contents));
        assertEquals(2,ledger.count());assertEquals("minecraft:iron_sword",ledger.at(0));assertEquals("minecraft:dirt",ledger.at(20));
        assertEquals("",ledger.at(1));
    }
    @Test void unusedReservationMovesAsideForAnUnrelatedStack() {
        var ledger=new SlotLedger();ledger.claim(0,"minecraft:dirt");ledger.claim(12,"minecraft:iron_sword");
        var contents=emptyLayout();contents.set(0,"minecraft:iron_sword");
        assertTrue(ledger.arrange(contents));assertEquals("minecraft:iron_sword",ledger.at(0));
        assertEquals(1,ledger.reserved("minecraft:dirt"));assertEquals(2,ledger.count());
    }
    @Test void splittingSpendsAnAllocationButMovingAndMergingNeverReleaseOne() {
        var ledger=new SlotLedger();ledger.claim(0,"minecraft:dirt");
        var contents=emptyLayout();contents.set(0,"minecraft:dirt");contents.set(10,"minecraft:dirt");
        assertTrue(ledger.arrange(contents));assertEquals(2,ledger.count());
        contents.set(0,"");contents.set(25,"minecraft:dirt");
        assertTrue(ledger.arrange(contents));assertEquals(2,ledger.count());
        contents.set(25,"");assertTrue(ledger.arrange(contents));assertEquals(2,ledger.count());
    }
    @Test void aFullAllocationBudgetStillPermitsAnyPermutation() {
        var ledger=new SlotLedger();var contents=emptyLayout();
        for(int n=0;n<36;n++) {ledger.claim(n,"test:item_"+n);contents.set(35-n,"test:item_"+n);}
        assertTrue(ledger.arrange(contents));assertEquals(contents,ledger.snapshot());assertEquals(36,ledger.count());
    }
    @Test void refusingAnExtraStackIsAtomicAndDoesNotMoveExistingReservations() {
        var ledger=new SlotLedger();for(int n=0;n<36;n++)ledger.claim(n,"test:item_"+n);
        var before=ledger.snapshot();var contents=emptyLayout();contents.set(2,"test:item_0");contents.set(3,"test:item_0");
        assertFalse(ledger.canArrange(contents));assertFalse(ledger.arrange(contents));assertEquals(before,ledger.snapshot());
    }
    @Test void reservesWithoutReleasingWhenContentsAreUsed() {
        var ledger = new SlotLedger();
        ledger.claim(0, "minecraft:oak_log");
        assertEquals(1, ledger.count());
        assertTrue(ledger.permits(0, "minecraft:oak_log"));
        assertFalse(ledger.permits(0, "minecraft:oak_planks"));
        assertThrows(IllegalStateException.class, () -> ledger.claim(0, "minecraft:oak_planks"));
        assertEquals("minecraft:oak_log", ledger.at(0));
    }
    @Test void repeatedStacksCanPermanentlyReserveSeparatePhysicalSlots() {
        var ledger = new SlotLedger();
        ledger.claim(0, "minecraft:cobblestone");
        ledger.claim(1, "minecraft:cobblestone");
        assertEquals(2, ledger.count());
    }
    @Test void fullBudgetRejectsNewTypesButAllowsRefillingExistingTypes() {
        var ledger = new SlotLedger();
        for (int i = 0; i < 36; i++) ledger.claim(i, "test:item_" + i);
        assertEquals(-1, ledger.freeSlot());
        assertFalse(ledger.canIntroduce("test:new"));
        assertTrue(ledger.canIntroduce("test:item_12"));
        ledger.claim(12, "test:item_12");
        assertEquals(36, ledger.count());
    }
    @Test void hotbarRearrangementMovesBindingsWithoutCreatingCapacity() {
        var ledger = new SlotLedger();
        ledger.claim(0, "minecraft:dirt");
        ledger.claim(20, "minecraft:iron_pickaxe");
        ledger.swap(0, 20);
        assertEquals("minecraft:iron_pickaxe", ledger.at(0));
        assertEquals("minecraft:dirt", ledger.at(20));
        assertEquals(2, ledger.count());
    }
    @Test void movingToAnUnusedPositionDoesNotSpendAnExtraSlot() {
        var ledger = new SlotLedger();
        ledger.claim(18, "minecraft:apple");
        ledger.swap(18, 0);
        assertEquals("", ledger.at(18));
        assertEquals("minecraft:apple", ledger.at(0));
        assertEquals(1, ledger.count());
    }
    @Test void saveSnapshotIsIndependentAndRestoresExactly() {
        var ledger = new SlotLedger();
        ledger.claim(5, "minecraft:diamond");
        var snapshot = ledger.snapshot();
        ledger.claim(6, "minecraft:stick");
        var restored = new SlotLedger(snapshot);
        assertEquals(1, restored.count());
        assertEquals("minecraft:diamond", restored.at(5));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.set(5, ""));
    }
    @Test void invalidSavedDataCannotSilentlyResetTheBudget() {
        assertThrows(IllegalArgumentException.class, () -> new SlotLedger(Collections.nCopies(35, "")));
        var bad = new ArrayList<>(Collections.nCopies(36, ""));
        bad.set(2, null);
        assertThrows(IllegalArgumentException.class, () -> new SlotLedger(bad));
    }
    @Test void explicitResetReleasesAllClaims() {
        var ledger = new SlotLedger();
        ledger.claim(0, "minecraft:oak_planks");
        ledger.reset();
        assertEquals(0, ledger.count());
        assertEquals(0, ledger.freeSlot());
    }
}
