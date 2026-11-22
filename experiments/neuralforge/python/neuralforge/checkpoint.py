import json
import os
from pathlib import Path

import torch

from .config import NetworkConfig
from .model import GroupedDenseNetwork


def _cpu_state_dict(module):
    return {name: tensor.detach().cpu() for name, tensor in module.state_dict().items()}


def _atomic_torch_save(payload, destination):
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    torch.save(payload, temporary)
    os.replace(temporary, destination)


def save_group(model, group_index, directory):
    directory = Path(directory) / "groups"
    payload = {
        "group_index": int(group_index),
        "config": model.config.to_dict(),
        "state_dict": _cpu_state_dict(model.groups[group_index]),
    }
    _atomic_torch_save(payload, directory / f"group_{group_index:04d}.pt")


def save_head(model, directory):
    payload = {
        "config": model.config.to_dict(),
        "state_dict": _cpu_state_dict(model.head),
    }
    _atomic_torch_save(payload, Path(directory) / "head.pt")


def save_assembled(model, directory, response_bank=None):
    payload = {
        "format": "neuralforge-grouped-fp32-v1",
        "config": model.config.to_dict(),
        "architecture": model.config.summary(),
        "state_dict": _cpu_state_dict(model),
        "response_bank": response_bank or [],
    }
    _atomic_torch_save(payload, Path(directory) / "assembled.pt")
    manifest = {
        "format": payload["format"],
        "config": payload["config"],
        "architecture": payload["architecture"],
        "groups": [
            {
                "index": index,
                "layer_start": sum(model.config.group_sizes[:index]),
                "layer_count": count,
                "checkpoint": f"groups/group_{index:04d}.pt",
            }
            for index, count in enumerate(model.config.group_sizes)
        ],
        "head_checkpoint": "head.pt",
        "assembled_checkpoint": "assembled.pt",
    }
    manifest_path = Path(directory) / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest_path


def load_assembled(path, map_location="cpu"):
    payload = torch.load(path, map_location=map_location, weights_only=True)
    config = NetworkConfig(**payload["config"])
    model = GroupedDenseNetwork(config)
    model.load_state_dict(payload["state_dict"])
    return model, payload
