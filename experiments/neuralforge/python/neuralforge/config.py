from dataclasses import asdict, dataclass
from math import ceil


@dataclass
class NetworkConfig:
    input_size: int = 1024
    hidden_layers: int = 2048
    width: int = 1024
    output_size: int = 1024
    layers_per_group: int = 32
    residual_scale: float = 0.02

    def validate(self):
        values = (self.input_size, self.hidden_layers, self.width,
                  self.output_size, self.layers_per_group)
        if any(int(value) <= 0 for value in values):
            raise ValueError("All network dimensions must be positive.")
        if self.residual_scale <= 0:
            raise ValueError("residual_scale must be positive.")

    @property
    def group_count(self):
        return ceil(self.hidden_layers / self.layers_per_group)

    @property
    def group_sizes(self):
        remaining = self.hidden_layers
        sizes = []
        while remaining:
            current = min(self.layers_per_group, remaining)
            sizes.append(current)
            remaining -= current
        return sizes

    @property
    def dense_weight_count(self):
        return self.hidden_layers * self.width * self.width + self.width * self.output_size

    @property
    def parameter_count(self):
        hidden_biases = self.hidden_layers * self.width
        head_biases = self.output_size
        residual_scales = self.hidden_layers
        return self.dense_weight_count + hidden_biases + head_biases + residual_scales

    @property
    def fp32_parameter_bytes(self):
        return self.parameter_count * 4

    @property
    def group_fp32_bytes(self):
        return self.layers_per_group * (self.width * self.width + self.width + 1) * 4

    def to_dict(self):
        return asdict(self)

    def summary(self):
        return {
            "input_size": self.input_size,
            "hidden_layers": self.hidden_layers,
            "neurons_per_hidden_layer": self.width,
            "output_size": self.output_size,
            "groups": self.group_count,
            "layers_per_group": self.layers_per_group,
            "group_sizes": self.group_sizes,
            "dense_weight_count": self.dense_weight_count,
            "total_parameters": self.parameter_count,
            "fp32_parameter_bytes": self.fp32_parameter_bytes,
            "fp32_parameter_gib": self.fp32_parameter_bytes / (1024 ** 3),
            "largest_group_fp32_mib": self.group_fp32_bytes / (1024 ** 2),
        }
