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

    private static final int[] LAYERS = {4, 100, 100, 100, 100, 5};
    private static final int ACTIONS = 5;
    private static final int REPLAY_CAPACITY = 2500;
    private static final int BATCH_SIZE = 8;
    private static final double GAMMA = 0.97;
    private static final double LEARNING_RATE = 0.0002;
    private static final int MAGIC = 0x5641504B;
    private static final int MODEL_VERSION = 2;

    private final QNetwork online = new QNetwork();
    private final QNetwork target = new QNetwork();
    private final Transition[] replay = new Transition[REPLAY_CAPACITY];
    private final Random random = new Random(73921L);
    private int replaySize;
    private int replayCursor;
    private int updateCount;
    private volatile boolean stopRequested;

    public NeuralAgent(File modelFile) {
        boolean loaded = false;
        if (modelFile != null && modelFile.isFile()) {
            try {
                online.load(modelFile);
                loaded = true;
            } catch (Exception ignored) {
                loaded = false;
            }
        }
        if (!loaded) online.reset(random);
        target.copyFrom(online);
    }

    public synchronized void stop() {
        stopRequested = true;
    }

    public int chooseAction(double[] observation) {
        return greedyAction(observation);
    }

    private int greedyAction(double[] observation) {
        double[] q = online.predict(observation);
        int best = 0;
        for (int i = 1; i < q.length; i++) {
            if (q[i] > q[best]) best = i;
        }
        return best;
    }

    public void train(File modelFile, int episodes, Listener listener) {
        stopRequested = false;
        try {
            QNetwork bestSnapshot = new QNetwork();
            bestSnapshot.copyFrom(online);
            int bestScore = evaluate(12);
            int winsInWindow = 0;
            int checkpointInterval = Math.max(20, episodes / 5);

            for (int episode = 1; episode <= episodes && !stopRequested; episode++) {
                double epsilon = Math.max(0.06,
                    1.0 - 0.94 * (episode - 1.0) / Math.max(1.0, episodes - 1.0));
                Arena arena = new Arena(random);
                double[] state = arena.observation();
                int turns = 0;

                while (!arena.done && turns < 55 && !stopRequested) {
                    turns++;
                    int action = random.nextDouble() < epsilon
                        ? random.nextInt(ACTIONS)
                        : greedyAction(state);

                    Step step = arena.step(action, random);
                    double[] nextState = arena.observation();
                    remember(new Transition(state, action, step.reward, nextState, step.done));
                    state = nextState;

                    if (replaySize >= BATCH_SIZE && turns % 4 == 0) learnBatch();
                }

                if (arena.playerWon) winsInWindow++;

                if (episode % checkpointInterval == 0 || episode == episodes) {
                    int score = evaluate(12);
                    if (score >= bestScore) {
                        bestScore = score;
                        bestSnapshot.copyFrom(online);
                    }
                    if (listener != null) listener.onProgress(
                        episode, episodes, winsInWindow, epsilon
                    );
                    winsInWindow = 0;
                }
            }

            online.copyFrom(bestSnapshot);
            target.copyFrom(bestSnapshot);
            if (modelFile != null) online.save(modelFile);

            if (stopRequested) {
                if (listener != null) listener.onComplete(-1, 0, modelFile);
            } else {
                int finalWins = evaluate(50);
                if (listener != null) listener.onComplete(finalWins, 50, modelFile);
            }
        } catch (Exception error) {
            if (listener != null) listener.onError(error);
        }
    }

    private void remember(Transition item) {
        replay[replayCursor] = item;
        replayCursor = (replayCursor + 1) % REPLAY_CAPACITY;
        if (replaySize < REPLAY_CAPACITY) replaySize++;
    }

    private void learnBatch() {
        for (int n = 0; n < BATCH_SIZE; n++) {
            Transition item = replay[random.nextInt(replaySize)];
            double expected = item.reward;

            if (!item.done) {
                double[] onlineNext = online.predict(item.nextState);
                int bestAction = 0;
                for (int i = 1; i < onlineNext.length; i++) {
                    if (onlineNext[i] > onlineNext[bestAction]) bestAction = i;
                }
                double[] targetNext = target.predict(item.nextState);
                expected += GAMMA * targetNext[bestAction];
            }

            online.trainOne(item.state, item.action, expected, LEARNING_RATE);
            updateCount++;
            if (updateCount % 250 == 0) target.copyFrom(online);
        }
    }

    private int evaluate(int games) {
        int wins = 0;
        for (int game = 0; game < games; game++) {
            Arena arena = new Arena(new Random(90_000L + game));
            Random enemyRandom = new Random(190_000L + game);
            int turn = 0;
            while (!arena.done && turn++ < 55) {
                arena.step(greedyAction(arena.observation()), enemyRandom);
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
        double distance;
        double playerHealth = 1.0;
        double enemyHealth = 1.0;
        int enemyCooldown;
        int turns;
        boolean done;
        boolean playerWon;

        Arena(Random random) {
            distance = 0.48 + random.nextDouble() * 0.48;
            enemyCooldown = 1 + random.nextInt(3);
        }

        double[] observation() {
            double danger = distance < 0.68 && enemyCooldown <= 1 ? 1.0 : 0.0;
            return new double[] {
                clamp(distance),
                clamp(playerHealth),
                clamp(enemyHealth),
                danger
            };
        }

        Step step(int action, Random random) {
            if (done) return new Step(0.0, true);
            turns++;
            double reward = -0.012;

            if (action == 0) {
                distance = Math.max(0.12, distance - 0.12);
                reward += 0.025;
            } else if (action == 1) {
                distance = Math.min(0.98, distance + 0.12);
                reward += 0.008;
            } else if (action == 2) {
                if (distance <= 0.47) {
                    double damage = 0.17 + random.nextDouble() * 0.05;
                    enemyHealth = Math.max(0.0, enemyHealth - damage);
                    reward += 2.0 * damage / 0.17;
                } else {
                    reward -= 0.18;
                }
            } else if (action == 3) {
                reward += 0.012;
            } else if (action == 4) {
                reward += 0.006;
            }

            enemyCooldown--;
            if (enemyCooldown <= 0) {
                if (distance < 0.68) {
                    double damage;
                    if (action == 3) {
                        damage = 0.018;
                        reward += 0.12;
                    } else if (action == 4 && random.nextDouble() < 0.72) {
                        damage = 0.0;
                        reward += 0.18;
                    } else {
                        damage = 0.075;
                    }
                    playerHealth = Math.max(0.0, playerHealth - damage);
                    reward -= damage * 18.0;
                }
                enemyCooldown = 2 + random.nextInt(3);
            }

            if (random.nextDouble() < 0.22) {
                distance = Math.max(0.12, distance - 0.025);
            }

            if (enemyHealth <= 0.0) {
                reward += 12.0;
                done = true;
                playerWon = true;
            } else if (playerHealth <= 0.0) {
                reward -= 12.0;
                done = true;
            } else if (turns >= 55) {
                reward -= 1.5;
                done = true;
                playerWon = enemyHealth < playerHealth;
                if (playerWon) reward += 3.0;
            }
            return new Step(reward, done);
        }

        private double clamp(double value) {
            return Math.max(0.0, Math.min(1.0, value));
        }
    }

    private static final class QNetwork {
        private final double[][][] weights = new double[5][][];
        private final double[][] biases = new double[5][];
        private final double[][][] firstMoment = new double[5][][];
        private final double[][][] secondMoment = new double[5][][];
        private final double[][] biasFirstMoment = new double[5][];
        private final double[][] biasSecondMoment = new double[5][];
        private long adamStep;

        QNetwork() {
            for (int layer = 0; layer < 5; layer++) {
                int inputs = LAYERS[layer];
                int outputs = LAYERS[layer + 1];
                weights[layer] = new double[outputs][inputs];
                biases[layer] = new double[outputs];
                firstMoment[layer] = new double[outputs][inputs];
                secondMoment[layer] = new double[outputs][inputs];
                biasFirstMoment[layer] = new double[outputs];
                biasSecondMoment[layer] = new double[outputs];
            }
        }

        void reset(Random random) {
            for (int layer = 0; layer < 5; layer++) {
                double limit = Math.sqrt(6.0 / (LAYERS[layer] + LAYERS[layer + 1]));
                for (int output = 0; output < LAYERS[layer + 1]; output++) {
                    biases[layer][output] = 0.0;
                    for (int input = 0; input < LAYERS[layer]; input++) {
                        weights[layer][output][input] = (random.nextDouble() * 2.0 - 1.0) * limit;
                    }
                }
            }
            adamStep = 0L;
        }

        double[] predict(double[] input) {
            if (input == null || input.length != LAYERS[0]) {
                throw new IllegalArgumentException("A rede espera quatro entradas.");
            }
            double[] activation = input;
            for (int layer = 0; layer < 5; layer++) {
                double[] next = new double[LAYERS[layer + 1]];
                for (int output = 0; output < next.length; output++) {
                    double sum = biases[layer][output];
                    for (int in = 0; in < activation.length; in++) {
                        sum += weights[layer][output][in] * activation[in];
                    }
                    next[output] = layer == 4 ? sum : Math.tanh(sum);
                }
                activation = next;
            }
            return activation;
        }

        void trainOne(double[] input, int chosenAction, double expected, double learningRate) {
            double[][] activations = new double[6][];
            activations[0] = input.clone();

            for (int layer = 0; layer < 5; layer++) {
                activations[layer + 1] = new double[LAYERS[layer + 1]];
                for (int output = 0; output < LAYERS[layer + 1]; output++) {
                    double sum = biases[layer][output];
                    for (int in = 0; in < LAYERS[layer]; in++) {
                        sum += weights[layer][output][in] * activations[layer][in];
                    }
                    activations[layer + 1][output] = layer == 4 ? sum : Math.tanh(sum);
                }
            }

            double[][] deltas = new double[6][];
            for (int layer = 0; layer < 6; layer++) deltas[layer] = new double[LAYERS[layer]];
            double error = activations[5][chosenAction] - expected;
            deltas[5][chosenAction] = Math.max(-1.0, Math.min(1.0, error));

            for (int layer = 4; layer >= 1; layer--) {
                for (int inputIndex = 0; inputIndex < LAYERS[layer]; inputIndex++) {
                    double propagated = 0.0;
                    for (int output = 0; output < LAYERS[layer + 1]; output++) {
                        propagated += weights[layer][output][inputIndex] * deltas[layer + 1][output];
                    }
                    double activation = activations[layer][inputIndex];
                    deltas[layer][inputIndex] = propagated * (1.0 - activation * activation);
                }
            }

            adamStep++;
            double correction1 = 1.0 - Math.pow(0.9, adamStep);
            double correction2 = 1.0 - Math.pow(0.999, adamStep);

            for (int layer = 0; layer < 5; layer++) {
                for (int output = 0; output < LAYERS[layer + 1]; output++) {
                    updateBias(layer, output, deltas[layer + 1][output],
                        learningRate, correction1, correction2);
                    for (int in = 0; in < LAYERS[layer]; in++) {
                        double gradient = deltas[layer + 1][output] * activations[layer][in];
                        gradient = Math.max(-5.0, Math.min(5.0, gradient));
                        updateWeight(layer, output, in, gradient,
                            learningRate, correction1, correction2);
                    }
                }
            }
        }

        private void updateWeight(int layer, int output, int input, double gradient,
                                  double rate, double correction1, double correction2) {
            firstMoment[layer][output][input] =
                0.9 * firstMoment[layer][output][input] + 0.1 * gradient;
            secondMoment[layer][output][input] =
                0.999 * secondMoment[layer][output][input] + 0.001 * gradient * gradient;
            double mean = firstMoment[layer][output][input] / correction1;
            double variance = secondMoment[layer][output][input] / correction2;
            weights[layer][output][input] -= rate * mean / (Math.sqrt(variance) + 1e-8);
        }

        private void updateBias(int layer, int output, double gradient,
                                double rate, double correction1, double correction2) {
            biasFirstMoment[layer][output] =
                0.9 * biasFirstMoment[layer][output] + 0.1 * gradient;
            biasSecondMoment[layer][output] =
                0.999 * biasSecondMoment[layer][output] + 0.001 * gradient * gradient;
            double mean = biasFirstMoment[layer][output] / correction1;
            double variance = biasSecondMoment[layer][output] / correction2;
            biases[layer][output] -= rate * mean / (Math.sqrt(variance) + 1e-8);
        }

        void copyFrom(QNetwork other) {
            adamStep = other.adamStep;
            for (int layer = 0; layer < 5; layer++) {
                for (int output = 0; output < LAYERS[layer + 1]; output++) {
                    biases[layer][output] = other.biases[layer][output];
                    biasFirstMoment[layer][output] = other.biasFirstMoment[layer][output];
                    biasSecondMoment[layer][output] = other.biasSecondMoment[layer][output];
                    System.arraycopy(other.weights[layer][output], 0,
                        weights[layer][output], 0, LAYERS[layer]);
                    System.arraycopy(other.firstMoment[layer][output], 0,
                        firstMoment[layer][output], 0, LAYERS[layer]);
                    System.arraycopy(other.secondMoment[layer][output], 0,
                        secondMoment[layer][output], 0, LAYERS[layer]);
                }
            }
        }

        void save(File file) throws Exception {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("Não foi possível criar a pasta do modelo.");
            }
            File temp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp)))) {
                output.writeInt(MAGIC);
                output.writeInt(MODEL_VERSION);
                for (int layer = 0; layer < 5; layer++) {
                    output.writeInt(LAYERS[layer]);
                    output.writeInt(LAYERS[layer + 1]);
                    for (int outputIndex = 0; outputIndex < LAYERS[layer + 1]; outputIndex++) {
                        output.writeDouble(biases[layer][outputIndex]);
                        for (int inputIndex = 0; inputIndex < LAYERS[layer]; inputIndex++) {
                            output.writeDouble(weights[layer][outputIndex][inputIndex]);
                        }
                    }
                }
                output.flush();
            }

            if (file.exists() && !file.delete()) {
                throw new IllegalStateException("Não foi possível substituir o modelo anterior.");
            }
            if (!temp.renameTo(file)) {
                throw new IllegalStateException("Não foi possível finalizar o arquivo do modelo.");
            }
        }

        void load(File file) throws Exception {
            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new FileInputStream(file)))) {
                if (input.readInt() != MAGIC || input.readInt() != MODEL_VERSION) {
                    throw new IllegalArgumentException("Arquivo de modelo inválido.");
                }
                for (int layer = 0; layer < 5; layer++) {
                    int inputs = input.readInt();
                    int outputs = input.readInt();
                    if (inputs != LAYERS[layer] || outputs != LAYERS[layer + 1]) {
                        throw new IllegalArgumentException("Arquitetura do modelo incompatível.");
                    }
                    for (int outputIndex = 0; outputIndex < outputs; outputIndex++) {
                        biases[layer][outputIndex] = input.readDouble();
                        for (int inputIndex = 0; inputIndex < inputs; inputIndex++) {
                            weights[layer][outputIndex][inputIndex] = input.readDouble();
                        }
                    }
                }
                if (input.read() != -1) throw new IllegalArgumentException("Dados extras no modelo.");
            }
        }
    }
}
