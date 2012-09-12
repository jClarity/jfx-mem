package com.jclarity.anim.memory;

import com.jclarity.anim.memory.MemoryBlock.MemoryBlockFactory;
import com.jclarity.anim.memory.MemoryPool.Tenured;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class implements a simple memory model, with a fixed number of rows but
 * variable number of columns
 *
 * @author boxcat
 */
public class MemoryModel {

    private final MemoryPool eden;
    private final MemoryPool s1;
    private final MemoryPool s2;
    private final Tenured tenured;
    // FIXME We also need to model multiple TLABs
    private final ConcurrentMap<Integer, Integer> threadToCurrentTLAB = new ConcurrentHashMap<>();
    private final Lock edenLock = new ReentrantLock();
    // This will become a variable
    private static final int TENURING_THRESHOLD = 4;
    // FIXME Constant used to control lenght of run. Ick.
    private static final int RUN_LENGTH = 200;
    private final MemoryBlockFactory factory = new MemoryBlockFactory();
    private final MemoryBlock[] allocList;
    private int allocMax = 0;
    private boolean isS1Current = false;
    
    //TODO Ben Sanity check me
    private volatile int youngGcCount=0;

    public MemoryPool getEden() {
        return eden;
    }

    public MemoryPool getS1() {
        return s1;
    }

    public MemoryPool getS2() {
        return s2;
    }

    public MemoryPool getTenured() {
        return tenured;
    }

    private MemoryPool currentSurvivorSpace() {
        return (isS1Current ? s1 : s2);
    }

    private void flipSurvivorSpaces() {
        isS1Current = !isS1Current;
    }

    public MemoryModel(int wEden, int wSrv, int wOld, int height) {

        eden = new MemoryPool(wEden, height);
        s1 = new MemoryPool(wSrv, height / 2);
        s2 = new MemoryPool(wSrv, height / 2);
        tenured = new Tenured(wOld, height);

        int nblocks = height * (wEden + 2 * wSrv + wOld);

        allocList = new MemoryBlock[nblocks * RUN_LENGTH];

        // FIXME Just single thread for now
        threadToCurrentTLAB.put(0, 0);
    }

    /**
     * Manages the map of thread ids to currently being used TLAB. Handles
     * multiple allocating threads
     *
     * @param i
     * @return
     */
    boolean setNewTLABForThread(int i) {
        // FIXME Test case PLX
        Integer row = threadToCurrentTLAB.get(i);
        int max = row.intValue();
        for (Integer val : threadToCurrentTLAB.values()) {
            int v = val.intValue();
            if (v >= eden.height() - 1) {
                return false;
            }
            if (v > max) {
                max = v;
            }
        }
        threadToCurrentTLAB.put(i, max + 1);
        return true;
    }

    /**
     * This method allocates a new block in Eden
     *
     */
    void allocate() {
        edenLock.lock();

        try {
            MemoryBlock mb = factory.getBlock();
            mb.setCreatedID(youngGcCount);
            allocMax = mb.getBlockId();
            allocList[allocMax] = mb;

            // FIXME Single allocating thread
            for (int i = 0; i < eden.width(); i++) {
                // Must use getValue() to actually see bindable behaviour
                MemoryBlockView mbv = eden.getValue(i, threadToCurrentTLAB.get(0));
                if (mbv.getStatus() == MemoryStatus.FREE) {
                    mbv.setBlock(mb);
                    return;
                }
            }

            // Now try allocating a new TLAB for this thread
            // FIXME Single allocating thread
            boolean gotNewTLAB = setNewTLABForThread(0);
            System.out.println("Trying to get new TLAB: " + gotNewTLAB);

            if (gotNewTLAB) {
                // Have new TLAB, know we can allocate at offset 0
                eden.getValue(0, threadToCurrentTLAB.get(0)).setBlock(mb);
                return;
            }

            // Can't do anything in Eden, must collect
            youngCollection();

            // Eden is now reset, can allocate at offset 0 on current TLAB
            eden.getValue(0, threadToCurrentTLAB.get(0)).setBlock(mb);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            edenLock.unlock();
        }
    }

    /**
     * This method sets the Memory Block at position i in the allocation list to
     * a dead state
     *
     * @param id
     */
    void destroy(int id) {
        allocList[id].die();
        System.out.println("Killed " + id);
    }

    /**
     * Perform a young gen collection
     */
    private void youngCollection() {
        System.out.println("Trying a young collection");
        // FIXME Sanity check
        if (!selfCheckEden()) {
            throw new RuntimeException("Inconsistent Eden state detected"); 
        }
            
        final List<MemoryBlock> evacuees = new ArrayList<>();

        // We need to step through Eden (& implicitly the allocation list)
        // and save live objects for promotion
        for (int i = 0; i < eden.width(); i++) {
            for (int j = 0; j < eden.height(); j++) {
                MemoryBlock mb = eden.getValue(i, j).getBlock();
                switch (mb.getStatus()) {
                    case ALLOCATED:
                        mb.mark();
                        evacuees.add(mb);
                        break;
                    case DEAD:
                        break;
                    // Next two can't happen
                    case FREE:
                    default:
                        System.out.println("Block id " + mb.getBlockId() + " with status of: " + mb.getStatus() + " detected in Eden at " + i + ", " + j);
                }
            }
        }
        moveToSurvivorSpace(evacuees);

        // Now reset all of Eden to FREE state
        eden.reset();

        // Reset threadToCurrentTLAB
        // Handles multiple allocating threads
        for (Integer tNum : threadToCurrentTLAB.keySet()) {
            int t = tNum.intValue();
            threadToCurrentTLAB.put(t, t);
        }

        // Walk alloc list & unmark
        for (int i = 1; i < allocMax; i++) {
            allocList[i].unmark();
        }
        youngGcCount++;
    }

