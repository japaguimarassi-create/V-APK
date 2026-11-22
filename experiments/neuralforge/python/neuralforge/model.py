import torch
from torch import nn
from torch.utils.checkpoint import checkpoint

from .config import NetworkConfig


class DenseResidualLayer(nn.Module):
    def __init__(self, width, residual_scale):
        super().__init__()
        self.linear = nn.Linear(width, width, bias=True, dtype=torch.float32)
        self.scale = nn.Parameter(torch.tensor(float(residual_scale), dtype=torch.float32))
        nn.init.xavier_uniform_(self.linear.weight)
        nn.init.zeros_(self.linear.bias)

    def forward(self, inputs):
        update = torch.tanh(self.linear(inputs))
        return inputs + self.scale * update


class DenseGroup(nn.Module):
    def __init__(self, width, layer_count, residual_scale):
        super().__init__()
        self.layers = nn.ModuleList([
            DenseResidualLayer(width, residual_scale)
            for _ in range(layer_count)
        ])

    def forward(self, inputs):
        value = inputs
        for layer in self.layers:
            value = layer(value)
        return value


class GroupedDenseNetwork(nn.Module):
    def __init__(self, config=None):
        super().__init__()
        self.config = config or NetworkConfig()
        self.config.validate()
        self.groups = nn.ModuleList([
            DenseGroup(self.config.width, count, self.config.residual_scale)
            for count in self.config.group_sizes
        ])
        self.head = nn.Linear(
            self.config.width, self.config.output_size,
            bias=True, dtype=torch.float32
        )
        nn.init.xavier_uniform_(self.head.weight)
        nn.init.zeros_(self.head.bias)

    def encode(self, inputs, checkpoint_groups=False):
        value = inputs
        for group in self.groups:
            if checkpoint_groups and torch.is_grad_enabled():
                value = checkpoint(group, value, use_reentrant=False)
            else:
                value = group(value)
        return value

    def forward(self, inputs, checkpoint_groups=False):
        return self.head(self.encode(inputs, checkpoint_groups))

    def forward_groupwise(self, inputs, active_group, checkpoint_suffix=True):
        if active_group < 0 or active_group >= len(self.groups):
            raise IndexError("active_group is outside the model.")

        value = inputs
        with torch.no_grad():
            for index in range(active_group):
                value = self.groups[index](value)

        value = value.detach().requires_grad_(True)
        for index in range(active_group, len(self.groups)):
            group = self.groups[index]
            if checkpoint_suffix and torch.is_grad_enabled():
                value = checkpoint(group, value, use_reentrant=False)
            else:
                value = group(value)
        return self.head(value)

    def forward_with_responses(self, inputs):
        value = inputs
        responses = []
        for group in self.groups:
            value = group(value)
            responses.append(value)
        return self.head(value), responses
