import torch
import torch.nn.functional as F
from torch.distributions import Categorical

from .checkpoint import save_group, save_head


def _set_all_trainable(model, enabled):
    for parameter in model.parameters():
        parameter.requires_grad_(enabled)


def _batches(inputs, targets, batch_size, shuffle=True):
    count = inputs.shape[0]
    order = torch.randperm(count) if shuffle else torch.arange(count)
    for start in range(0, count, batch_size):
        indices = order[start:start + batch_size]
        yield inputs.index_select(0, indices), targets.index_select(0, indices)


def _hybrid_objective(logits, targets, reinforcement_weight):
    supervised_loss = F.mse_loss(logits, targets)
    if reinforcement_weight <= 0:
        zero = torch.zeros((), device=logits.device, dtype=logits.dtype)
        return supervised_loss, {
            "supervised": float(supervised_loss.detach().cpu()),
            "policy": 0.0,
        }

    policy = Categorical(logits=logits)
    sampled_actions = policy.sample()
    target_actions = targets.argmax(dim=-1)
    rewards = torch.where(
        sampled_actions == target_actions,
        torch.ones_like(sampled_actions, dtype=logits.dtype),
        torch.full_like(sampled_actions, -0.2, dtype=logits.dtype),
    )
    policy_loss = -(rewards.detach() * policy.log_prob(sampled_actions)).mean()
    total_loss = supervised_loss + reinforcement_weight * policy_loss
    return total_loss, {
        "supervised": float(supervised_loss.detach().cpu()),
        "policy": float(policy_loss.detach().cpu()),
    }


def _train_output_head(
    model, inputs, targets, batch_size, device, learning_rate,
    reinforcement_weight, checkpoint_dir
):
    _set_all_trainable(model, False)
    for parameter in model.head.parameters():
        parameter.requires_grad_(True)

    optimizer = torch.optim.SGD(model.head.parameters(), lr=learning_rate)
    total_losses = []
    supervised_losses = []
    policy_losses = []
    model.train()

    for x_batch, y_batch in _batches(inputs, targets, batch_size):
        x_batch = x_batch.to(device)
        y_batch = y_batch.to(device)
        with torch.no_grad():
            latent = model.encode(x_batch)
        optimizer.zero_grad(set_to_none=True)
        prediction = model.head(latent)
        loss, components = _hybrid_objective(
            prediction, y_batch, reinforcement_weight
        )
        loss.backward()
        optimizer.step()
        total_losses.append(float(loss.detach().cpu()))
        supervised_losses.append(components["supervised"])
        policy_losses.append(components["policy"])

    save_head(model, checkpoint_dir)
    denominator = max(len(total_losses), 1)
    return {
        "loss": sum(total_losses) / denominator,
        "supervised_loss": sum(supervised_losses) / denominator,
        "policy_loss": sum(policy_losses) / denominator,
    }


def train_groupwise(
    model,
    inputs,
    targets,
    checkpoint_dir,
    group_cycles=1,
    group_epochs=1,
    batch_size=1,
    learning_rate=1e-4,
    device="cpu",
    joint_epochs=0,
    reinforcement_weight=0.2,
):
    if reinforcement_weight < 0:
        raise ValueError("reinforcement_weight cannot be negative.")

    inputs = inputs.detach().to("cpu", dtype=torch.float32)
    targets = targets.detach().to("cpu", dtype=torch.float32)
    model.to(device)
    history = {"head": [], "groups": [], "joint": []}

    for cycle in range(group_cycles):
        head_metrics = _train_output_head(
            model, inputs, targets, batch_size, device, learning_rate,
            reinforcement_weight, checkpoint_dir
        )
        history["head"].append({"cycle": cycle, **head_metrics})

        for group_index, group in enumerate(model.groups):
            _set_all_trainable(model, False)
            for parameter in group.parameters():
                parameter.requires_grad_(True)

            optimizer = torch.optim.SGD(group.parameters(), lr=learning_rate)
            total_losses = []
            supervised_losses = []
            policy_losses = []
            model.train()

            for _ in range(group_epochs):
                for x_batch, y_batch in _batches(inputs, targets, batch_size):
                    x_batch = x_batch.to(device)
                    y_batch = y_batch.to(device)
                    optimizer.zero_grad(set_to_none=True)
                    prediction = model.forward_groupwise(
                        x_batch, group_index, checkpoint_suffix=True
                    )
                    loss, components = _hybrid_objective(
                        prediction, y_batch, reinforcement_weight
                    )
                    if not torch.isfinite(loss):
                        raise FloatingPointError(
                            f"Non-finite loss while training group {group_index}."
                        )
                    loss.backward()
                    torch.nn.utils.clip_grad_norm_(group.parameters(), max_norm=1.0)
                    optimizer.step()
                    total_losses.append(float(loss.detach().cpu()))
                    supervised_losses.append(components["supervised"])
                    policy_losses.append(components["policy"])

            save_group(model, group_index, checkpoint_dir)
            denominator = max(len(total_losses), 1)
            history["groups"].append({
                "cycle": cycle,
                "group_index": group_index,
                "layers": model.config.group_sizes[group_index],
                "loss": sum(total_losses) / denominator,
                "supervised_loss": sum(supervised_losses) / denominator,
                "policy_loss": sum(policy_losses) / denominator,
            })

    if joint_epochs > 0:
        _set_all_trainable(model, True)
        optimizer = torch.optim.SGD(model.parameters(), lr=learning_rate * 0.1)
        for epoch in range(joint_epochs):
            epoch_total = []
            epoch_supervised = []
            epoch_policy = []
            model.train()
            for x_batch, y_batch in _batches(inputs, targets, batch_size):
                x_batch = x_batch.to(device)
                y_batch = y_batch.to(device)
                optimizer.zero_grad(set_to_none=True)
                prediction = model(x_batch, checkpoint_groups=True)
                loss, components = _hybrid_objective(
                    prediction, y_batch, reinforcement_weight
                )
                if not torch.isfinite(loss):
                    raise FloatingPointError("Non-finite loss during joint fine-tuning.")
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
                optimizer.step()
                epoch_total.append(float(loss.detach().cpu()))
                epoch_supervised.append(components["supervised"])
                epoch_policy.append(components["policy"])
            denominator = max(len(epoch_total), 1)
            history["joint"].append({
                "epoch": epoch,
                "loss": sum(epoch_total) / denominator,
                "supervised_loss": sum(epoch_supervised) / denominator,
                "policy_loss": sum(epoch_policy) / denominator,
            })

        for group_index in range(len(model.groups)):
            save_group(model, group_index, checkpoint_dir)
        save_head(model, checkpoint_dir)

    _set_all_trainable(model, False)
    return history
