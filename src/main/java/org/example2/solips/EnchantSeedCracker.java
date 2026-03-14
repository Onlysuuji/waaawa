package org.example2.solips;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.tags.EnchantmentTags;
import java.util.stream.StreamSupport;

import java.util.ArrayList;
import java.util.List;

public final class EnchantSeedCracker {

    private static Thread worker;

    private EnchantSeedCracker() {}

    public static void startCrack() {
        if (SeedCrackState.isRunning()) return;

        ObservationRecord observation = ObservedEnchantState.snapshot();
        if (observation == null) return;

        boolean added = SeedCrackState.addObservationIfAbsent(observation);
        if (!added) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        RegistryAccess registryAccess = mc.level.registryAccess();

        SeedCrackState.beginRun();


        worker = new Thread(() -> runCrack(registryAccess), "EnchantSeedCracker");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runCrack(RegistryAccess registryAccess) {



        debugMismatchCount = 0;
        try {
            Registry<Enchantment> enchantmentRegistry =
                    registryAccess.registryOrThrow(Registries.ENCHANTMENT);

            List<ObservationRecord> observations = SeedCrackState.getObservationsSnapshot();

            Minecraft mc = Minecraft.getInstance();

            int clientSeed = mc.player != null ? mc.player.getEnchantmentSeed() : -1;
            int actualSeed = getAuthoritativeEnchantSeed();

            System.out.println("[actual-debug] clientSeed=" + clientSeed
                    + " serverSeed=" + actualSeed
                    + " clientHex=0x" + Integer.toHexString(clientSeed)
                    + " serverHex=0x" + Integer.toHexString(actualSeed));

            debugActualSeed(actualSeed, observations, enchantmentRegistry);

            if (observations.isEmpty()) {
                return;
            }

            ObservationRecord latest = observations.get(observations.size() - 1);

            boolean actualMatchesLatest = matchesObservation(actualSeed, latest, enchantmentRegistry);

            boolean actualMatchesAll = true;
            for (ObservationRecord observation : observations) {
                if (!matchesObservation(actualSeed, observation, enchantmentRegistry)) {
                    actualMatchesAll = false;
                    System.out.println("[seed-debug] actual seed failed on item=" + observation.getItem()
                            + " costs=" + java.util.Arrays.toString(observation.getCosts())
                            + " clueIds=" + java.util.Arrays.toString(observation.getClueIds())
                            + " clueLv=" + java.util.Arrays.toString(observation.getClueLevels()));
                    break;
                }
            }

            System.out.println("[seed-debug] actualSeed=" + actualSeed
                    + " hex=0x" + Integer.toHexString(actualSeed)
                    + " actualMatchesLatest=" + actualMatchesLatest
                    + " actualMatchesAll=" + actualMatchesAll);

            List<Integer> previousCandidates = SeedCrackState.getCandidatesSnapshot();
            List<Integer> nextCandidates = new ArrayList<>();

            if (previousCandidates.isEmpty() && observations.size() == 1) {
                int maxSeed = 0x00FFFFFF;
                for (int seed = 0; seed <= maxSeed; seed++) {
                    if ((seed & 0x3FFF) == 0) {
                        SeedCrackState.setChecked(seed);
                    }
                    if (matchesObservation(seed, latest, enchantmentRegistry)) {
                        nextCandidates.add(seed);
                    }
                }
                SeedCrackState.setChecked(maxSeed + 1);

            } else if (!previousCandidates.isEmpty()) {
                int index = 0;
                for (int seed : previousCandidates) {
                    index++;
                    if ((index & 0x3FF) == 0) {
                        SeedCrackState.setChecked(index);
                    }
                    if (matchesObservation(seed, latest, enchantmentRegistry)) {
                        nextCandidates.add(seed);
                    }
                }
                SeedCrackState.setChecked(previousCandidates.size());

            } else {
                int maxSeed = 0x00FFFFFF;
                for (int seed = 0; seed <= maxSeed; seed++) {
                    if ((seed & 0x3FFF) == 0) {
                        SeedCrackState.setChecked(seed);
                    }

                    boolean ok = true;
                    for (ObservationRecord observation : observations) {
                        if (!matchesObservation(seed, observation, enchantmentRegistry)) {
                            ok = false;
                            break;
                        }
                    }

                    if (ok) {
                        nextCandidates.add(seed);
                    }
                }
                SeedCrackState.setChecked(maxSeed + 1);
            }

            System.out.println("[run-debug] nextCandidates=" + nextCandidates.size());
            if (!nextCandidates.isEmpty()) {
                int limit = Math.min(10, nextCandidates.size());
                System.out.println("[run-debug] firstCandidates=" + nextCandidates.subList(0, limit));
            }

            boolean containsExact = nextCandidates.contains(actualSeed);
            boolean containsLow24 = nextCandidates.contains(actualSeed & 0x00FFFFFF);

            System.out.println("[seed-debug] nextCandidates=" + nextCandidates.size()
                    + " containsExact=" + containsExact
                    + " containsLow24=" + containsLow24);

            SeedCrackState.replaceCandidates(nextCandidates);
        } finally {
            SeedCrackState.finishRun();
        }
    }

    private static boolean matchesObservation(
            int seed,
            ObservationRecord observation,
            Registry<Enchantment> enchantmentRegistry
    ) {
        ItemStack stack = observation.createStack();

        boolean costOk = matchesCosts(
                stack,
                observation.getBookshelves(),
                seed,
                observation.getCosts()
        );

        if (!costOk) {
            return false;
        }

        if (seed < 2000) {
            System.out.println("[obs-debug] cost matched seed=" + seed + " item=" + stack.getItem());
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

    private static boolean matchesCosts(
            ItemStack stack,
            int bookshelves,
            int seed,
            int[] observedCosts
    ) {
        RandomSource random = RandomSource.create(seed);

        for (int slot = 0; slot < 3; slot++) {
            int cost = EnchantmentHelper.getEnchantmentCost(random, slot, bookshelves, stack);
            if (cost < slot + 1) {
                cost = 0;
            }
            if (cost != observedCosts[slot]) {
                return false;
            }
        }
        return true;
    }

    private static int debugMismatchCount = 0;
    private static final int DEBUG_MISMATCH_LIMIT = 1;

    private static boolean matchesClues(
            ItemStack stack,
            int seed,
            int[] observedCosts,
            int[] observedClueIds,
            int[] observedClueLevels,
            Registry<Enchantment> enchantmentRegistry
    ) {
        List<Holder<Enchantment>> holders = StreamSupport
                .stream(
                        enchantmentRegistry.getTagOrEmpty(EnchantmentTags.IN_ENCHANTING_TABLE).spliterator(),
                        false
                )
                .map(h -> (Holder<Enchantment>) h)
                .toList();

        if (debugMismatchCount < DEBUG_MISMATCH_LIMIT) {
            System.out.println("[clue-debug] entered matchesClues item=" + stack.getItem()
                    + " seed=" + seed
                    + " holderCount=" + holders.size());
        }

        for (int slot = 0; slot < 3; slot++) {
            if (observedCosts[slot] <= 0) {
                continue;
            }

            RandomSource enchantRandom = RandomSource.create((long) seed + slot);
            List<EnchantmentInstance> list = EnchantmentHelper.selectEnchantment(
                    enchantRandom,
                    stack,
                    observedCosts[slot],
                    holders.stream()
            );

            if (list.isEmpty()) {
                if (debugMismatchCount < DEBUG_MISMATCH_LIMIT) {
                    System.out.println("[clue-debug] seed=" + seed
                            + " slot=" + slot
                            + " item=" + stack.getItem()
                            + " cost=" + observedCosts[slot]
                            + " -> predicted list empty");
                    debugMismatchCount++;
                }
                return false;
            }

            EnchantmentInstance first = list.get(0);
            String actualKey = String.valueOf(enchantmentRegistry.getKey(first.enchantment.value()));
            int actualLevel = first.level;

            String observedKey = "null";
            if (observedClueIds[slot] >= 0) {
                var observedHolderOpt = enchantmentRegistry.getHolder(observedClueIds[slot]);
                if (observedHolderOpt.isPresent()) {
                    observedKey = String.valueOf(enchantmentRegistry.getKey(observedHolderOpt.get().value()));
                } else {
                    observedKey = "unknown_id_" + observedClueIds[slot];
                }
            }

            boolean idOk = true;
            if (observedClueIds[slot] >= 0) {
                var observedHolderOpt = enchantmentRegistry.getHolder(observedClueIds[slot]);
                if (observedHolderOpt.isEmpty() || !observedHolderOpt.get().equals(first.enchantment)) {
                    idOk = false;
                }
            }

            boolean levelOk = observedClueLevels[slot] <= 0 || actualLevel == observedClueLevels[slot];

            if (!idOk || !levelOk) {
                if (debugMismatchCount < DEBUG_MISMATCH_LIMIT) {
                    System.out.println("[clue-debug] seed=" + seed
                            + " slot=" + slot
                            + " item=" + stack.getItem()
                            + " cost=" + observedCosts[slot]
                            + " observed=(" + observedKey + "," + observedClueLevels[slot] + ")"
                            + " actual=(" + actualKey + "," + actualLevel + ")");
                    debugMismatchCount++;
                }
                return false;
            }
        }

        if (debugMismatchCount < DEBUG_MISMATCH_LIMIT) {
            System.out.println("[clue-debug] matched seed=" + seed + " item=" + stack.getItem());
        }

        return true;
    }
    private static void debugActualSeed(
            int actualSeed,
            List<ObservationRecord> observations,
            Registry<Enchantment> enchantmentRegistry
    ) {
        System.out.println("[actual-debug] actualSeed=" + actualSeed
                + " hex=0x" + Integer.toHexString(actualSeed));

        for (int i = 0; i < observations.size(); i++) {
            ObservationRecord obs = observations.get(i);
            ItemStack stack = obs.createStack();

            boolean costOk = matchesCosts(
                    stack,
                    obs.getBookshelves(),
                    actualSeed,
                    obs.getCosts()
            );

            boolean clueOk = matchesClues(
                    stack,
                    actualSeed,
                    obs.getCosts(),
                    obs.getClueIds(),
                    obs.getClueLevels(),
                    enchantmentRegistry
            );

            System.out.println("[actual-debug] obs#" + (i + 1)
                    + " item=" + obs.getItem()
                    + " costOk=" + costOk
                    + " clueOk=" + clueOk
                    + " costs=" + java.util.Arrays.toString(obs.getCosts())
                    + " clueIds=" + java.util.Arrays.toString(obs.getClueIds())
                    + " clueLv=" + java.util.Arrays.toString(obs.getClueLevels()));

            if (!costOk || !clueOk) {
                dumpActualPreview(actualSeed, obs, enchantmentRegistry);
                break;
            }
        }
    }

    private static int getAuthoritativeEnchantSeed() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) {
            return -1;
        }

        // singleplayer では server 側の Player を優先
        if (mc.hasSingleplayerServer()) {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var serverPlayer = server.getPlayerList().getPlayer(mc.player.getUUID());
                if (serverPlayer != null) {
                    return serverPlayer.getEnchantmentSeed();
                }
            }
        }

        // fallback
        return mc.player.getEnchantmentSeed();
    }

