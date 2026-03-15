package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

public final class EnchantSeedCracker {
    private static final long COST_FLUSH_INTERVAL = 65536L;
    private static final int CLUE_FLUSH_INTERVAL = 4096;

    private static volatile Thread worker;
    private static volatile int workerEpoch = Integer.MIN_VALUE;

    private EnchantSeedCracker() {
    }

    private static final class PreparedObservation {
        final ItemStack stack;
        final int bookshelves;
        final int[] costs;
        final int[] clueIds;
        final int[] clueLevels;

        PreparedObservation(ObservationRecord record) {
            this.stack = record.createStack();
            this.bookshelves = record.getBookshelves();
            this.costs = record.getCosts();
            this.clueIds = record.getClueIds();
            this.clueLevels = record.getClueLevels();
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
            SeedCrackState.beginRun(epoch);

            worker = new Thread(() -> runCrack(registryAccess, epoch), "EnchantSeedCracker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void runCrack(RegistryAccess registryAccess, int expectedEpoch) {
        try {
            Registry<Enchantment> enchantmentRegistry =
                    registryAccess.registryOrThrow(Registries.ENCHANTMENT);

            List<Holder<Enchantment>> holders = StreamSupport
                    .stream(enchantmentRegistry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).spliterator(), false)
                    .map(holder -> (Holder<Enchantment>) holder)
                    .toList();

            List<ObservationRecord> observationRecords = SeedCrackState.getObservationsSnapshot();
            if (observationRecords.isEmpty()) {
                SeedCrackState.finishRun(expectedEpoch);
                return;
            }

            List<PreparedObservation> observations = prepareObservations(observationRecords);
            int localObservationVersion = SeedCrackState.getObservationVersion();

            if (SeedCrackState.getCursor() > 0 || SeedCrackState.getCostMatched() > 0) {
                refilterCostCandidates(observations, expectedEpoch);
            }

            long cursor = SeedCrackState.getCursor();
            List<Integer> costBatch = new ArrayList<>(256);

            while (cursor < SeedCrackState.TOTAL_SEEDS) {
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return;
                }

                int currentVersion = SeedCrackState.getObservationVersion();
                if (currentVersion != localObservationVersion) {
                    if (!costBatch.isEmpty()) {
                        SeedCrackState.appendCostMatches(costBatch, cursor, expectedEpoch);
                        costBatch = new ArrayList<>(256);
                    }

                    observationRecords = SeedCrackState.getObservationsSnapshot();
                    observations = prepareObservations(observationRecords);
                    refilterCostCandidates(observations, expectedEpoch);
                    localObservationVersion = currentVersion;
                }

                int seed = (int) cursor;
                if (matchesAllCosts(seed, observations)) {
                    costBatch.add(seed);
                }
                cursor++;

                if ((cursor & (COST_FLUSH_INTERVAL - 1L)) == 0L) {
                    SeedCrackState.appendCostMatches(costBatch, cursor, expectedEpoch);
                    costBatch = new ArrayList<>(256);
                }
            }

            SeedCrackState.appendCostMatches(costBatch, cursor, expectedEpoch);
            SeedCrackState.finishCostPhase(expectedEpoch);

            List<Integer> costCandidates = SeedCrackState.getCostCandidatesSnapshot(expectedEpoch);
            int processed = 0;
            List<Integer> finalBatch = new ArrayList<>(128);

            while (processed < costCandidates.size()) {
                if (SeedCrackState.getResetEpoch() != expectedEpoch) {
                    return;
                }

                int currentVersion = SeedCrackState.getObservationVersion();
                if (currentVersion != localObservationVersion) {
                    if (!finalBatch.isEmpty()) {
                        SeedCrackState.appendFinalMatches(finalBatch, processed, costCandidates.size(), expectedEpoch);
                        finalBatch = new ArrayList<>(128);
                    }

                    observationRecords = SeedCrackState.getObservationsSnapshot();
                    observations = prepareObservations(observationRecords);
                    refilterCostCandidates(observations, expectedEpoch);
                    SeedCrackState.clearFinalCandidates(expectedEpoch);

                    costCandidates = SeedCrackState.getCostCandidatesSnapshot(expectedEpoch);
                    processed = 0;
                    localObservationVersion = currentVersion;
                    continue;
                }

                int seed = costCandidates.get(processed);
                if (matchesAllClues(seed, observations, enchantmentRegistry, holders)) {
                    finalBatch.add(seed);
                }
                processed++;

                if (processed % CLUE_FLUSH_INTERVAL == 0) {
                    SeedCrackState.appendFinalMatches(finalBatch, processed, costCandidates.size(), expectedEpoch);
                    finalBatch = new ArrayList<>(128);
                }
            }

            SeedCrackState.appendFinalMatches(finalBatch, processed, costCandidates.size(), expectedEpoch);
            SeedCrackState.finishRun(expectedEpoch);
        } finally {
            synchronized (EnchantSeedCracker.class) {
                if (Thread.currentThread() == worker) {
                    worker = null;
                }
            }
        }
    }

    private static List<PreparedObservation> prepareObservations(List<ObservationRecord> records) {
        List<PreparedObservation> list = new ArrayList<>(records.size());
        for (ObservationRecord record : records) {
            list.add(new PreparedObservation(record));
        }
        return list;
    }

    private static void refilterCostCandidates(List<PreparedObservation> observations, int expectedEpoch) {
        List<Integer> current = SeedCrackState.getCostCandidatesSnapshot(expectedEpoch);
        if (current.isEmpty()) {
            SeedCrackState.clearFinalCandidates(expectedEpoch);
            return;
        }

        List<Integer> next = new ArrayList<>(current.size());
        for (int seed : current) {
            if (matchesAllCosts(seed, observations)) {
                next.add(seed);
            }
        }

        SeedCrackState.replaceCostCandidates(next, expectedEpoch);
        SeedCrackState.clearFinalCandidates(expectedEpoch);
    }

    private static boolean matchesAllCosts(int seed, List<PreparedObservation> observations) {
        for (PreparedObservation observation : observations) {
            if (!matchesCosts(observation, seed)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesCosts(PreparedObservation observation, int seed) {
        RandomSource random = RandomSource.create(seed);

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

    private static boolean matchesAllClues(
            int seed,
            List<PreparedObservation> observations,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        for (PreparedObservation observation : observations) {
            if (!matchesClues(seed, observation, enchantmentRegistry, holders)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesClues(
            int seed,
            PreparedObservation observation,
            Registry<Enchantment> enchantmentRegistry,
            List<Holder<Enchantment>> holders
    ) {
        for (int slot = 0; slot < 3; slot++) {
            if (observation.costs[slot] <= 0) {
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

            EnchantmentInstance first = list.get(0);

            if (observation.clueIds[slot] >= 0) {
                var observedHolderOpt = enchantmentRegistry.getHolder(observation.clueIds[slot]);
                if (observedHolderOpt.isEmpty() || !observedHolderOpt.get().equals(first.enchantment)) {
                    return false;
                }
            }

            if (observation.clueLevels[slot] > 0 && first.level != observation.clueLevels[slot]) {
                return false;
            }
        }

        return true;
    }
}