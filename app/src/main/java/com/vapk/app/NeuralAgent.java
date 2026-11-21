package com.vapk.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

public final class NeuralAgent {
    public static final int INPUTS = 8;
    public static final int ACTIONS = 5;
    public static final int HIDDEN_LAYERS = 4;
    public static final int WIDTH = 100;
    private static final int[] SIZES = {INPUTS, WIDTH, WIDTH, WIDTH, WIDTH, ACTIONS};
    private final float[][][] weights = new float[5][][];
    private final float[][] biases = new float[5][];
    private final Random random = new Random();

    public NeuralAgent() {
        for (int layer = 0; layer < weights.length; layer++) {
            int input = SIZES[layer];
            int output = SIZES[layer + 1];
            weights[layer] = new float[input][output];
            biases[layer] = new float[output];
            float limit = (float) Math.sqrt(6.0 / (input + output));
            for (int i = 0; i < input; i++) {
                for (int j = 0; j < output; j++) {
                    weights[layer][i][j] = (random.nextFloat() * 2f - 1f) * limit;
                }
            }
        }
    }

    public synchronized float[] predict(float[] state) {
        return forward(state)[5].clone();
    }

    public synchronized int chooseAction(float[] state) {
        float[] values = predict(state);
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) best = i;
        }
        return best;
    }

    public synchronized void learn(float[] state, int action, float target, float learningRate) {
        float[][] activations = forward(state);
        float[] delta = new float[ACTIONS];
        delta[action] = clamp(activations[5][action] - target, -1f, 1f);

        for (int layer = 4; layer >= 0; layer--) {
            float[] previousDelta = new float[SIZES[layer]];
            if (layer > 0) {
                for (int i = 0; i < SIZES[layer]; i++) {
                    float sum = 0f;
                    for (int j = 0; j < SIZES[layer + 1]; j++) {
                        sum += delta[j] * weights[layer][i][j];
                    }
                    float a = activations[layer][i];
                    previousDelta[i] = sum * (1f - a * a);
                }
            }

            for (int i = 0; i < SIZES[layer]; i++) {
                float input = activations[layer][i];
                for (int j = 0; j < SIZES[layer + 1]; j++) {
                    weights[layer][i][j] -= learningRate * input * delta[j];
                }
            }
            for (int j = 0; j < biases[layer].length; j++) {
                biases[layer][j] -= learningRate * delta[j];
            }
            delta = previousDelta;
        }
    }

    private float[][] forward(float[] state) {
        if (state == null || state.length != INPUTS) {
            throw new IllegalArgumentException("A rede espera 8 entradas.");
        }
        float[][] activations = new float[6][];
        activations[0] = state.clone();
        for (int layer = 0; layer < 5; layer++) {
            float[] output = new float[SIZES[layer + 1]];
            for (int j = 0; j < output.length; j++) {
                float sum = biases[layer][j];
                for (int i = 0; i < SIZES[layer]; i++) {
                    sum += activations[layer][i] * weights[layer][i][j];
                }
                output[j] = layer == 4 ? sum : (float) Math.tanh(sum);
            }
            activations[layer + 1] = output;
        }
        return activations;
    }

    public synchronized NeuralAgent snapshot() {
        NeuralAgent copy = new NeuralAgent();
        for (int layer = 0; layer < weights.length; layer++) {
            for (int i = 0; i < weights[layer].length; i++) {
                System.arraycopy(weights[layer][i], 0, copy.weights[layer][i], 0,
                        weights[layer][i].length);
            }
            System.arraycopy(biases[layer], 0, copy.biases[layer], 0, biases[layer].length);
        }
        return copy;
    }

    public synchronized void save(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Não foi possível criar a pasta do modelo.");
        }
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(0x56414E31);
            for (int layer = 0; layer < weights.length; layer++) {
                for (int i = 0; i < weights[layer].length; i++) {
                    for (int j = 0; j < weights[layer][i].length; j++) out.writeFloat(weights[layer][i][j]);
                }
                for (float bias : biases[layer]) out.writeFloat(bias);
            }
        }
    }

    public synchronized boolean load(File file) {
        if (file == null || !file.isFile()) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != 0x56414E31) return false;
            for (int layer = 0; layer < weights.length; layer++) {
                for (int i = 0; i < weights[layer].length; i++) {
                    for (int j = 0; j < weights[layer][i].length; j++) {
                        weights[layer][i][j] = in.readFloat();
                    }
                }
                for (int j = 0; j < biases[layer].length; j++) biases[layer][j] = in.readFloat();
            }
            return in.read() == -1;
        } catch (IOException error) {
            return false;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
