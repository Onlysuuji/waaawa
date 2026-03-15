package org.example2.solips;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SeedCrackState {
    public enum Phase {
        IDLE,
        COST_SCAN,
        CLUE_FILTER,
        DONE
    }

    public static final long TOTAL_SEEDS = 0x1_0000_0000L;
    private static final int UNKNOWN_ENCHANT_SEED = Integer.MIN_VALUE;

    private static volatile boolean running = false;
    private static volatile boolean solved = false;
    private static volatile int solvedSeed = 0;

    private static volatile Phase phase = Phase.IDLE;
    private static volatile long phaseChecked = 0L;
    private static volatile long phaseTotal = TOTAL_SEEDS;

    private static volatile long cursor = 0L;
    private static volatile int costMatched = 0;
    private static volatile int matched = 0;

    private static volatile int resetEpoch = 0;
    private static volatile int trackedEnchantSeed = UNKNOWN_ENCHANT_SEED;

    private static final List<ObservationRecord> appliedObservations = new ArrayList<>();
    private static final ArrayDeque<ObservationRecord> queuedObservations = new ArrayDeque<>();
    private static final Set<String> observationKeys = new HashSet<>();

    private static final List<Integer> costCandidates = new ArrayList<>();
    private static final List<Integer> finalCandidates = new ArrayList<>();

    private SeedCrackState() {
    }

    public static synchronized void resetAll() {
        resetEpoch++;
        clearAllState();
    }

    private static void clearAllState() {
        running = false;
        solved = false;
        solvedSeed = 0;

        phase = Phase.IDLE;
        phaseChecked = 0L;
        phaseTotal = TOTAL_SEEDS;

        cursor = 0L;
        costMatched = 0;
        matched = 0;

        trackedEnchantSeed = UNKNOWN_ENCHANT_SEED;

        appliedObservations.clear();
        queuedObservations.clear();
        observationKeys.clear();
        costCandidates.clear();
        finalCandidates.clear();
    }

    public static synchronized boolean updateEnchantSeedAndCheckReset(int currentEnchantSeed) {
        if (currentEnchantSeed == UNKNOWN_ENCHANT_SEED) {
            return false;
        }

        if (trackedEnchantSeed == UNKNOWN_ENCHANT_SEED) {
            trackedEnchantSeed = currentEnchantSeed;
            return false;
        }

        if (trackedEnchantSeed == currentEnchantSeed) {
            return false;
        }

        trackedEnchantSeed = currentEnchantSeed;
        resetEpoch++;
        clearAllState();
        trackedEnchantSeed = currentEnchantSeed;
        return true;
    }

    public static synchronized boolean hasObservationKey(String key) {
        return observationKeys.contains(key);
    }

    public static synchronized boolean addObservationIfAbsent(ObservationRecord observation) {
        if (observation == null) {
            return false;
        }

        String key = observation.getKey();
        if (observationKeys.contains(key)) {
            return false;
        }

        observationKeys.add(key);
        queuedObservations.addLast(observation);
        solved = false;
        solvedSeed = 0;
        return true;
    }

    public static synchronized boolean activateNextObservation(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return false;
        }

        ObservationRecord next = queuedObservations.pollFirst();
        if (next == null) {
            return false;
        }

        appliedObservations.add(next);
        finalCandidates.clear();
        matched = 0;
        solved = false;
        solvedSeed = 0;
        return true;
    }

    public static synchronized List<ObservationRecord> getAppliedObservationsSnapshot() {
        return new ArrayList<>(appliedObservations);
    }

    public static synchronized int getObservationCount() {
        return appliedObservations.size() + queuedObservations.size();
    }

    public static synchronized int getAppliedObservationCount() {
        return appliedObservations.size();
    }

    public static synchronized int getQueuedObservationCount() {
        return queuedObservations.size();
    }

    public static synchronized int getResetEpoch() {
        return resetEpoch;
    }

    public static synchronized long getCursor() {
        return cursor;
    }

    public static synchronized void beginRun(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        running = true;
        solved = false;
        solvedSeed = 0;

        if (cursor < TOTAL_SEEDS) {
            phase = Phase.COST_SCAN;
            phaseChecked = cursor;
            phaseTotal = TOTAL_SEEDS;
        } else {
            phase = Phase.CLUE_FILTER;
            phaseChecked = 0L;
            phaseTotal = costCandidates.size();
        }

        costMatched = costCandidates.size();
        matched = finalCandidates.size();
    }

    public static synchronized void appendCostMatches(List<Integer> newMatches, long newCursor, int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        if (!newMatches.isEmpty()) {
            costCandidates.addAll(newMatches);
        }

        cursor = newCursor;
        phase = Phase.COST_SCAN;
        phaseChecked = newCursor;
        phaseTotal = TOTAL_SEEDS;
        costMatched = costCandidates.size();
        matched = finalCandidates.size();
        solved = false;
        solvedSeed = 0;
    }

    public static synchronized void replaceCostCandidates(List<Integer> newCandidates, int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        costCandidates.clear();
        costCandidates.addAll(newCandidates);
        costMatched = costCandidates.size();
        solved = false;
        solvedSeed = 0;
    }

    public static synchronized List<Integer> getCostCandidatesSnapshot(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return List.of();
        }
        return new ArrayList<>(costCandidates);
    }

    public static synchronized void finishCostPhase(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        cursor = TOTAL_SEEDS;
        phase = Phase.CLUE_FILTER;
        phaseChecked = 0L;
        phaseTotal = costCandidates.size();
        costMatched = costCandidates.size();

        finalCandidates.clear();
        matched = 0;
        solved = false;
        solvedSeed = 0;
    }

    public static synchronized void clearFinalCandidates(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        finalCandidates.clear();
        matched = 0;

        if (cursor >= TOTAL_SEEDS) {
            phase = Phase.CLUE_FILTER;
            phaseChecked = 0L;
            phaseTotal = costCandidates.size();
        }

        solved = false;
        solvedSeed = 0;
    }

    public static synchronized void appendFinalMatches(
            List<Integer> newMatches,
            int processed,
            int total,
            int expectedEpoch
    ) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        if (!newMatches.isEmpty()) {
            finalCandidates.addAll(newMatches);
        }

        phase = Phase.CLUE_FILTER;
        phaseChecked = processed;
        phaseTotal = total;
        matched = finalCandidates.size();
        solved = false;
        solvedSeed = 0;
    }

    public static synchronized void finishObservationRun(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        phase = Phase.DONE;
        phaseChecked = phaseTotal;
        costMatched = costCandidates.size();
        matched = finalCandidates.size();
        solved = finalCandidates.size() == 1;
        solvedSeed = solved ? finalCandidates.get(0) : 0;
    }

    public static synchronized void finishAllRuns(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        running = false;
        phase = appliedObservations.isEmpty() ? Phase.IDLE : Phase.DONE;
        phaseChecked = phase == Phase.IDLE ? 0L : phaseTotal;
        costMatched = costCandidates.size();
        matched = finalCandidates.size();
        solved = finalCandidates.size() == 1;
        solvedSeed = solved ? finalCandidates.get(0) : 0;
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isSolved() {
        return solved;
    }

    public static int getSolvedSeed() {
        return solvedSeed;
    }

    public static Phase getPhase() {
        return phase;
    }

    public static long getPhaseChecked() {
        return phaseChecked;
    }

    public static long getPhaseTotal() {
        return phaseTotal;
    }

    public static long getChecked() {
        return phaseChecked;
    }

    public static int getCostMatched() {
        return costMatched;
    }

    public static int getMatched() {
        return matched;
    }
}
