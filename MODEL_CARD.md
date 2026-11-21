# V-APK 2.0 neural agent

Architecture: 4 inputs → 100 → 100 → 100 → 100 → 5 outputs. The four hidden layers use tanh activation; the output layer is linear.

Observation vector:
1. Distance to enemy, normalized to [0, 1].
2. Player health, normalized to [0, 1].
3. Enemy health, normalized to [0, 1].
4. Immediate danger indicator, 0 or 1.

Action indices:
0. Approach.
1. Retreat.
2. Attack.
3. Block.
4. Evade.

The on-device agent starts with newly initialized weights unless a compatible model file already exists in the app's private storage. Training uses epsilon-greedy exploration, a replay buffer, mini-batch updates, Adam optimization, a target network, Double-DQN next-action selection, reward shaping, and best-checkpoint preservation.

The agent learns only in the built-in simulated arena. Evaluation results apply only to that environment and do not establish performance in commercial games or other external tasks. The user starts training from the Android app, and the resulting weights remain in app-private storage.