    private static void dumpActualPreview(
            int seed,
            ObservationRecord obs,
            Registry<Enchantment> enchantmentRegistry
    ) {
        ItemStack stack = obs.createStack();

        for (int slot = 0; slot < 3; slot++) {
            int observedCost = obs.getCosts()[slot];
            int observedClueId = obs.getClueIds()[slot];
            int observedClueLv = obs.getClueLevels()[slot];

            int actualCost = EnchantmentHelper.getEnchantmentCost(
                    RandomSource.create(seed),
                    slot,
                    obs.getBookshelves(),
                    stack
            );
            if (actualCost < slot + 1) {
                actualCost = 0;
            }

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

            if (observedCost > 0) {
                List<EnchantmentInstance> list = buildPreviewEnchantments(
                        stack,
                        seed,
                        slot,
                        observedCost,
                        enchantmentRegistry
                );
                if (!list.isEmpty()) {
                    EnchantmentInstance first = list.get(0);
                    actualKey = String.valueOf(enchantmentRegistry.getKey(first.enchantment.value()));
                    actualLv = first.level;
                }
            }

            System.out.println("[actual-debug] slot=" + slot
                    + " observedCost=" + observedCost
                    + " actualCost=" + actualCost
                    + " observed=(" + observedKey + "," + observedClueLv + ")"
                    + " actual=(" + actualKey + "," + actualLv + ")");
        }
    }
    private static List<EnchantmentInstance> buildPreviewEnchantments(
            ItemStack stack,
            int seed,
            int slot,
            int cost,
            Registry<Enchantment> enchantmentRegistry
    ) {
        List<Holder<Enchantment>> holders = java.util.stream.StreamSupport
                .stream(
                        enchantmentRegistry
                                .getTagOrEmpty(net.minecraft.tags.EnchantmentTags.IN_ENCHANTING_TABLE)
                                .spliterator(),
                        false
                )
                .map(h -> (Holder<Enchantment>) h)
                .toList();

        RandomSource enchantRandom = RandomSource.create((long) seed + slot);

        List<EnchantmentInstance> list = new ArrayList<>(EnchantmentHelper.selectEnchantment(
                enchantRandom,
                stack,
                cost,
                holders.stream()
        ));

        // 本だけ特別処理したいなら残す
        if (stack.is(net.minecraft.world.item.Items.BOOK) && list.size() > 1) {
            list.remove(enchantRandom.nextInt(list.size()));
        }

        return list;
    }
}