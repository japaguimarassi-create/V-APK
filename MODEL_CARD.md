# V-APK neural seed model

Architecture: 4 inputs → 100 → 100 → 100 → 100 → 5 outputs.

The seed policy was distilled into this architecture from a rule-based expert using 60,000 generated combat states. Each hidden layer uses tanh activation; the output layer is linear. The exported seed uses signed 4-bit per-layer quantization and is decoded by `NeuralAgent.QNet.load`.

Observation vector:
1. Player-to-enemy distance, normalized to [0, 1].
2. Player health, normalized to [0, 1].
3. Enemy health, normalized to [0, 1].
4. Immediate danger flag, 0 or 1.

Action indices:
0. Approach.
1. Retreat.
2. Attack.
3. Block.
4. Strafe.

The quantized seed scored 100 wins in 100 evaluation episodes against the built-in deterministic toy arena. This is a simulator-specific benchmark, not a real-world/gameplay capability claim. On-device training uses replay memory, epsilon-greedy exploration, a target network for Double-DQN target values, reward shaping, and best-checkpoint preservation.
