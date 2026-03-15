package org.example2.solips;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SeedCrackState {
    private static volatile boolean running = false;
    private static volatile boolean solved = false;
    private static volatile int solvedSeed = 0;
    private static volatile long checked = 0L;
    private static volatile int matched = 0;

    private static volatile boolean actualSeedKnown = false;
    private static volatile int actualSeed = 0;
    private static volatile boolean actualSeedPassesLatest = false;
    private static volatile boolean actualSeedPassesAll = false;
    private static volatile int actualFailedObservationIndex = -1;
    private static volatile String actualMismatchReason = "";

    private static final List<Integer> candidates = new ArrayList<>();
    private static final List<ObservationRecord> observations = new ArrayList<>();
    private static final Set<String> observationKeys = new HashSet<>();

    private SeedCrackState() {
    }

    public static synchronized void resetAll() {
        running = false;
        solved = false;
        solvedSeed = 0;
        checked = 0L;
        matched = 0;
        clearActualSeedVerificationLocked();
        candidates.clear();
        observations.clear();
        observationKeys.clear();
    }

    public static synchronized void beginRun() {
        running = true;
        solved = false;
        solvedSeed = 0;
        checked = 0L;
        matched = 0;
        clearActualSeedVerificationLocked();
    }

    public static synchronized void finishRun() {
        running = false;
        matched = candidates.size();
        if (candidates.size() == 1) {
            solved = true;
            solvedSeed = candidates.get(0);
        }
    }

    public static synchronized boolean hasObservationKey(String key) {
        return observationKeys.contains(key);
    }

    public static synchronized boolean addObservationIfAbsent(ObservationRecord observation) {
        if (observation == null) {
            return false;
        }
        if (observationKeys.contains(observation.getKey())) {
            return false;
        }
        observationKeys.add(observation.getKey());
        observations.add(observation);
        return true;
    }

    public static synchronized ObservationRecord getLatestObservation() {
        if (observations.isEmpty()) {
            return null;
        }
        return observations.get(observations.size() - 1);
    }

    public static synchronized List<ObservationRecord> getObservationsSnapshot() {
        return new ArrayList<>(observations);
    }

    public static synchronized List<Integer> getCandidatesSnapshot() {
        return new ArrayList<>(candidates);
    }

    public static synchronized void replaceCandidates(List<Integer> newCandidates) {
        candidates.clear();
        candidates.addAll(newCandidates);
        matched = candidates.size();
        solved = candidates.size() == 1;
        solvedSeed = solved ? candidates.get(0) : 0;
    }

    public static synchronized void setActualSeedVerification(
            int seed,
            boolean known,
            boolean passesLatest,
            boolean passesAll,
            int failedObservationIndex,
            String mismatchReason
    ) {
        actualSeed = seed;
        actualSeedKnown = known;
        actualSeedPassesLatest = passesLatest;
        actualSeedPassesAll = passesAll;
        actualFailedObservationIndex = failedObservationIndex;
        actualMismatchReason = mismatchReason != null ? mismatchReason : "";
    }

    private static void clearActualSeedVerificationLocked() {
        actualSeedKnown = false;
        actualSeed = 0;
        actualSeedPassesLatest = false;
        actualSeedPassesAll = false;
        actualFailedObservationIndex = -1;
        actualMismatchReason = "";
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

    public static long getChecked() {
        return checked;
    }

    public static void setChecked(long value) {
        checked = value;
    }

    public static int getMatched() {
        return matched;
    }

    public static int getObservationCount() {
        synchronized (SeedCrackState.class) {
            return observations.size();
        }
    }

    public static boolean isActualSeedKnown() {
        return actualSeedKnown;
    }

    public static int getActualSeed() {
        return actualSeed;
    }

    public static boolean getActualSeedPassesLatest() {
        return actualSeedPassesLatest;
    }

    public static boolean getActualSeedPassesAll() {
        return actualSeedPassesAll;
    }

    public static int getActualFailedObservationIndex() {
        return actualFailedObservationIndex;
    }

    public static String getActualMismatchReason() {
        return actualMismatchReason;
    }
}