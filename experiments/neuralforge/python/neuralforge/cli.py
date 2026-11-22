import argparse
import json
from pathlib import Path

import numpy as np
import torch

from .checkpoint import save_assembled
from .config import NetworkConfig
from .model import GroupedDenseNetwork
from .native import load_native_library
from .response_bank import (
    collect_response_bank,
    save_response_bank,
    save_response_vectors,
)
from .trainer import train_groupwise


def build_parser():
    parser = argparse.ArgumentParser(
        description="Train a dense FP32 network in sequential groups and assemble it."
    )
    parser.add_argument("--data", help="NPZ dataset containing X and y arrays.")
    parser.add_argument("--x-key", default="X")
    parser.add_argument("--y-key", default="y")
    parser.add_argument("--input-size", type=int, default=1024)
    parser.add_argument("--hidden-layers", type=int, default=2048)
    parser.add_argument("--width", type=int, default=1024)
    parser.add_argument("--output-size", type=int, default=1024)
    parser.add_argument("--layers-per-group", type=int, default=32)
    parser.add_argument("--group-cycles", type=int, default=1)
    parser.add_argument("--group-epochs", type=int, default=1)
    parser.add_argument("--joint-epochs", type=int, default=0)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--learning-rate", type=float, default=1e-4)
    parser.add_argument("--checkpoint-dir", default="checkpoints")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--native-lib")
    parser.add_argument("--response-samples", type=int, default=128)
    parser.add_argument("--execute", action="store_true",
                        help="Actually allocate and train the network.")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    config = NetworkConfig(
        input_size=args.input_size,
        hidden_layers=args.hidden_layers,
        width=args.width,
        output_size=args.output_size,
        layers_per_group=args.layers_per_group,
    )
    config.validate()
    print(json.dumps(config.summary(), indent=2))

    if not args.execute:
        print("Configuration only. Add --execute and --data to allocate and train.")
        print("FP32 parameters alone require approximately "
              f"{config.fp32_parameter_bytes / (1024 ** 3):.2f} GiB.")
        return 0

    if not args.data:
        raise SystemExit("--data is required when --execute is supplied.")
    if args.batch_size < 1 or args.group_cycles < 1 or args.group_epochs < 1:
        raise SystemExit("Batch size, group cycles, and group epochs must be positive.")
    if args.response_samples < 0:
        raise SystemExit("--response-samples cannot be negative.")

    dataset = np.load(args.data, allow_pickle=False)
    if args.x_key not in dataset or args.y_key not in dataset:
        raise KeyError(f"Dataset must contain arrays '{args.x_key}' and '{args.y_key}'.")
    x = np.asarray(dataset[args.x_key], dtype=np.float32)
    y = np.asarray(dataset[args.y_key], dtype=np.float32)

    if x.ndim != 2 or x.shape[1] != config.input_size:
        raise ValueError(f"X must have shape [samples, {config.input_size}].")
    if y.ndim != 2 or y.shape != (x.shape[0], config.output_size):
        raise ValueError(f"y must have shape [samples, {config.output_size}].")
    if x.shape[0] == 0 or not np.isfinite(x).all() or not np.isfinite(y).all():
        raise ValueError("Dataset is empty or contains NaN/infinity.")

    device = args.device
    if device == "auto":
        if torch.cuda.is_available():
            free_bytes, _ = torch.cuda.mem_get_info()
            needed_bytes = int(config.fp32_parameter_bytes * 1.25)
            device = "cuda" if free_bytes >= needed_bytes else "cpu"
        else:
            device = "cpu"
    if device.startswith("cuda") and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but is unavailable.")

    tensor_x = torch.from_numpy(np.ascontiguousarray(x))
    tensor_y = torch.from_numpy(np.ascontiguousarray(y))
    model = GroupedDenseNetwork(config)
    output_dir = Path(args.checkpoint_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    history = train_groupwise(
        model=model,
        inputs=tensor_x,
        targets=tensor_y,
        checkpoint_dir=output_dir,
        group_cycles=args.group_cycles,
        group_epochs=args.group_epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        device=device,
        joint_epochs=args.joint_epochs,
    )

    native_library = load_native_library(args.native_lib)
    response_entries, response_vectors = collect_response_bank(
        model, tensor_x, args.batch_size, device, native_library,
        max_samples=args.response_samples
    )
    save_response_bank(response_entries, output_dir / "response_bank.json")
    save_response_vectors(response_vectors, output_dir / "response_samples.npz")
    manifest = save_assembled(model, output_dir, response_entries)

    (output_dir / "training_history.json").write_text(
        json.dumps(history, indent=2), encoding="utf-8"
    )
    print(f"Assembled checkpoint: {output_dir / 'assembled.pt'}")
    print(f"Group manifest: {manifest}")
    print(f"Response metrics: {output_dir / 'response_bank.json'}")
    print(f"Response vectors: {output_dir / 'response_samples.npz'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
