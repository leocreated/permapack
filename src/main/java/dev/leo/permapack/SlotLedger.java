package dev.leo.permapack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;

/** Permanent allocation counts, displayed at the current positions of their stacks. */
public final class SlotLedger {
    public static final int SIZE = 36;
    private final List<String> bindings;

    public SlotLedger() { bindings = new ArrayList<>(Collections.nCopies(SIZE, "")); }

    public SlotLedger(List<String> saved) {
        if (saved.size() != SIZE) throw new IllegalArgumentException("Expected 36 reservations");
        bindings = new ArrayList<>(saved);
        if (bindings.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null reservation");
        // Old saves charged every bucket state independently. Retain the largest
        // variant count; arrange() also preserves every currently occupied stack.
        int buckets = Math.max(Collections.frequency(saved,"minecraft:bucket"),
            Math.max(Collections.frequency(saved,"minecraft:water_bucket"),Collections.frequency(saved,"minecraft:lava_bucket")));
        for(int n=0;n<SIZE;n++) if(allocationKey(saved.get(n)).equals("minecraft:bucket"))
            bindings.set(n,buckets-- > 0 ? "minecraft:bucket" : "");
    }

    public static String allocationKey(String item) {
        return item.equals("minecraft:water_bucket") || item.equals("minecraft:lava_bucket") ? "minecraft:bucket" : item;
    }
    public String at(int index) { return bindings.get(index); }
    public int count() { return (int) bindings.stream().filter(s -> !s.isEmpty()).count(); }
    public List<String> snapshot() { return List.copyOf(bindings); }
    public boolean permits(int slot, String item) { return at(slot).isEmpty() || at(slot).equals(allocationKey(item)); }
    public boolean contains(String item) { return bindings.contains(allocationKey(item)); }
    public int freeSlot() { return bindings.indexOf(""); }
    public boolean canIntroduce(String item) { return contains(item) || freeSlot() >= 0; }
    public int reserved(String item) { return (int) bindings.stream().filter(allocationKey(item)::equals).count(); }
    public String release(int slot) { return bindings.set(slot,""); }
    public boolean releaseOne(String item) {
        int slot=bindings.lastIndexOf(allocationKey(item));
        if(slot<0) return false;
        bindings.set(slot,""); return true;
    }

    private Map<String,Integer> allocationCounts() {
        Map<String,Integer> counts = new LinkedHashMap<>();
        for (String item : bindings) if (!item.isEmpty()) counts.merge(item,1,Integer::sum);
        return counts;
    }
    private Map<String,Integer> requiredCounts(List<String> contents) {
        if (contents.size()!=SIZE || contents.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Expected 36 non-null content IDs");
        Map<String,Integer> counts=allocationCounts();
        Map<String,Integer> used=new LinkedHashMap<>();
        for(String item:contents) if(!item.isEmpty()) used.merge(allocationKey(item),1,Integer::sum);
        used.forEach((item,n)->counts.merge(item,n,Math::max));
        return counts;
    }
    public boolean canArrange(List<String> contents) {
        return requiredCounts(contents).values().stream().mapToInt(Integer::intValue).sum()<=SIZE;
    }
    /** Move allocations with real stacks; keep every unused allocation as an empty reservation. */
    public boolean arrange(List<String> contents) {
        Map<String,Integer> remaining=requiredCounts(contents);
        if(remaining.values().stream().mapToInt(Integer::intValue).sum()>SIZE) return false;
        List<String> next=new ArrayList<>(Collections.nCopies(SIZE,""));
        for(int n=0;n<SIZE;n++) if(!contents.get(n).isEmpty()) {
            String item=allocationKey(contents.get(n)); next.set(n,item); remaining.merge(item,-1,Integer::sum);
        }
        // Retain the visible position of unused reservations where possible, without pinning items to it.
        for(int n=0;n<SIZE;n++) {
            String previous=bindings.get(n);
            if(next.get(n).isEmpty() && remaining.getOrDefault(previous,0)>0) {
                next.set(n,previous); remaining.merge(previous,-1,Integer::sum);
            }
        }
        for(var entry:remaining.entrySet()) for(int n=0;n<entry.getValue();n++)
            next.set(next.indexOf(""),entry.getKey());
        for(int n=0;n<SIZE;n++) bindings.set(n,next.get(n));
        return true;
    }

    public void claim(int slot, String item) {
        if (item == null || item.isBlank()) throw new IllegalArgumentException("An item ID is required");
        if (!permits(slot, item)) throw new IllegalStateException("Slot already reserved for " + at(slot));
        bindings.set(slot, allocationKey(item));
    }

    /** Rearranging slots moves their reservations too, without releasing either. */
    public void swap(int a, int b) {
        String previous = bindings.get(a);
        bindings.set(a, bindings.get(b));
        bindings.set(b, previous);
    }

    public void reset() { Collections.fill(bindings, ""); }
}
