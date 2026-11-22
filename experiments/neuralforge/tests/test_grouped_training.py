import sys
from pathlib import Path

import numpy as np
import torch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "python"))

from neuralforge.config import NetworkConfig
from neuralforge.model import GroupedDenseNetwork
from neuralforge.native import calculate_stats, load_native_library
from neuralforge.response_bank import RunningStats
from neuralforge.trainer import train_groupwise


def small_model():
    config = NetworkConfig(
        input_size=8, hidden_layers=4, width=8,
        output_size=2, layers_per_group=2
    )
    return GroupedDenseNetwork(config)


def test_group_partition_and_forward():
    model = small_model()
    assert model.config.group_sizes == [2, 2]
    result, responses = model.forward_with_responses(torch.zeros(3, 8))
    assert result.shape == (3, 2)
    assert len(responses) == 2
    for response in responses:
        assert response.shape == (3, 8)
        assert torch.allclose(
            response.mean(dim=-1),
            torch.zeros(3),
            atol=1e-5
        )
    assert torch.isfinite(result).all()


def test_groupwise_training_saves_each_group(tmp_path):
    torch.manual_seed(4)
    model = small_model()
    inputs = torch.randn(12, 8)
    targets = torch.randn(12, 2)

    history = train_groupwise(
        model=model, inputs=inputs, targets=targets,
        checkpoint_dir=tmp_path, group_cycles=1, group_epochs=1,
        batch_size=4, learning_rate=1e-3, device="cpu", joint_epochs=0
    )

    assert len(history["groups"]) == 2
    assert (tmp_path / "groups" / "group_0000.pt").exists()
    assert (tmp_path / "groups" / "group_0001.pt").exists()
    assert (tmp_path / "head.pt").exists()


def test_response_stats_are_finite():
    values = np.array([-2.0, 0.0, 2.0], dtype=np.float32)
    stats = calculate_stats(values, load_native_library())
    assert stats["count"] == 3
    assert abs(stats["mean"]) < 1e-6
    assert abs(stats["std"] - (8.0 / 3.0) ** 0.5) < 1e-5
    assert stats["min"] == -2.0
    assert stats["max"] == 2.0


def test_running_response_contract():
    running = RunningStats()
    running.update({
        "count": 2, "mean": 1.0, "std": 1.0,
        "min": 0.0, "max": 2.0, "rms": 2 ** 0.5
    })
    running.update({
        "count": 2, "mean": 3.0, "std": 1.0,
        "min": 2.0, "max": 4.0, "rms": (10.0) ** 0.5
    })
    result = running.to_dict()
    assert result["count"] == 4
    assert abs(result["mean"] - 2.0) < 1e-8
    assert abs(result["std"] - (2.0) ** 0.5) < 1e-8
