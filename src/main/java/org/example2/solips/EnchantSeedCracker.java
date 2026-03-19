package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;
import java.util.concurrent.*;

public final class EnchantSeedCracker {
    private static final int CLUE_FLUSH_INTERVAL = 4096;
    private static final int COST_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    private static final long COST_SCAN_CHUNK_SIZE = 1L << 20;
    private static final int COST_REFILTER_CHUNK_SIZE = 1 << 18;
    private static final int COST_REFILTER_PARALLEL_THRESHOLD = 1 << 17;

    private static volatile Thread worker;
    private static volatile int workerEpoch = Integer.MIN_VALUE;

    private static final int[] CLUE_SLOT_ORDER = new int[]{2, 1, 0};

    private static final ResourceLocation DEPTH_STRIDER_ID = ResourceLocation.withDefaultNamespace("depth_strider");
    private static final ResourceLocation UNBREAKING_ID = ResourceLocation.withDefaultNamespace("unbreaking");

    private static volatile Method reflectedGetEnchantmentList;
    private static volatile Field reflectedEnchantmentSeedField;
    private static volatile Field reflectedRandomField;

    private static final ThreadLocal<RandomSource> COST_RANDOM =
            ThreadLocal.withInitial(() -> RandomSource.create(0L));

    private EnchantSeedCracker() {
    }

    private static final class PreparedObservation {
        final ItemStack stack;
        final int bookshelves;
        final int enchantability;
        final int[] costs;
        final int[] clueIds;
        final int[] clueLevels;

        PreparedObservation(ObservationRecord record) {
            this.stack = record.createStack();
            this.bookshelves = record.getBookshelves();
            this.enchantability = this.stack.getEnchantmentValue();
            this.costs = record.getCosts();
            this.clueIds = record.getClueIds();
            this.clueLevels = record.getClueLevels();
        }
    }

    private static final class CostDebugResult {
        final boolean matches;
        final int failedSlot;
        final int[] actualCosts;

        CostDebugResult(boolean matches, int failedSlot, int[] actualCosts) {
            this.matches = matches;
            this.failedSlot = failedSlot;
            this.actualCosts = actualCosts;
        }
    }

    private static final class ClueDebugResult {
        final boolean matches;
        final int failedSlot;
        final int[] actualClueIds;
        final int[] actualClueLevels;

        ClueDebugResult(boolean matches, int failedSlot, int[] actualClueIds, int[] actualClueLevels) {
            this.matches = matches;
            this.failedSlot = failedSlot;
            this.actualClueIds = actualClueIds;
            this.actualClueLevels = actualClueLevels;
        }
    }

    private static final class ScratchMenuHolder {
        private EnchantmentMenu menu;

