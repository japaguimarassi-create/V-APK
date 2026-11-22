import json
from pathlib import Path

import torch

from .native import calculate_stats


class RunningStats:
    def __init__(self):
        self.count = 0
        self.mean = 0.0
        self.m2 = 0.0
        self.minimum = float("inf")
        self.maximum = float("-inf")
        self.sum_squares = 0.0

    def update(self, batch_stats):
        count_b = int(batch_stats["count"])
        if count_b <= 0:
            return

        mean_b = float(batch_stats["mean"])
        std_b = float(batch_stats["std"])
        if self.count == 0:
            self.count = count_b
            self.mean = mean_b
            self.m2 = std_b * std_b * count_b
        else:
            total = self.count + count_b
            delta = mean_b - self.mean
            self.m2 += std_b * std_b * count_b
            self.m2 += delta * delta * self.count * count_b / total
            self.mean += delta * count_b / total
            self.count = total

        self.minimum = min(self.minimum, float(batch_stats["min"]))
        self.maximum = max(self.maximum, float(batch_stats["max"]))
        rms = float(batch_stats["rms"])
        self.sum_squares += rms * rms * count_b

    def to_dict(self):
        if self.count == 0:
            return {"count": 0}
        return {
            "count": self.count,
            "mean": self.mean,
            "std": max(self.m2 / self.count, 0.0) ** 0.5,
            "min": self.minimum,
            "max": self.maximum,
            "rms": (self.sum_squares / self.count) ** 0.5,
        }


def collect_response_bank(model, inputs, batch_size, device, native_library=None):
    totals = [RunningStats() for _ in model.groups]
    model.eval()
    with torch.no_grad():
        for start in range(0, len(inputs), batch_size):
            batch = inputs[start:start + batch_size].to(device)
            _, responses = model.forward_with_responses(batch)
            for index, response in enumerate(responses):
                values = response.detach().float().cpu().contiguous().numpy()
                totals[index].update(calculate_stats(values, native_library))

    return [
        {
            "group_index": index,
            "layer_start": sum(model.config.group_sizes[:index]),
            "layer_count": model.config.group_sizes[index],
            "activation_contract": totals[index].to_dict(),
        }
        for index in range(len(totals))
    ]


def save_response_bank(entries, path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(entries, indent=2), encoding="utf-8")
    temporary.replace(path)
