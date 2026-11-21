package com.vapk.app;

import java.util.Random;

public final class ArenaTrainer {
    public interface Listener {
        void onProgress(int episode, int total, float averageReward, float epsilon);
    }

    private static final int BOARD = 10;
    private static final int MAX_STEPS = 36;
    private static final int REPLAY_CAPACITY = 2400;
    private static final int BATCH_SIZE = 2;
    private final Random random = new Random();

    public void train(NeuralAgent agent, int episodes, Listener listener) {
        ReplayBuffer replay = new ReplayBuffer(REPLAY_CAPACITY, random);
        NeuralAgent target = agent.snapshot();
        float epsilon = 1f;
        float rewardWindow = 0f;
        int globalStep = 0;

        for (int episode = 1; episode <= episodes; episode++) {
            Game game = new Game(random);
            float[] state = game.state();
            float episodeReward = 0f;

            for (int step = 0; step < MAX_STEPS; step++) {
                int action;
                if (random.nextFloat() < epsilon) {
                    action = random.nextInt(NeuralAgent.ACTIONS);
                } else {
                    action = agent.chooseAction(state);
                }

                Transition transition = game.step(state, action);
                replay.add(transition);
                state = transition.nextState;
                episodeReward += transition.reward;
                globalStep++;

                if (replay.size() >= 48 && globalStep % 2 == 0) {
                    for (int n = 0; n < BATCH_SIZE; n++) {
                        Transition sample = replay.sample();
                        float targetValue = sample.reward;
                        if (!sample.done) {
                            float[] nextValues = target.predict(sample.nextState);
                            float max = nextValues[0];
                            for (int i = 1; i < nextValues.length; i++) max = Math.max(max, nextValues[i]);
                            targetValue += 0.92f * max;
                        }
                        agent.learn(sample.state, sample.action, targetValue, 0.0007f);
                    }
                }

                if (globalStep % 100 == 0) target = agent.snapshot();
                if (transition.done) break;
            }

            epsilon = Math.max(0.06f, epsilon * 0.985f);
            rewardWindow += episodeReward;
            if (listener != null) {
                listener.onProgress(episode, episodes, rewardWindow / Math.min(episode, 10), epsilon);
            }
        }
    }

    private static final class Transition {
        final float[] state;
        final int action;
        final float reward;
        final float[] nextState;
        final boolean done;

        Transition(float[] state, int action, float reward, float[] nextState, boolean done) {
            this.state = state.clone();
            this.action = action;
            this.reward = reward;
            this.nextState = nextState.clone();
            this.done = done;
        }
    }

    private static final class ReplayBuffer {
        private final Transition[] data;
        private final Random random;
        private int size;
        private int cursor;

        ReplayBuffer(int capacity, Random random) {
            data = new Transition[capacity];
            this.random = random;
        }

        void add(Transition value) {
            data[cursor] = value;
            cursor = (cursor + 1) % data.length;
            size = Math.min(size + 1, data.length);
        }

        int size() {
            return size;
        }

        Transition sample() {
            return data[random.nextInt(size)];
        }
    }

    private static final class Game {
        private final Random random;
        private int ax;
        private int ay;
        private int ex;
        private int ey;
        private int gx;
        private int gy;

        Game(Random random) {
            this.random = random;
            reset();
        }

        private void reset() {
            ax = random.nextInt(BOARD);
            ay = random.nextInt(BOARD);
            do {
                ex = random.nextInt(BOARD);
                ey = random.nextInt(BOARD);
            } while (distance(ax, ay, ex, ey) < 4);
            do {
                gx = random.nextInt(BOARD);
                gy = random.nextInt(BOARD);
            } while (distance(ax, ay, gx, gy) < 4 || (gx == ex && gy == ey));
        }

        float[] state() {
            return new float[]{
                    scale(ax), scale(ay),
                    (ex - ax) / 9f, (ey - ay) / 9f,
                    (gx - ax) / 9f, (gy - ay) / 9f,
                    distance(ax, ay, ex, ey) / 18f,
                    distance(ax, ay, gx, gy) / 18f
            };
        }

        Transition step(float[] previousState, int action) {
            int oldGoalDistance = distance(ax, ay, gx, gy);
            int oldEnemyDistance = distance(ax, ay, ex, ey);
            float reward = -0.015f;
            boolean done = false;

            if (action == 0) ay = Math.max(0, ay - 1);
            else if (action == 1) ay = Math.min(BOARD - 1, ay + 1);
            else if (action == 2) ax = Math.max(0, ax - 1);
            else if (action == 3) ax = Math.min(BOARD - 1, ax + 1);
            else if (action == 4) {
                if (distance(ax, ay, ex, ey) <= 1) {
                    do {
                        ex = random.nextInt(BOARD);
                        ey = random.nextInt(BOARD);
                    } while (distance(ax, ay, ex, ey) < 4);
                    reward += 0.8f;
                } else {
                    reward -= 0.12f;
                }
            }

            int newGoalDistance = distance(ax, ay, gx, gy);
            int newEnemyDistance = distance(ax, ay, ex, ey);
            reward += (oldGoalDistance - newGoalDistance) * 0.07f;
            reward += (newEnemyDistance - oldEnemyDistance) * 0.025f;

            if (ax == gx && ay == gy) {
                reward += 5f;
                done = true;
            } else {
                if (ex < ax) ex++;
                else if (ex > ax) ex--;
                if (ey < ay) ey++;
                else if (ey > ay) ey--;

                if (ax == ex && ay == ey) {
                    reward -= 5f;
                    done = true;
                }
            }

            return new Transition(previousState, action, reward, state(), done);
        }

        private static int distance(int x1, int y1, int x2, int y2) {
            return Math.abs(x1 - x2) + Math.abs(y1 - y2);
        }

        private static float scale(int value) {
            return (value / 9f) * 2f - 1f;
        }
    }
}
