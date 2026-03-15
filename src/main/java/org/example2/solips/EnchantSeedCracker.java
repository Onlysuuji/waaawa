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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.StreamSupport;

public final class EnchantSeedCracker {
    private static final long FULL_RANGE_SIZE = 0x1_0000_0000L;
    private static final long CHUNK_SIZE = 1L << 22; // 4,194,304
    private static final long PROGRESS_STEP = 1L << 20; // 1,048,576

    private static Thread worker;
    private static volatile ExecutorService searchPool;
    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    private EnchantSeedCracker() {
    }

    private static final class ActualSeedCheckResult {
        final boolean passesLatest;
        final boolean passesAll;
        final int failedObservationIndex;
        final String mismatchReason;

        ActualSeedCheckResult(
                boolean passesLatest,
                boolean passesAll,
                int failedObservationIndex,
                String mismatchReason
        ) {
            this.passesLatest = passesLatest;
            this.passesAll = passesAll;
            this.failedObservationIndex = failedObservationIndex;
            this.mismatchReason = mismatchReason != null ? mismatchReason : "";
        }
    }

    public static void stopCrack() {
        cancelRequested.set(true);

        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }

        ExecutorService pool = searchPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private static boolean shouldStop() {
        return cancelRequested.get() || Thread.currentThread().isInterrupted();
    }

    public static void startCrack() {
        if (SeedCrackState.isRunning()) {
            return;
        }

        ObservationRecord observation = ObservedEnchantState.snapshot();
        if (observation == null) {
            return;
        }

        boolean added = SeedCrackState.addObservationIfAbsent(observation);
        if (!added) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        RegistryAccess registryAccess = mc.level.registryAccess();
        SeedCrackState.beginRun();

        worker = new Thread(() -> runCrack(registryAccess), "EnchantSeedCracker");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runCrack(RegistryAccess registryAccess) {
        try {
            Registry<Enchantment> enchantmentRegistry = registryAccess.registryOrThrow(Registries.ENCHANTMENT);
            List<ObservationRecord> observations = SeedCrackState.getObservationsSnapshot();

            if (observations.isEmpty()) {
                SeedCrackState.replaceCandidates(List.of());
                return;
            }

            int actualSeed = getAuthoritativeEnchantSeed();
            ActualSeedCheckResult actualCheck = verifyActualSeed(actualSeed, observations, enchantmentRegistry);

            SeedCrackState.setActualSeedVerification(
                    actualSeed,
                    actualSeed != -1,
                    actualCheck.passesLatest,
                    actualCheck.passesAll,
                    actualCheck.failedObservationIndex,
                    actualCheck.mismatchReason
            );

            System.out.println("[seed-debug] actualSeed=" + actualSeed
                    + " hex=0x" + Integer.toHexString(actualSeed)
                    + " actualMatchesLatest=" + actualCheck.passesLatest
                    + " actualMatchesAll=" + actualCheck.passesAll
                    + " failedIndex=" + actualCheck.failedObservationIndex
                    + " reason=" + actualCheck.mismatchReason);

            if (!actualCheck.passesAll && actualCheck.failedObservationIndex >= 0) {
                dumpActualPreview(actualSeed, observations.get(actualCheck.failedObservationIndex), enchantmentRegistry);
            }

            List<Integer> previousCandidates = SeedCrackState.getCandidatesSnapshot();
            List<Integer> nextCandidates;

            if (!previousCandidates.isEmpty()) {
                ObservationRecord latest = observations.get(observations.size() - 1);
                nextCandidates = refilterPreviousCandidates(previousCandidates, latest, enchantmentRegistry);
            } else {
                nextCandidates = fullSearch32(observations, enchantmentRegistry);
            }

            SeedCrackState.replaceCandidates(nextCandidates);
            System.out.println("[run-debug] nextCandidates=" + nextCandidates.size());
            if (!nextCandidates.isEmpty()) {
                int preview = Math.min(nextCandidates.size(), 10);
                System.out.println("[run-debug] firstCandidates=" + nextCandidates.subList(0, preview));
            }
        } finally {
            searchPool = null;
            worker = null;
            SeedCrackState.finishRun();
        }
    }

    private static List<Integer> refilterPreviousCandidates(
            List<Integer> previousCandidates,
            ObservationRecord latest,
            Registry<Enchantment> enchantmentRegistry
    ) {
        List<Integer> nextCandidates = new ArrayList<>();
        long checkedLocal = 0L;
        long nextProgress = PROGRESS_STEP;

        for (int seed : previousCandidates) {
            if (shouldStop()) {
                return List.of();
            }

            checkedLocal++;
            if (matchesObservation(seed, latest, enchantmentRegistry)) {
                nextCandidates.add(seed);
            }

            if (checkedLocal >= nextProgress) {
                SeedCrackState.setChecked(checkedLocal);
                nextProgress += PROGRESS_STEP;
            }
        }

        SeedCrackState.setChecked(checkedLocal);
        return nextCandidates;
    }

    private static List<Integer> fullSearch32(
            List<ObservationRecord> observations,
            Registry<Enchantment> enchantmentRegistry
    ) {
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        searchPool = pool;
        AtomicLong nextBase = new AtomicLong(0L);
        AtomicLong checkedCounter = new AtomicLong(0L);
        List<Integer> matches = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < workerCount; i++) {
            futures.add(pool.submit(() -> {
                List<Integer> localMatches = new ArrayList<>();
                long localProgress = 0L;

                while (!shouldStop()) {
                    long start = nextBase.getAndAdd(CHUNK_SIZE);
                    if (start >= FULL_RANGE_SIZE) {
                        break;
                    }

                    long end = Math.min(FULL_RANGE_SIZE, start + CHUNK_SIZE);

                    for (long u = start; u < end; u++) {
                        if (shouldStop()) {
                            return;
                        }

                        int seed = (int) u;

                        if (!matchesCostsAll(seed, observations)) {
                            continue;
                        }
                        if (!matchesCluesAll(seed, observations, enchantmentRegistry)) {
                            continue;
                        }

                        localMatches.add(seed);
                    }

                    if (!localMatches.isEmpty()) {
                        matches.addAll(localMatches);
                        localMatches.clear();
                    }

                    localProgress += (end - start);
                    long checkedNow = checkedCounter.addAndGet(end - start);
                    if (checkedNow >= PROGRESS_STEP || localProgress >= PROGRESS_STEP) {
                        SeedCrackState.setChecked(checkedNow);
                        localProgress = 0L;
                    }
                }
            }));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                throw new RuntimeException("fullSearch32 failed", e);
            }
        }

