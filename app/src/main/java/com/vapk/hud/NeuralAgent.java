package com.vapk.hud;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Random;

public final class NeuralAgent {
    public interface Listener {
        void onProgress(int episode, int total, int recentWins, double epsilon);
        void onComplete(int wins, int games, File modelFile);
        void onError(Exception error);
    }

    private static final int INPUTS = 4;
    private static final int HIDDEN = 100;
    private static final int ACTIONS = 5;
    private static final int[] SIZES = {4, 100, 100, 100, 100, 5};
    private static final int REPLAY_CAPACITY = 5000;
    private static final int BATCH_SIZE = 32;
    private static final double GAMMA = 0.97;
    private static final double LEARNING_RATE = 0.00025;
    private static final int MODEL_VERSION = 1;

    private final QNet online = new QNet();
    private final QNet target = new QNet();
    private final Transition[] replay = new Transition[REPLAY_CAPACITY];
    private final Random random = new Random(42);
    private int replaySize;
    private int replayCursor;
    private int updateCount;
    private volatile boolean stopRequested;

    public NeuralAgent(File modelFile) {
        if (modelFile != null && modelFile.isFile()) {
            try {
                online.load(modelFile);
            } catch (Exception ignored) {
                online.reset(new Random(42));
            }
        } else {
            online.reset(new Random(42));
        }
        target.copyFrom(online);
    }

    public void stop() {
        stopRequested = true;
    }

    public int chooseAction(double[] state) {
        return greedyAction(state);
    }

