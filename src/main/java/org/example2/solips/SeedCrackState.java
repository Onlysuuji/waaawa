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
    private static volatile boolean clueFilterInitialized = false;

    private static volatile long stopwatchStartNanos = 0L;
    private static volatile long stopwatchEndNanos = 0L;
    private static volatile boolean stopwatchRunning = false;
    private static volatile boolean stopwatchFinished = false;

    private static final List<ObservationRecord> appliedObservations = new ArrayList<>();
    private static final ArrayDeque<ObservationRecord> queuedObservations = new ArrayDeque<>();
    private static final Set<String> observationKeys = new HashSet<>();
    private static final Set<String> processedCostKeys = new HashSet<>();

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
        clueFilterInitialized = false;

        resetStopwatch();

        appliedObservations.clear();
        queuedObservations.clear();
        observationKeys.clear();
        processedCostKeys.clear();
        costCandidates.clear();
        finalCandidates.clear();
    }

    private static void resetStopwatch() {
        stopwatchStartNanos = 0L;
        stopwatchEndNanos = 0L;
        stopwatchRunning = false;
        stopwatchFinished = false;
    }

    private static void ensureStopwatchStarted() {
        if (stopwatchStartNanos == 0L) {
            stopwatchStartNanos = System.nanoTime();
            stopwatchEndNanos = 0L;
            stopwatchRunning = true;
            stopwatchFinished = false;
        } else if (!stopwatchFinished && !stopwatchRunning) {
            stopwatchRunning = true;
            stopwatchEndNanos = 0L;
        }
    }

    private static void stopStopwatchIfNeeded() {
        if (stopwatchStartNanos == 0L || stopwatchFinished) {
            return;
        }
        stopwatchEndNanos = System.nanoTime();
        stopwatchRunning = false;
        stopwatchFinished = true;
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
        ensureStopwatchStarted();
        return true;
    }

    public static synchronized ObservationRecord activateNextObservation(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return null;
        }

        ObservationRecord next = queuedObservations.pollFirst();
        if (next == null) {
            return null;
        }

        appliedObservations.add(next);
        solved = false;
        solvedSeed = 0;
        ensureStopwatchStarted();
        return next;
    }

    public static synchronized boolean hasProcessedCostKey(String costKey) {
        return processedCostKeys.contains(costKey);
    }

    public static synchronized void markCostKeyProcessed(String costKey, int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }
        processedCostKeys.add(costKey);
    }

    public static synchronized List<Integer> getCostCandidatesSnapshot(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return List.of();
        }
        return new ArrayList<>(costCandidates);
    }

    public static synchronized List<Integer> getFinalCandidatesSnapshot(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return List.of();
        }
        return new ArrayList<>(finalCandidates);
    }

    public static synchronized List<Integer> getClueSourceSnapshot(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return List.of();
        }
        if (clueFilterInitialized) {
            return new ArrayList<>(finalCandidates);
        }
        return new ArrayList<>(costCandidates);
    }

    public static synchronized List<ObservationRecord> getAppliedObservationsSnapshot() {
        return new ArrayList<>(appliedObservations);
    }

    public static synchronized boolean isClueFilterInitialized() {
        return clueFilterInitialized;
    }

    public static synchronized void markClueFilterInitialized(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }
        clueFilterInitialized = true;
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
        ensureStopwatchStarted();

        if (cursor < TOTAL_SEEDS) {
            phase = Phase.COST_SCAN;
            phaseChecked = cursor;
            phaseTotal = TOTAL_SEEDS;
        } else {
            phase = Phase.CLUE_FILTER;
            phaseChecked = 0L;
            phaseTotal = clueFilterInitialized ? finalCandidates.size() : costCandidates.size();
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

    public static synchronized void replaceFinalCandidates(List<Integer> newCandidates, int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        finalCandidates.clear();
        finalCandidates.addAll(newCandidates);
        matched = finalCandidates.size();
        solved = false;
        solvedSeed = 0;
    }

    public static synchronized void finishCostPhase(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        cursor = TOTAL_SEEDS;
        phase = Phase.CLUE_FILTER;
        phaseChecked = 0L;
        phaseTotal = clueFilterInitialized ? finalCandidates.size() : costCandidates.size();
        costMatched = costCandidates.size();
        matched = finalCandidates.size();
        solved = false;
        solvedSeed = 0;
    }

    public static synchronized void clearFinalCandidates(int expectedEpoch) {
        if (resetEpoch != expectedEpoch) {
            return;
        }

        int clueSourceSize = clueFilterInitialized ? finalCandidates.size() : costCandidates.size();

        finalCandidates.clear();
        matched = 0;

        if (cursor >= TOTAL_SEEDS) {
            phase = Phase.CLUE_FILTER;
            phaseChecked = 0L;
            phaseTotal = clueSourceSize;
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
        if (solved) {
            stopStopwatchIfNeeded();
        }
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
        if (solved) {
            stopStopwatchIfNeeded();
        }
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

    public static boolean isStopwatchRunning() {
        return stopwatchRunning;
    }

    public static boolean isStopwatchFinished() {
        return stopwatchFinished;
    }

    public static long getElapsedMillis() {
        long start = stopwatchStartNanos;
        if (start == 0L) {
            return 0L;
        }

        long end;
        if (stopwatchRunning) {
            end = System.nanoTime();
        } else if (stopwatchFinished && stopwatchEndNanos != 0L) {
            end = stopwatchEndNanos;
        } else {
            end = start;
        }

        long delta = end - start;
        if (delta < 0L) {
            return 0L;
        }
        return delta / 1_000_000L;
    }

    public static String getElapsedFormatted() {
        long millis = getElapsedMillis();
        long hours = millis / 3_600_000L;
        long minutes = (millis / 60_000L) % 60L;
        long seconds = (millis / 1_000L) % 60L;
        long ms = millis % 1_000L;

        if (hours > 0L) {
            return String.format("%d:%02d:%02d.%03d", hours, minutes, seconds, ms);
        }
        return String.format("%02d:%02d.%03d", minutes, seconds, ms);
    }
}