        pool.shutdownNow();
        SeedCrackState.setChecked(FULL_RANGE_SIZE);
        return new ArrayList<>(matches);
    }

    private static boolean matchesCostsAll(int seed, List<ObservationRecord> observations) {
        for (ObservationRecord observation : observations) {
            ItemStack stack = observation.createStack();
            int[] actualCosts = computeDisplayedCosts(stack, observation.getBookshelves(), seed);
            if (!Arrays.equals(actualCosts, observation.getCosts())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesCluesAll(
            int seed,
            List<ObservationRecord> observations,
            Registry<Enchantment> enchantmentRegistry
    ) {
        for (ObservationRecord observation : observations) {
            ItemStack stack = observation.createStack();
            if (!matchesClues(
                    stack,
                    seed,
                    observation.getCosts(),
                    observation.getClueIds(),
                    observation.getClueLevels(),
                    enchantmentRegistry
            )) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesObservation(
            int seed,
            ObservationRecord observation,
            Registry<Enchantment> enchantmentRegistry
    ) {
        ItemStack stack = observation.createStack();

        int[] actualCosts = computeDisplayedCosts(stack, observation.getBookshelves(), seed);
        if (!Arrays.equals(actualCosts, observation.getCosts())) {
            return false;
        }

        return matchesClues(
                stack,
                seed,
                observation.getCosts(),
                observation.getClueIds(),
                observation.getClueLevels(),
                enchantmentRegistry
        );
    }

    private static int[] computeDisplayedCosts(ItemStack stack, int bookshelves, int seed) {
        RandomSource random = RandomSource.create(seed);
        int[] costs = new int[3];

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, bookshelves, stack);
            if (cost < slot + 1) {
                cost = 0;
            }
            costs[slot] = cost;
        }

        return costs;
    }

    private static boolean matchesClues(
            ItemStack stack,
            int seed,
            int[] observedCosts,
            int[] observedClueIds,
            int[] observedClueLevels,
            Registry<Enchantment> enchantmentRegistry
    ) {
        for (int slot = 0; slot < 3; slot++) {
            if (observedCosts[slot] <= 0) {
                continue;
            }

            EnchantmentInstance shown = pickShownClue(
                    stack,
                    seed,
                    slot,
                    observedCosts[slot],
                    enchantmentRegistry
            );

            if (shown == null) {
                return false;
            }

            boolean idOk = true;
            if (observedClueIds[slot] >= 0) {
                var observedHolderOpt = enchantmentRegistry.getHolder(observedClueIds[slot]);
                if (observedHolderOpt.isEmpty() || !observedHolderOpt.get().equals(shown.enchantment)) {
                    idOk = false;
                }
            }

            boolean levelOk = observedClueLevels[slot] <= 0 || shown.level == observedClueLevels[slot];

            if (!idOk || !levelOk) {
                return false;
            }
        }

        return true;
    }

    private static EnchantmentInstance pickShownClue(
            ItemStack stack,
            int seed,
            int slot,
            int cost,
            Registry<Enchantment> enchantmentRegistry
    ) {
        List<Holder<Enchantment>> holders = StreamSupport
                .stream(enchantmentRegistry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).spliterator(), false)
                .map(holder -> (Holder<Enchantment>) holder)
                .toList();

        RandomSource enchantRandom = RandomSource.create((long) seed + slot);
        List<EnchantmentInstance> list = new ArrayList<>(EnchantmentHelper.selectEnchantment(
                enchantRandom,
                stack,
                cost,
                holders.stream()
        ));

        if (list.isEmpty()) {
            return null;
        }

        return list.get(enchantRandom.nextInt(list.size()));
    }

    private static ActualSeedCheckResult verifyActualSeed(
            int actualSeed,
            List<ObservationRecord> observations,
            Registry<Enchantment> enchantmentRegistry
    ) {
        if (actualSeed == -1) {
            return new ActualSeedCheckResult(false, false, -1, "unavailable");
        }

        if (observations.isEmpty()) {
            return new ActualSeedCheckResult(true, true, -1, "");
        }

        boolean passesLatest = matchesObservation(
                actualSeed,
                observations.get(observations.size() - 1),
                enchantmentRegistry
        );

        for (int i = 0; i < observations.size(); i++) {
            ObservationRecord obs = observations.get(i);
            boolean ok = matchesObservation(actualSeed, obs, enchantmentRegistry);

            System.out.println("[actual-debug] obs#" + (i + 1)
                    + " item=" + obs.getItem()
                    + " ok=" + ok
                    + " costs=" + Arrays.toString(obs.getCosts())
                    + " clueIds=" + Arrays.toString(obs.getClueIds())
                    + " clueLv=" + Arrays.toString(obs.getClueLevels()));

            if (!ok) {
                return new ActualSeedCheckResult(
                        passesLatest,
                        false,
                        i,
                        "obs#" + (i + 1)
                );
            }
        }

        return new ActualSeedCheckResult(true, true, -1, "");
    }


    private static int getAuthoritativeEnchantSeed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return -1;
        }

        if (mc.hasSingleplayerServer()) {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (serverPlayer != null) {
                    return serverPlayer.getEnchantmentSeed();
                }
            }
        }

        return mc.player.getEnchantmentSeed();
    }

    private static void dumpActualPreview(
            int seed,
            ObservationRecord obs,
            Registry<Enchantment> enchantmentRegistry
    ) {
        ItemStack stack = obs.createStack();
        int[] actualCosts = computeDisplayedCosts(stack, obs.getBookshelves(), seed);

        for (int slot = 0; slot < 3; slot++) {
            int observedCost = obs.getCosts()[slot];
            int observedClueId = obs.getClueIds()[slot];
            int observedClueLv = obs.getClueLevels()[slot];
            int actualCost = actualCosts[slot];

            String observedKey = "null";
            if (observedClueId >= 0) {
                var h = enchantmentRegistry.getHolder(observedClueId);
                if (h.isPresent()) {
                    observedKey = String.valueOf(enchantmentRegistry.getKey(h.get().value()));
                } else {
                    observedKey = "unknown_id_" + observedClueId;
                }
            }

            String actualKey = "none";
            int actualLv = 0;

            if (actualCost > 0) {
                EnchantmentInstance shown = pickShownClue(
                        stack,
                        seed,
                        slot,
                        actualCost,
                        enchantmentRegistry
                );
                if (shown != null) {
                    actualKey = String.valueOf(enchantmentRegistry.getKey(shown.enchantment.value()));
                    actualLv = shown.level;
                }
            }

            System.out.println("[actual-debug] slot=" + slot
                    + " observedCost=" + observedCost
                    + " actualCost=" + actualCost
                    + " observed=(" + observedKey + "," + observedClueLv + ")"
                    + " actual=(" + actualKey + "," + actualLv + ")");
        }
    }
}