    /**
     *
     * @param evacuees
     */
    private void moveToSurvivorSpace(List<MemoryBlock> evacuees) {
        MemoryPool from = currentSurvivorSpace();

        // First of all, make sure that we're not so large that we won't fit in
        // survivor space. This implies an effective tenuring threshold of 1
        if (evacuees.size() > from.size()) {
            // We need to check that we'll fit in tenured.
            // If we won't, we need to compact tenured first off
            if (evacuees.size() > tenured.spaceFree()) {
                tenured.compact();
            }
            
            prematurePromote(0);
            flipSurvivorSpaces();
            moveToTenured(evacuees);
            from.reset();
            return;
        }

        // NOTE We always flip survivor spaces on every YG collection
        flipSurvivorSpaces();
        MemoryPool to = currentSurvivorSpace();
        int spaceFree = compactAndEvacuateSrvSpace(from, to);
        if (spaceFree >= evacuees.size()) {
            // We have enough space - move the survivors into the new to space
            for (MemoryBlock mb : evacuees) {
                if (!to.tryAdd(mb)) {
                    System.out.println("Block id " + mb.getBlockId() + " status: " + mb.getStatus() + " failed YG promotion (after flip)");
                }
            }
            return;
        }

        // If we get here, we need to promote some survivors to tenured
        System.out.println("Need to prematurely promote");
        for (int gen = TENURING_THRESHOLD; gen > 0; gen--) {
            spaceFree = prematurePromote(gen);
            if (spaceFree >= evacuees.size()) {
                // We made space - move the survivors into the new to space
                for (MemoryBlock mb : evacuees) {
                    if (!to.tryAdd(mb)) {
                        System.out.println("Block id " + mb.getBlockId() + " status: " + mb.getStatus() + " failed YG promotion (after premature promotion)");
                    }
                }
                return;
            }
        }

        // If we get here, we didn't clear enough
        System.out.println("Didn't clear enough with premature promotion");
    }

    /**
     * This method is used to empty the current survivor space into the other.
     * It returns the number of free slots remaining in the new to space.
     */
    private int compactAndEvacuateSrvSpace(MemoryPool from, MemoryPool to) {
        int count = 0;
        for (int i = 0; i < from.width(); i++) {
            for (int j = 0; j < from.height(); j++) {
                MemoryBlockView mbv = from.getValue(i, j);
                if (mbv.getStatus() == MemoryStatus.ALLOCATED) {
                    MemoryBlock alive = mbv.getBlock();
                    alive.mark();
                    // This cannot fail, as to & from are the same size
                    if (!to.tryAdd(alive)) {
                        System.out.println("Block id " + alive.getBlockId() + " status: " + alive.getStatus() + " failed to copy between survivor spaces");
                    }
                    count++;
                }
            }
        }
        from.reset();

        return from.width() * from.height() - count;
    }

    /**
     * Walk through the current survivor space, and promote everything which has
     * this generation or higher. This does not leave the pool empty, and so
     * does not reset. Returns the total space available in the pool at exit.
     *
     * @param genPromoted
     */
    private int prematurePromote(int genPromoted) {
        MemoryPool from = currentSurvivorSpace();
        for (int i = 0; i < from.width(); i++) {
            INNER:
            for (int j = 0; j < from.height(); j++) {
                MemoryBlockView mbv = from.getValue(i, j);
                if (mbv.getStatus() == MemoryStatus.ALLOCATED) {
                    MemoryBlock alive = mbv.getBlock();
                    
                    if (alive.generation() >= genPromoted) {
                        alive.mark();
                        if (tenured.tryAdd(alive)) {
                            // Remove from Survivor spaces
                            mbv.setBlock(factory.getFreeBlock());
                            continue INNER;
                        } else {
                            tenured.compact();
                            if (tenured.tryAdd(alive)) {
                                // Remove from Survivor spaces
                                mbv.setBlock(factory.getFreeBlock());
                                continue INNER;
                            } else {
                                throw new RuntimeException("OOME at " + i + ", " + j);
                            }
                        }
                    }
                }
            }
        }
        return from.spaceFree();
    }

    private void moveToTenured(List<MemoryBlock> evacuees) {
        for (MemoryBlock mb : evacuees) {
            if (!tenured.tryAdd(mb)) {
                throw new RuntimeException("OOME when trying a bulk move to tenured");
            }
        }
    }

    private boolean selfCheckEden() {
        if (eden.spaceFree() > 0) return false;
        return true;
    }
}
