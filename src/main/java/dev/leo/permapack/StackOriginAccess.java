package dev.leo.permapack;

import java.util.Set;

/** Transient server bookkeeping, never serialized into item components or packets. */
public interface StackOriginAccess {
    Set<SharedBag.Origin> permapack$origins();
    void permapack$origins(Set<SharedBag.Origin> origins);
}
