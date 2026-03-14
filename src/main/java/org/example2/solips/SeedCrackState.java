package org.example2.solips;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SeedCrackState {

    private static volatile boolean running = false;
    private static volatile boolean solved = false;
    private static volatile int solvedSeed = 0;
    private static volatile int checked = 0;
    private static volatile int matched = 0;

    private static final List<Integer> candidates = new ArrayList<>();
    private static final List<ObservationRecord> observations = new ArrayList<>();
    private static final Set<String> observationKeys = new HashSet<>();

    private SeedCrackState() {}

    public static synchronized void resetAll() {
        running = false;
        solved = false;
        solvedSeed = 0;
        checked = 0;
        matched = 0;
        candidates.clear();
        observations.clear();
        observationKeys.clear();
    }

    public static synchronized void beginRun() {
        running = true;
        solved = false;
        solvedSeed = 0;
        checked = 0;
        matched = candidates.size();
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

    public static boolean isRunning() {
        return running;
    }

    public static boolean isSolved() {
        return solved;
    }

    public static int getSolvedSeed() {
        return solvedSeed;
    }

    public static int getChecked() {
        return checked;
    }

    public static int getMatched() {
        return matched;
    }

    public static void setChecked(int value) {
        checked = value;
    }

    public static int getObservationCount() {
        synchronized (SeedCrackState.class) {
            return observations.size();
        }
    }
}