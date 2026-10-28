package voxel.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationArenaTest {

    @Test
    void allocGrowsTailAndReportsSize() {
        AllocationArena arena = new AllocationArena();
        assertEquals(0L, arena.alloc(10));
        assertEquals(10L, arena.alloc(20));
        assertEquals(30L, arena.getSize());
    }

    @Test
    void freeReusesGapWithoutGrowingTail() {
        AllocationArena arena = new AllocationArena();
        long first = arena.alloc(10);
        long second = arena.alloc(10);
        assertEquals(0L, first);
        assertEquals(10L, second);

        arena.free(first);
        assertEquals(20L, arena.getSize());
        assertEquals(0L, arena.alloc(10)); // reuses the 0..10 gap
        assertEquals(20L, arena.getSize());
    }

    @Test
    void freeingLastBlockShrinksArena() {
        AllocationArena arena = new AllocationArena();
        long first = arena.alloc(10);
        long second = arena.alloc(10);

        arena.free(first);
        arena.free(second);
        assertEquals(0L, arena.getSize());
    }

    @Test
    void expandConsumesFollowingFreeGap() {
        AllocationArena arena = new AllocationArena();
        long first = arena.alloc(10);
        long second = arena.alloc(10);
        arena.alloc(10);

        arena.free(second);
        assertTrue(arena.expand(first, 10));
        assertEquals(20L, arena.getSize(first));
        assertEquals(30L, arena.alloc(10)); // next allocation is a new tail block
    }

    @Test
    void expandTailFlagsResize() {
        AllocationArena arena = new AllocationArena();
        long first = arena.alloc(10);
        assertTrue(arena.getResetResized()); // initial tail allocation
        assertFalse(arena.getResetResized());

        assertTrue(arena.expand(first, 10));
        assertEquals(20L, arena.getSize());
        assertEquals(20L, arena.getSize(first));
        assertTrue(arena.getResetResized());
    }

    @Test
    void expandRejectsBeyondLimit() {
        AllocationArena arena = new AllocationArena();
        arena.setLimit(20);
        long first = arena.alloc(10);

        assertFalse(arena.expand(first, 11));
        assertEquals(10L, arena.getSize());
        assertEquals(10L, arena.getSize(first));
    }

    @Test
    void allocRejectsZeroAndReportsExhaustion() {
        AllocationArena arena = new AllocationArena();
        assertThrows(IllegalArgumentException.class, () -> arena.alloc(0));

        arena.setLimit(10);
        assertEquals(0L, arena.alloc(10));
        assertEquals(AllocationArena.SIZE_LIMIT, arena.alloc(1));
    }

    @Test
    void freeRejectsUntrackedAddress() {
        AllocationArena arena = new AllocationArena();
        assertThrows(IllegalStateException.class, () -> arena.free(123));
    }
}
