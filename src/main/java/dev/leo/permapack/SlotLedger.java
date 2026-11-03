package dev.leo.permapack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** The permanent promises made by the 36 physical inventory slots. */
public final class SlotLedger {
    public static final int SIZE = 36;
    private final List<String> bindings;

    public SlotLedger() { bindings = new ArrayList<>(Collections.nCopies(SIZE, "")); }

    public SlotLedger(List<String> saved) {
        if (saved.size() != SIZE) throw new IllegalArgumentException("Expected 36 reservations");
        bindings = new ArrayList<>(saved);
        if (bindings.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null reservation");
    }

    public String at(int index) { return bindings.get(index); }
    public int count() { return (int) bindings.stream().filter(s -> !s.isEmpty()).count(); }
    public List<String> snapshot() { return List.copyOf(bindings); }
    public boolean permits(int slot, String item) { return at(slot).isEmpty() || at(slot).equals(item); }
    public boolean contains(String item) { return bindings.contains(item); }
    public int freeSlot() { return bindings.indexOf(""); }
    public boolean canIntroduce(String item) { return contains(item) || freeSlot() >= 0; }

    public void claim(int slot, String item) {
        if (item == null || item.isBlank()) throw new IllegalArgumentException("An item ID is required");
        if (!permits(slot, item)) throw new IllegalStateException("Slot already reserved for " + at(slot));
        bindings.set(slot, item);
    }

    /** Rearranging slots moves their reservations too, without releasing either. */
    public void swap(int a, int b) {
        String previous = bindings.get(a);
        bindings.set(a, bindings.get(b));
        bindings.set(b, previous);
    }

    public void reset() { Collections.fill(bindings, ""); }
}
