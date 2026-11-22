# NeuralForge Grouped Dense FP32

Standalone Python + C++ training project stored inside the V-APK repository. It does not modify the Android app or its existing 4x100 agent.

## Requested architecture

- 2,048 hidden layers.
- 1,024 neurons per hidden layer.
- Fully connected FP32 linear transformations.
- 64 sequential groups, 32 layers per group.
- Each hidden layer uses a dense linear transform with a small residual path to make very deep optimization less fragile.
- Every group ends with a trainable LayerNorm boundary so the next group receives a standardized activation representation.
- A configurable output head follows the 2,048 hidden layers.

The hidden matrices contain 2,147,483,648 FP32 weights. Including biases, residual scales, group normalization parameters, and the output head, the default model has approximately 2.151 billion parameters. Parameters alone require about 8.01 GiB in FP32. This is not a mobile-sized training workload.

## How groups are trained and assembled

The project does not train 64 disconnected networks on unrelated random inputs. It uses block-coordinate training against the complete sequential network:

1. The output head is trained while the hidden groups are frozen.
2. Group 0 is unfrozen and trained against the final task loss. The signal is propagated through all later frozen groups.
3. The same process is repeated for groups 1 through 63. Earlier groups provide real activations; later groups transmit the gradient without changing their parameters.
4. Each trained group is saved separately as groups/group_XXXX.pt.
5. The response bank records each group's activation distribution: count, mean, standard deviation, minimum, maximum, and RMS. The C++ library computes the FP32 batch statistics; Python combines them into stable running summaries.
6. The first 128 responses per group are also retained as actual activation vectors in response_samples.npz (configurable with response-samples).
7. Every group boundary includes a learned per-feature scale and offset (LayerNorm). It keeps the interface between groups numerically organized while remaining trainable in that group's stage.
8. All group checkpoints and the head are assembled into assembled.pt, with manifest.json describing the parameter layout.
9. Optional joint fine-tuning can then update all groups together.

This makes the learned parameters compatible because every group is optimized in the context of the same end-to-end path. The response bank is an audit record of the actual activations passed between groups, not a substitute for training data.

## Hybrid supervised + reinforcement training

The default objective combines supervised MSE with a REINFORCE policy term. For each sample, the model samples an action from its output distribution. It receives reward +1 when the sampled action matches the target vector's highest-scoring index and -0.2 otherwise. The combined loss is MSE plus the policy loss weighted by reinforcement-weight (default 0.2).

This is a label-derived contextual-bandit signal. It preserves the requested hybrid training behavior for a labeled dataset, but it is not a substitute for interacting with a real simulator or environment that returns action-dependent rewards. Set reinforcement-weight to zero to disable the policy term.

## Memory and compute limits

Group-wise optimization reduces the amount of gradient state held at once and activation checkpointing recomputes frozen suffix groups during the backward pass. It does **not** remove the need to hold the full 8+ GiB model weights in memory in this implementation. The full model also needs additional memory for framework overhead, data, gradients for the active group, and the runtime.

The default optimizer is plain SGD without momentum, avoiding two extra full-sized Adam state tensors. joint_epochs defaults to zero because joint fine-tuning needs gradients for all groups and substantially more memory.

The 2,048-layer model should not be allocated on a phone with limited RAM. Use a capable workstation or cloud machine. A GitHub Actions test run only checks the small test architecture; it does not train the full model.

## Build the C++ response-statistics library

From this directory:

\`\`\`bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j2
\`\`\`

The output is build/libresponse_stats.so on Linux, build/libresponse_stats.dylib on macOS, or build/response_stats.dll on Windows.

## Install Python dependencies

\`\`\`bash
python -m pip install -r requirements.txt
\`\`\`

Set PYTHONPATH=python when running commands from this directory.

## Inspect the complete architecture without allocating it

\`\`\`bash
PYTHONPATH=python python -m neuralforge.cli
\`\`\`

The default command prints the 2,048-layer/1,024-width configuration and memory estimate only. It does not allocate the network.

## Dataset format

Provide an NPZ file with two float32 arrays:

- X: shape [number_of_samples, 1024]
- y: shape [number_of_samples, 1024]

The targets are task-specific vectors. This implementation uses mean-squared error, so classification labels must first be converted to target vectors of the configured output size.

## Run real training

After compiling C++ and installing dependencies:

\`\`\`bash
PYTHONPATH=python python -m neuralforge.cli \\
  --data dataset.npz \\
  --execute \\
  --device auto \\
  --batch-size 1 \\
  --group-cycles 1 \\
  --group-epochs 1 \\
  --joint-epochs 0 \\
  --checkpoint-dir checkpoints
\`\`\`

The CLI requires the execute flag to allocate the network. Remove that flag to perform a safe configuration-only inspection.

For a small smoke test, use input-size 8, hidden-layers 4, width 8, output-size 2, and layers-per-group 2. The dataset dimensions must match the selected sizes.

## Tests

\`\`\`bash
PYTHONPATH=python pytest -q
\`\`\`

The tests verify group partitioning, normalized inter-group responses, forward dimensions, a real groupwise update, separate group checkpoints, retained response vectors, and C++ response statistics.

## Important distinction

This repository contains the training engine and staged assembly procedure. The network is not pretrained. Meaningful training requires a dataset whose examples represent the task to be learned. No training accuracy or learned capability is claimed until such a dataset is supplied and an actual run is completed.