        EnchantmentMenu get() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                menu = null;
                return null;
            }
            if (menu == null) {
                menu = new EnchantmentMenu(0, mc.player.getInventory());
            }
            return menu;
        }

        void clear() {
            menu = null;
        }
    }

    private static final class ClueFilterResult {
        final int[] nextSource;
        final boolean interruptedByQueuedCost;

        ClueFilterResult(int[] nextSource, boolean interruptedByQueuedCost) {
            this.nextSource = nextSource;
            this.interruptedByQueuedCost = interruptedByQueuedCost;
        }

        static ClueFilterResult completed(int[] nextSource) {
            return new ClueFilterResult(nextSource, false);
        }

        static ClueFilterResult interrupted(int[] nextSource) {
            return new ClueFilterResult(nextSource, true);
        }
    }

    private static final ThreadLocal<ScratchMenuHolder> SCRATCH_MENU =
            ThreadLocal.withInitial(ScratchMenuHolder::new);

    private static final ExecutorService COST_EXECUTOR = Executors.newFixedThreadPool(COST_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "EnchantCostWorker");
        thread.setDaemon(true);
        return thread;
    });

    private static final class IntArrayBuilder {
        private int[] values;
        private int size;

        IntArrayBuilder(int initialCapacity) {
            this.values = new int[Math.max(1, initialCapacity)];
            this.size = 0;
        }

        void add(int value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        void addAll(int[] source, int count) {
            if (count <= 0) {
                return;
            }
            ensureCapacity(size + count);
            System.arraycopy(source, 0, values, size, count);
            size += count;
        }

        int size() {
            return size;
        }

        void clear() {
            size = 0;
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }

        private void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity <= values.length) {
                return;
            }
            int newCapacity = values.length;
            while (newCapacity < requiredCapacity) {
                newCapacity = Math.max(newCapacity << 1, requiredCapacity);
            }
            values = Arrays.copyOf(values, newCapacity);
        }
    }

    private static final class SeedBatch {
        final int[] seeds;
        final int size;

        SeedBatch(int[] seeds, int size) {
            this.seeds = seeds;
            this.size = size;
        }
    }

    private static final class CostChunkResult {
        final int[] seeds;
        final int size;
        final long endCursor;

        CostChunkResult(int[] seeds, int size, long endCursor) {
            this.seeds = seeds;
            this.size = size;
            this.endCursor = endCursor;
        }
    }

    public static void submitObservation(ObservationRecord observation) {
        if (observation == null) {
            return;
        }

        boolean added = SeedCrackState.addObservationIfAbsent(observation);
        if (!added) {
            return;
        }

        ensureWorkerRunning();
    }

    public static void ensureWorkerRunning() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (SeedCrackState.getObservationCount() == 0) {
            return;
        }

        int epoch = SeedCrackState.getResetEpoch();
        RegistryAccess registryAccess = mc.level.registryAccess();

        synchronized (EnchantSeedCracker.class) {
            if (worker != null && worker.isAlive() && workerEpoch == epoch) {
                return;
            }

            workerEpoch = epoch;
            worker = new Thread(() -> runQueuedCrack(registryAccess, epoch), "EnchantSeedCracker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void runQueuedCrack(RegistryAccess registryAccess, int expectedEpoch) {
        boolean restartWorker = false;

        try {
            Registry<Enchantment> enchantmentRegistry =
                    registryAccess.registryOrThrow(Registries.ENCHANTMENT);

            List<Holder<Enchantment>> holders = StreamSupport
                    .stream(enchantmentRegistry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).spliterator(), false)
                    .map(holder -> (Holder<Enchantment>) holder)
                    .toList();

            while (true) {
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return;
                }

                List<ObservationRecord> activated = drainQueuedObservations(expectedEpoch);
                if (activated.isEmpty()) {
                    SeedCrackState.finishAllRuns(expectedEpoch);
                    return;
                }

                SeedCrackState.beginRun(expectedEpoch);

                while (true) {
                    processAllPendingCostConstraints(expectedEpoch);
                    if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                        return;
                    }

                    List<ObservationRecord> moreActivated = drainQueuedObservations(expectedEpoch);
                    if (moreActivated.isEmpty()) {
                        break;
                    }
                    activated.addAll(moreActivated);
                }

                boolean clueCompleted = applyPendingClueConstraints(expectedEpoch, registryAccess, enchantmentRegistry, holders);
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return;
                }
                if (!clueCompleted) {
                    continue;
                }

                SeedCrackState.finishObservationRun(expectedEpoch);
                logObservationSummary(activated.get(activated.size() - 1), registryAccess, enchantmentRegistry, holders);
            }
        } finally {
            SCRATCH_MENU.get().clear();
            restartWorker = SeedCrackState.hasQueuedObservations(expectedEpoch);
            synchronized (EnchantSeedCracker.class) {
                if (Thread.currentThread() == worker) {
                    worker = null;
                }
            }
            if (restartWorker) {
                ensureWorkerRunning();
            }
        }
    }

    private static List<ObservationRecord> drainQueuedObservations(int expectedEpoch) {
        List<ObservationRecord> activated = new ArrayList<>();
        while (true) {
            ObservationRecord next = SeedCrackState.activateNextObservation(expectedEpoch);
            if (next == null) {
                return activated;
            }
            activated.add(next);
        }
    }

    private static void processAllPendingCostConstraints(int expectedEpoch) {
        for (ObservationRecord record : SeedCrackState.getAppliedObservationsSnapshot()) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return;
            }
            if (SeedCrackState.hasProcessedCostKey(record.getCostKey())) {
                continue;
            }
            processCostConstraint(record.getCostKey(), new PreparedObservation(record), expectedEpoch);
        }
    }

    private static void processCostConstraint(String costKey, PreparedObservation observation, int expectedEpoch) {
        if (SeedCrackState.hasProcessedCostKey(costKey)) {
            SeedCrackState.finishCostPhase(expectedEpoch);
            return;
        }

        if (SeedCrackState.getCursor() == 0L && SeedCrackState.getCostMatched() == 0) {
            fullCostScan(observation, expectedEpoch);
        } else {
            refilterCostCandidates(observation, expectedEpoch);
            refilterFinalCandidatesByCost(observation, expectedEpoch);
            SeedCrackState.finishCostPhase(expectedEpoch);
        }

        if (SeedCrackState.getResetEpoch() != expectedEpoch) {
            return;
        }

        SeedCrackState.markCostKeyProcessed(costKey, expectedEpoch);
    }

    private static void fullCostScan(PreparedObservation observation, int expectedEpoch) {
        long nextStart = SeedCrackState.getCursor();

        while (nextStart < SeedCrackState.TOTAL_SEEDS) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return;
            }

            List<Future<CostChunkResult>> futures = new ArrayList<>(COST_THREADS);
            for (int i = 0; i < COST_THREADS && nextStart < SeedCrackState.TOTAL_SEEDS; i++) {
                long start = nextStart;
                long end = Math.min(start + COST_SCAN_CHUNK_SIZE, SeedCrackState.TOTAL_SEEDS);
                nextStart = end;
                futures.add(COST_EXECUTOR.submit(() -> scanCostChunk(observation, start, end, expectedEpoch)));
            }

            for (Future<CostChunkResult> future : futures) {
                CostChunkResult chunkResult = await(future);
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return;
                }
                SeedCrackState.appendCostMatches(
                        chunkResult.seeds,
                        chunkResult.size,
                        chunkResult.endCursor,
                        expectedEpoch
                );
            }
        }

        SeedCrackState.finishCostPhase(expectedEpoch);
    }

    private static void refilterCostCandidates(PreparedObservation observation, int expectedEpoch) {
        int[] current = SeedCrackState.getCostCandidatesArraySnapshot(expectedEpoch);
        if (current.length == 0) {
            SeedCrackState.replaceCostCandidates(new int[0], 0, expectedEpoch);
            return;
        }

        int[] next = filterSeedsByCost(current, observation, expectedEpoch);
        if (SeedCrackState.getResetEpoch() != expectedEpoch) {
            return;
        }

        SeedCrackState.replaceCostCandidates(next, next.length, expectedEpoch);
    }

    private static void refilterFinalCandidatesByCost(PreparedObservation observation, int expectedEpoch) {
        if (!SeedCrackState.isClueFilterInitialized()) {
            return;
        }

        int[] current = SeedCrackState.getFinalCandidatesArraySnapshot(expectedEpoch);
        if (current.length == 0) {
            SeedCrackState.replaceFinalCandidates(new int[0], 0, expectedEpoch);
            return;
        }

        int[] next = filterSeedsByCost(current, observation, expectedEpoch);
        if (SeedCrackState.getResetEpoch() != expectedEpoch) {
            return;
        }

        SeedCrackState.replaceFinalCandidates(next, next.length, expectedEpoch);
    }

    private static CostChunkResult scanCostChunk(PreparedObservation observation, long start, long end, int expectedEpoch) {
        RandomSource random = COST_RANDOM.get();
        IntArrayBuilder matches = new IntArrayBuilder(256);

        for (long cursor = start; cursor < end; cursor++) {
            if ((cursor & 0x3FFFL) == 0L && SeedCrackState.getResetEpoch() != expectedEpoch) {
                break;
            }

            int seed = (int) cursor;
            if (matchesCosts(observation, seed, random)) {
                matches.add(seed);
            }
        }

        return new CostChunkResult(matches.toArray(), matches.size(), end);
    }

    private static int[] filterSeedsByCost(int[] current, PreparedObservation observation, int expectedEpoch) {
        if (current.length == 0) {
            return new int[0];
        }

        if (COST_THREADS <= 1 || current.length < COST_REFILTER_PARALLEL_THRESHOLD) {
            RandomSource random = COST_RANDOM.get();
            IntArrayBuilder next = new IntArrayBuilder(Math.min(current.length, 1024));
            for (int seed : current) {
                if (matchesCosts(observation, seed, random)) {
                    next.add(seed);
                }
            }
            return next.toArray();
        }

        IntArrayBuilder merged = new IntArrayBuilder(Math.min(current.length, 1024));
        int nextIndex = 0;

        while (nextIndex < current.length) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return new int[0];
            }

            List<Future<SeedBatch>> futures = new ArrayList<>(COST_THREADS);
            for (int i = 0; i < COST_THREADS && nextIndex < current.length; i++) {
                int start = nextIndex;
                int end = Math.min(start + COST_REFILTER_CHUNK_SIZE, current.length);
                nextIndex = end;
                futures.add(COST_EXECUTOR.submit(() -> filterCostChunk(current, start, end, observation, expectedEpoch)));
            }

            for (Future<SeedBatch> future : futures) {
                SeedBatch batch = await(future);
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return new int[0];
                }
                merged.addAll(batch.seeds, batch.size);
            }
        }

        return merged.toArray();
    }

    private static SeedBatch filterCostChunk(
            int[] current,
            int start,
            int end,
            PreparedObservation observation,
            int expectedEpoch
    ) {
        RandomSource random = COST_RANDOM.get();
        IntArrayBuilder next = new IntArrayBuilder(Math.max(32, (end - start) >>> 3));

        for (int index = start; index < end; index++) {
            if ((index & 0x3FFF) == 0 && SeedCrackState.getResetEpoch() != expectedEpoch) {
                break;
            }

            int seed = current[index];
            if (matchesCosts(observation, seed, random)) {
                next.add(seed);
            }
        }

        return new SeedBatch(next.toArray(), next.size());
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException
                    ? (RuntimeException) cause
                    : new RuntimeException(cause);
        }
    }

    private static boolean applyPendingClueConstraints(
            int expectedEpoch,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        int[] source;
        List<ObservationRecord> clueTargets;

        if (SeedCrackState.isClueFilterInitialized()) {
            source = SeedCrackState.getFinalCandidatesArraySnapshot(expectedEpoch);
            clueTargets = SeedCrackState.getPendingClueObservationsSnapshot(expectedEpoch);
        } else {
            source = SeedCrackState.getCostCandidatesArraySnapshot(expectedEpoch);
            clueTargets = SeedCrackState.getAppliedObservationsSnapshot();
        }

        System.out.println("[clue-pass] initialized=" + SeedCrackState.isClueFilterInitialized()
                + " sourceSize=" + source.length
                + " pending=" + clueTargets.size()
                + " costMatched=" + SeedCrackState.getCostMatched()
                + " matched=" + SeedCrackState.getMatched());

        for (ObservationRecord record : clueTargets) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return false;
            }
            if (SeedCrackState.hasProcessedClueObservationKey(record.getKey())) {
                continue;
            }

            ClueFilterResult result = clueFilterFromSource(
                    source,
                    new PreparedObservation(record),
                    registryAccess,
                    enchantmentRegistry,
                    holders,
                    expectedEpoch
            );

            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return false;
            }
            if (result.interruptedByQueuedCost) {
                return false;
            }

            source = result.nextSource;
            SeedCrackState.markObservationClueProcessed(record.getKey(), expectedEpoch);
        }

        SeedCrackState.markClueFilterInitialized(expectedEpoch);
        return true;
    }

    private static ClueFilterResult clueFilterFromSource(
            int[] source,
            PreparedObservation observation,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders,
            int expectedEpoch
    ) {
        SeedCrackState.clearFinalCandidates(expectedEpoch);

        int processed = 0;
        IntArrayBuilder next = new IntArrayBuilder(Math.min(source.length, 1024));
        IntArrayBuilder finalBatch = new IntArrayBuilder(128);
        ScratchMenuHolder scratchMenuHolder = SCRATCH_MENU.get();

        while (processed < source.length) {
            if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                return ClueFilterResult.interrupted(source);
            }
            if (shouldInterruptClueFilterForQueuedCost(expectedEpoch)) {
                restoreClueSourceAfterInterruption(source, expectedEpoch);
                return ClueFilterResult.interrupted(source);
            }

            int seed = source[processed];
            if (matchesCluesFast(seed, observation, registryAccess, enchantmentRegistry, holders, scratchMenuHolder)) {
                next.add(seed);
                finalBatch.add(seed);
            }
            processed++;

            if (processed % CLUE_FLUSH_INTERVAL == 0) {
                SeedCrackState.appendFinalMatches(finalBatch.toArray(), finalBatch.size(), processed, source.length, expectedEpoch);
                finalBatch.clear();
            }
        }

        if (shouldInterruptClueFilterForQueuedCost(expectedEpoch)) {
            restoreClueSourceAfterInterruption(source, expectedEpoch);
            return ClueFilterResult.interrupted(source);
        }

        SeedCrackState.appendFinalMatches(finalBatch.toArray(), finalBatch.size(), processed, source.length, expectedEpoch);
        return ClueFilterResult.completed(next.toArray());
    }

    private static boolean shouldInterruptClueFilterForQueuedCost(int expectedEpoch) {
        return SeedCrackState.hasQueuedUnprocessedCostObservation(expectedEpoch);
    }

    private static void restoreClueSourceAfterInterruption(int[] source, int expectedEpoch) {
        if (SeedCrackState.isClueFilterInitialized()) {
            SeedCrackState.replaceFinalCandidates(source, source.length, expectedEpoch);
        } else {
            SeedCrackState.clearFinalCandidates(expectedEpoch);
        }
    }

    private static boolean matchesCosts(PreparedObservation observation, int seed) {
        return matchesCosts(observation, seed, COST_RANDOM.get());
    }

    private static boolean matchesCosts(PreparedObservation observation, int seed, RandomSource random) {
        if (observation.enchantability <= 0) {
            return observation.costs[0] == 0 && observation.costs[1] == 0 && observation.costs[2] == 0;
        }

        random.setSeed(seed);

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(
                    random,
                    slot,
                    observation.bookshelves,
                    observation.stack
            );

            if (cost < slot + 1) {
                cost = 0;
            }

            if (cost != observation.costs[slot]) {
                return false;
            }
        }

        return true;
    }

    private static CostDebugResult evaluateCosts(PreparedObservation observation, int seed) {
        int[] actualCosts = new int[3];

        if (observation.enchantability <= 0) {
            for (int slot = 0; slot < 3; slot++) {
                actualCosts[slot] = 0;
                if (observation.costs[slot] != 0) {
                    return new CostDebugResult(false, slot, actualCosts);
                }
            }
            return new CostDebugResult(true, -1, actualCosts);
        }

        RandomSource random = COST_RANDOM.get();
        random.setSeed(seed);

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(
                    random,
                    slot,
                    observation.bookshelves,
                    observation.stack
            );

            if (cost < slot + 1) {
                cost = 0;
            }

            actualCosts[slot] = cost;
            if (cost != observation.costs[slot]) {
                return new CostDebugResult(false, slot, actualCosts);
            }
        }

        return new CostDebugResult(true, -1, actualCosts);
    }


    private static boolean shouldIgnoreObservedClue(
            PreparedObservation observation,
            int slot,
            Registry<Enchantment> enchantmentRegistry
    ) {
        int observedClueId = observation.clueIds[slot];
        if (observedClueId < 0) {
            return false;
        }

        Enchantment observed = enchantmentRegistry.byId(observedClueId);
        if (observed == null) {
            return false;
        }

        ResourceLocation observedKey = enchantmentRegistry.getKey(observed);
        if (observedKey == null) {
            return false;
        }

        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(observation.stack.getItem());
        if (itemKey == null) {
            return false;
        }

        String itemPath = itemKey.getPath();
        if (itemPath.endsWith("_boots") && DEPTH_STRIDER_ID.equals(observedKey)) {
            return true;
        }

        return itemPath.endsWith("_sword") && UNBREAKING_ID.equals(observedKey);
    }

    private static boolean matchesCluesFast(
            int seed,
            PreparedObservation observation,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders,
            ScratchMenuHolder scratchMenuHolder
    ) {
        EnchantmentMenu menu = scratchMenuHolder.get();
        if (menu == null || !setMenuSeed(menu, seed)) {
            return matchesCluesFallbackFast(seed, observation, enchantmentRegistry, holders);
        }

        for (int slot : CLUE_SLOT_ORDER) {
            if (observation.costs[slot] <= 0) {
                continue;
            }

            if (shouldIgnoreObservedClue(observation, slot, enchantmentRegistry)) {
                continue;
            }

            List<EnchantmentInstance> list =
                    getMenuEnchantmentList(menu, registryAccess, observation.stack, slot, observation.costs[slot]);
            if (list == null || list.isEmpty()) {
                return false;
            }

            EnchantmentInstance displayed = pickDisplayedClue(menu, list);
            if (displayed == null) {
                return false;
            }

            int actualClueId = enchantmentRegistry.getId(displayed.enchantment.value());
            if (observation.clueIds[slot] >= 0 && observation.clueIds[slot] != actualClueId) {
                return false;
            }

            if (observation.clueLevels[slot] > 0 && observation.clueLevels[slot] != displayed.level) {
                return false;
            }
        }

        return true;
    }

    private static boolean matchesCluesFallbackFast(
            int seed,
            PreparedObservation observation,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        for (int slot : CLUE_SLOT_ORDER) {
            if (observation.costs[slot] <= 0) {
                continue;
            }

            if (shouldIgnoreObservedClue(observation, slot, enchantmentRegistry)) {
                continue;
            }

            RandomSource enchantRandom = RandomSource.create((long) seed + slot);
            List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(
                    enchantRandom,
                    observation.stack,
                    observation.costs[slot],
                    holders.stream()
            );

            if (list.isEmpty()) {
                return false;
            }

            EnchantmentInstance displayed = pickDisplayedClue(enchantRandom, list);
            if (displayed == null) {
                return false;
            }

            int actualClueId = enchantmentRegistry.getId(displayed.enchantment.value());
            if (observation.clueIds[slot] >= 0 && observation.clueIds[slot] != actualClueId) {
                return false;
            }

            if (observation.clueLevels[slot] > 0 && observation.clueLevels[slot] != displayed.level) {
                return false;
            }
        }

        return true;
    }

    private static boolean matchesClues(
            int seed,
            PreparedObservation observation,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        return evaluateClues(seed, observation, registryAccess, enchantmentRegistry, holders).matches;
    }

    private static ClueDebugResult evaluateClues(
            int seed,
            PreparedObservation observation,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        int[] actualClueIds = new int[]{-1, -1, -1};
        int[] actualClueLevels = new int[]{0, 0, 0};

        EnchantmentMenu menu = getScratchMenu();
        if (menu == null || !setMenuSeed(menu, seed)) {
            return evaluateCluesFallback(seed, observation, enchantmentRegistry, holders);
        }

        for (int slot : CLUE_SLOT_ORDER) {
            if (observation.costs[slot] <= 0) {
                continue;
            }

            if (shouldIgnoreObservedClue(observation, slot, enchantmentRegistry)) {
                continue;
            }

            List<EnchantmentInstance> list = getMenuEnchantmentList(menu, registryAccess, observation.stack, slot, observation.costs[slot]);
            if (list == null || list.isEmpty()) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }

            EnchantmentInstance displayed = pickDisplayedClue(menu, list);
            if (displayed == null) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }

            actualClueIds[slot] = enchantmentRegistry.getId(displayed.enchantment.value());
            actualClueLevels[slot] = displayed.level;

            if (observation.clueIds[slot] >= 0 && observation.clueIds[slot] != actualClueIds[slot]) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }

            if (observation.clueLevels[slot] > 0 && observation.clueLevels[slot] != actualClueLevels[slot]) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }
        }

        return new ClueDebugResult(true, -1, actualClueIds, actualClueLevels);
    }

    private static ClueDebugResult evaluateCluesFallback(
            int seed,
            PreparedObservation observation,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        int[] actualClueIds = new int[]{-1, -1, -1};
        int[] actualClueLevels = new int[]{0, 0, 0};

        for (int slot = 0; slot < 3; slot++) {
            if (observation.costs[slot] <= 0) {
                continue;
            }

            if (shouldIgnoreObservedClue(observation, slot, enchantmentRegistry)) {
                continue;
            }

            RandomSource enchantRandom = RandomSource.create((long) seed + slot);
            List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(
                    enchantRandom,
                    observation.stack,
                    observation.costs[slot],
                    holders.stream()
            );

            if (list.isEmpty()) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }

            EnchantmentInstance displayed = pickDisplayedClue(enchantRandom, list);
            if (displayed == null) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }

            actualClueIds[slot] = enchantmentRegistry.getId(displayed.enchantment.value());
            actualClueLevels[slot] = displayed.level;

            if (observation.clueIds[slot] >= 0 && observation.clueIds[slot] != actualClueIds[slot]) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }

            if (observation.clueLevels[slot] > 0 && observation.clueLevels[slot] != actualClueLevels[slot]) {
                return new ClueDebugResult(false, slot, actualClueIds, actualClueLevels);
            }
        }

        return new ClueDebugResult(true, -1, actualClueIds, actualClueLevels);
    }

    private static EnchantmentMenu getScratchMenu() {
        return SCRATCH_MENU.get().get();
    }

    private static boolean setMenuSeed(EnchantmentMenu menu, int seed) {
        try {
            Field field = reflectedEnchantmentSeedField;
            if (field == null) {
                field = EnchantmentMenu.class.getDeclaredField("enchantmentSeed");
                field.setAccessible(true);
                reflectedEnchantmentSeedField = field;
            }

            DataSlot dataSlot = (DataSlot) field.get(menu);
            dataSlot.set(seed);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EnchantmentInstance> getMenuEnchantmentList(
            EnchantmentMenu menu,
            RegistryAccess registryAccess,
            ItemStack stack,
            int slot,
            int cost
    ) {
        try {
            Method method = reflectedGetEnchantmentList;
            if (method == null) {
                method = EnchantmentMenu.class.getDeclaredMethod(
                        "getEnchantmentList",
                        RegistryAccess.class,
                        ItemStack.class,
                        int.class,
                        int.class
                );
                method.setAccessible(true);
                reflectedGetEnchantmentList = method;
            }

            return (List<EnchantmentInstance>) method.invoke(menu, registryAccess, stack, slot, cost);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static EnchantmentInstance pickDisplayedClue(EnchantmentMenu menu, List<EnchantmentInstance> list) {
        if (list.isEmpty()) {
            return null;
        }

        try {
            Field field = reflectedRandomField;
            if (field == null) {
                field = EnchantmentMenu.class.getDeclaredField("random");
                field.setAccessible(true);
                reflectedRandomField = field;
            }

            RandomSource menuRandom = (RandomSource) field.get(menu);
            return pickDisplayedClue(menuRandom, list);
        } catch (ReflectiveOperationException e) {
            return list.get(0);
        }
    }

    private static EnchantmentInstance pickDisplayedClue(RandomSource random, List<EnchantmentInstance> list) {
        if (list.isEmpty()) {
            return null;
        }

        return list.get(random.nextInt(list.size()));
    }

    private static void logObservationSummary(
            ObservationRecord record,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("[item-result] item=").append(record.getItem())
                .append(" bookshelves=").append(record.getBookshelves())
                .append(" costs=").append(Arrays.toString(record.getCosts()))
                .append(" clueIds=").append(Arrays.toString(record.getClueIds()))
                .append(" clueLv=").append(Arrays.toString(record.getClueLevels()))
                .append(" checked=").append(SeedCrackState.getChecked())
                .append(" costMatched=").append(SeedCrackState.getCostMatched())
                .append(" matched=").append(SeedCrackState.getMatched())
                .append(" applied=").append(SeedCrackState.getAppliedObservationCount())
                .append(" queued=").append(SeedCrackState.getQueuedObservationCount())
                .append(" solved=").append(SeedCrackState.isSolved());

        if (SeedCrackState.isSolved()) {
            sb.append(" solvedSeed=")
                    .append(Integer.toUnsignedString(SeedCrackState.getSolvedSeed()));
        }

        System.out.println(sb);
        logActualSeedDebug(record, registryAccess, enchantmentRegistry, holders);
    }

    private static void logActualSeedDebug(
            ObservationRecord latestRecord,
            RegistryAccess registryAccess,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        Integer actualSeed = getAuthoritativeSingleplayerSeed();
        if (actualSeed == null) {
            System.out.println("[actual-debug] actualSeed=(unavailable)");
            return;
        }

        PreparedObservation latest = new PreparedObservation(latestRecord);
        CostDebugResult latestCost = evaluateCosts(latest, actualSeed);
        ClueDebugResult latestClue = evaluateClues(actualSeed, latest, registryAccess, enchantmentRegistry, holders);

        List<ObservationRecord> applied = SeedCrackState.getAppliedObservationsSnapshot();
        boolean actualMatchesAllApplied = true;
        int failedIndex = -1;
        String reason = "ok";

        for (int i = 0; i < applied.size(); i++) {
            PreparedObservation observation = new PreparedObservation(applied.get(i));
            CostDebugResult costResult = evaluateCosts(observation, actualSeed);
            if (!costResult.matches) {
                actualMatchesAllApplied = false;
                failedIndex = i + 1;
                reason = "cost@slot=" + costResult.failedSlot;
                break;
            }

            ClueDebugResult clueResult = evaluateClues(actualSeed, observation, registryAccess, enchantmentRegistry, holders);
            if (!clueResult.matches) {
                actualMatchesAllApplied = false;
                failedIndex = i + 1;
                reason = "clue@slot=" + clueResult.failedSlot;
                break;
            }
        }

        System.out.println("[actual-debug] actualSeed=" + Integer.toUnsignedString(actualSeed)
                + " hex=0x" + Integer.toHexString(actualSeed)
                + " latestItem=" + latestRecord.getItem()
                + " latestCostOk=" + latestCost.matches
                + " latestClueOk=" + latestClue.matches
                + " latestOk=" + (latestCost.matches && latestClue.matches)
                + " appliedOk=" + actualMatchesAllApplied
                + " failedIndex=" + failedIndex
                + " reason=" + reason);

        logSlotDiffs(latest, latestCost, latestClue);
    }

    private static void logSlotDiffs(
            PreparedObservation observation,
            CostDebugResult costResult,
            ClueDebugResult clueResult
    ) {
        for (int slot = 0; slot < 3; slot++) {
            System.out.println("[actual-debug] slot=" + slot
                    + " observedCost=" + observation.costs[slot]
                    + " actualCost=" + costResult.actualCosts[slot]
                    + " observed=(" + observation.clueIds[slot] + "," + observation.clueLevels[slot] + ")"
                    + " actual=(" + clueResult.actualClueIds[slot] + "," + clueResult.actualClueLevels[slot] + ")");
        }
    }

    private static Integer getAuthoritativeSingleplayerSeed() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasSingleplayerServer() || mc.player == null) {
            return null;
        }

        var server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }

        var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
        if (serverPlayer == null) {
            return null;
        }

        return serverPlayer.getEnchantmentSeed();
    }
}