    private int greedyAction(double[] state) {
        double[] values = online.predict(state);
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) best = i;
        }
        return best;
    }

    public void train(File modelFile, int episodes, Listener listener) {
        stopRequested = false;
        try {
            int winsInWindow = 0;
            for (int episode = 1; episode <= episodes && !stopRequested; episode++) {
                double epsilon = Math.max(0.05, 1.0 - 0.95 * (episode - 1) / Math.max(1.0, episodes - 1.0));
                Arena arena = new Arena(random);
                double[] state = arena.state();
                int turns = 0;

                while (!arena.done && turns++ < 60 && !stopRequested) {
                    int action;
                    if (random.nextDouble() < epsilon) {
                        action = random.nextInt(ACTIONS);
                    } else {
                        action = greedyAction(state);
                    }

                    Step step = arena.step(action, random);
                    double[] nextState = arena.state();
                    remember(new Transition(state, action, step.reward, nextState, step.done));
                    state = nextState;

                    if (replaySize >= BATCH_SIZE) {
                        for (int k = 0; k < 1; k++) learnBatch();
                    }
                }

                if (arena.playerWon) winsInWindow++;
                if (episode % 10 == 0 || episode == episodes) {
                    if (listener != null) listener.onProgress(episode, episodes, winsInWindow, epsilon);
                    winsInWindow = 0;
                }
            }

            if (!stopRequested) {
                int wins = evaluate(100);
                if (modelFile != null) online.save(modelFile);
                if (listener != null) listener.onComplete(wins, 100, modelFile);
            } else {
                if (modelFile != null) online.save(modelFile);
                if (listener != null) listener.onComplete(-1, 0, modelFile);
            }
        } catch (Exception error) {
            if (listener != null) listener.onError(error);
        }
    }

    private void remember(Transition transition) {
        replay[replayCursor] = transition;
        replayCursor = (replayCursor + 1) % REPLAY_CAPACITY;
        if (replaySize < REPLAY_CAPACITY) replaySize++;
    }

    private void learnBatch() {
        for (int n = 0; n < BATCH_SIZE; n++) {
            Transition t = replay[random.nextInt(replaySize)];
            double expected = t.reward;
            if (!t.done) {
                double[] onlineNext = online.predict(t.nextState);
                int bestNextAction = 0;
                for (int i = 1; i < onlineNext.length; i++) {
                    if (onlineNext[i] > onlineNext[bestNextAction]) bestNextAction = i;
                }
                double[] targetNext = target.predict(t.nextState);
                expected += GAMMA * targetNext[bestNextAction];
            }
            online.trainOne(t.state, t.action, expected, LEARNING_RATE);
            updateCount++;
            if (updateCount % 250 == 0) target.copyFrom(online);
        }
    }

    private int evaluate(int games) {
        int wins = 0;
        for (int i = 0; i < games; i++) {
            Arena arena = new Arena(new Random(10_000L + i));
            int turns = 0;
            while (!arena.done && turns++ < 60) {
                arena.step(greedyAction(arena.state()), new Random(20_000L + i * 71L + turns));
            }
            if (arena.playerWon) wins++;
        }
        return wins;
    }

    private static final class Transition {
        final double[] state;
        final int action;
        final double reward;
        final double[] nextState;
        final boolean done;

        Transition(double[] state, int action, double reward, double[] nextState, boolean done) {
            this.state = state.clone();
            this.action = action;
            this.reward = reward;
            this.nextState = nextState.clone();
            this.done = done;
        }
    }

    private static final class Step {
        final double reward;
        final boolean done;

        Step(double reward, boolean done) {
            this.reward = reward;
            this.done = done;
        }
    }

    private static final class Arena {
        double distance = 0.86;
        double playerHealth = 1.0;
        double enemyHealth = 1.0;
        int enemyCooldown;
        int turns;
        boolean done;
        boolean playerWon;

        Arena(Random random) {
            enemyCooldown = 1 + random.nextInt(2);
        }

        double[] state() {
            double danger = distance < 0.62 && enemyCooldown <= 1 ? 1.0 : 0.0;
            return new double[] {distance, playerHealth, enemyHealth, danger};
        }

        Step step(int action, Random random) {
            if (done) return new Step(0, true);
            turns++;
            double reward = -0.01;

            if (action == 0) {
                distance = Math.max(0.12, distance - 0.12);
                reward += 0.08;
            } else if (action == 1) {
                distance = Math.min(0.98, distance + 0.12);
                reward += 0.015;
            } else if (action == 2) {
                if (distance <= 0.46) {
                    enemyHealth = Math.max(0, enemyHealth - 0.17);
                    reward += 2.0;
                } else {
                    reward -= 0.15;
                }
            } else if (action == 3) {
                reward -= 0.015;
            } else if (action == 4) {
                reward -= 0.005;
            }

            enemyCooldown--;
            if (enemyCooldown <= 0 && distance < 0.62) {
                double damage;
                if (action == 3) {
                    damage = 0.008;
                } else if (action == 4 && random.nextDouble() < 0.72) {
                    damage = 0.0;
                    reward += 0.10;
                } else {
                    damage = 0.075;
                }
                playerHealth = Math.max(0, playerHealth - damage);
                reward -= damage * 14.0;
                enemyCooldown = 2 + random.nextInt(2);
            } else if (enemyCooldown < 0) {
                enemyCooldown = 0;
            }

            if (enemyHealth <= 0) {
                reward += 12.0;
                done = true;
                playerWon = true;
            } else if (playerHealth <= 0) {
                reward -= 12.0;
                done = true;
            } else if (turns >= 60) {
                reward -= 1.0;
                done = true;
            }
            return new Step(reward, done);
        }
    }

    private static final class QNet {
        private final double[][][] w = new double[5][][];
        private final double[][] b = new double[5][];
        private final double[][][] mw = new double[5][][];
        private final double[][][] vw = new double[5][][];
        private final double[][] mb = new double[5][];
        private final double[][] vb = new double[5][];
        private long adamStep;

        QNet() {
            for (int l = 0; l < 5; l++) {
                int in = SIZES[l];
                int out = SIZES[l + 1];
                w[l] = new double[out][in];
                b[l] = new double[out];
                mw[l] = new double[out][in];
                vw[l] = new double[out][in];
                mb[l] = new double[out];
                vb[l] = new double[out];
            }
        }

        void reset(Random random) {
            for (int l = 0; l < 5; l++) {
                double scale = Math.sqrt(1.0 / SIZES[l]);
                for (int j = 0; j < SIZES[l + 1]; j++) {
                    b[l][j] = 0;
                    for (int i = 0; i < SIZES[l]; i++) {
                        w[l][j][i] = (random.nextDouble() * 2.0 - 1.0) * scale;
                    }
                }
            }
            adamStep = 0;
        }

        double[] predict(double[] input) {
            double[] activation = input;
            for (int l = 0; l < 5; l++) {
                double[] next = new double[SIZES[l + 1]];
                for (int j = 0; j < next.length; j++) {
                    double sum = b[l][j];
                    for (int i = 0; i < activation.length; i++) sum += w[l][j][i] * activation[i];
                    next[j] = l == 4 ? sum : Math.tanh(sum);
                }
                activation = next;
            }
            return activation;
        }

        void trainOne(double[] input, int action, double targetValue, double learningRate) {
            double[][] a = new double[6][];
            a[0] = input;
            for (int l = 0; l < 5; l++) {
                a[l + 1] = new double[SIZES[l + 1]];
                for (int j = 0; j < a[l + 1].length; j++) {
                    double sum = b[l][j];
                    for (int i = 0; i < a[l].length; i++) sum += w[l][j][i] * a[l][i];
                    a[l + 1][j] = l == 4 ? sum : Math.tanh(sum);
                }
            }

            double[][] delta = new double[6][];
            for (int l = 0; l < 6; l++) delta[l] = new double[SIZES[l]];
            delta[5][action] = Math.max(-1.0, Math.min(1.0, a[5][action] - targetValue));

            for (int layer = 4; layer >= 1; layer--) {
                for (int i = 0; i < SIZES[layer]; i++) {
                    double sum = 0;
                    for (int j = 0; j < SIZES[layer + 1]; j++) {
                        sum += w[layer][j][i] * delta[layer + 1][j];
                    }
                    delta[layer][i] = sum * (1.0 - a[layer][i] * a[layer][i]);
                }
            }

            adamStep++;
            double beta1Correction = 1.0 - Math.pow(0.9, adamStep);
            double beta2Correction = 1.0 - Math.pow(0.999, adamStep);
            for (int l = 0; l < 5; l++) {
                for (int j = 0; j < SIZES[l + 1]; j++) {
                    double gradB = delta[l + 1][j];
                    updateBias(l, j, gradB, learningRate, beta1Correction, beta2Correction);
                    for (int i = 0; i < SIZES[l]; i++) {
                        double grad = Math.max(-5.0, Math.min(5.0, delta[l + 1][j] * a[l][i]));
                        updateWeight(l, j, i, grad, learningRate, beta1Correction, beta2Correction);
                    }
                }
            }
        }

        private void updateWeight(int l, int j, int i, double g, double lr, double c1, double c2) {
            mw[l][j][i] = 0.9 * mw[l][j][i] + 0.1 * g;
            vw[l][j][i] = 0.999 * vw[l][j][i] + 0.001 * g * g;
            double m = mw[l][j][i] / c1;
            double v = vw[l][j][i] / c2;
            w[l][j][i] -= lr * m / (Math.sqrt(v) + 1e-8);
        }

        private void updateBias(int l, int j, double g, double lr, double c1, double c2) {
            mb[l][j] = 0.9 * mb[l][j] + 0.1 * g;
            vb[l][j] = 0.999 * vb[l][j] + 0.001 * g * g;
            double m = mb[l][j] / c1;
            double v = vb[l][j] / c2;
            b[l][j] -= lr * m / (Math.sqrt(v) + 1e-8);
        }

        void copyFrom(QNet other) {
            for (int l = 0; l < 5; l++) {
                for (int j = 0; j < SIZES[l + 1]; j++) {
                    b[l][j] = other.b[l][j];
                    for (int i = 0; i < SIZES[l]; i++) w[l][j][i] = other.w[l][j][i];
                }
            }
        }

        void save(File file) throws Exception {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar a pasta do modelo.");
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
                out.writeInt(MODEL_VERSION);
                for (int l = 0; l < 5; l++) {
                    for (int j = 0; j < SIZES[l + 1]; j++) out.writeDouble(b[l][j]);
                    for (int j = 0; j < SIZES[l + 1]; j++) {
                        for (int i = 0; i < SIZES[l]; i++) out.writeDouble(w[l][j][i]);
                    }
                }
            }
        }

        void load(File file) throws Exception {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                if (in.readInt() != MODEL_VERSION) throw new IllegalArgumentException("Versão do modelo incompatível.");
                for (int l = 0; l < 5; l++) {
                    for (int j = 0; j < SIZES[l + 1]; j++) b[l][j] = in.readDouble();
                    for (int j = 0; j < SIZES[l + 1]; j++) {
                        for (int i = 0; i < SIZES[l]; i++) w[l][j][i] = in.readDouble();
                    }
                }
            }
        }
    }
}
