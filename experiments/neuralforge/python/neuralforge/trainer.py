import torch
import torch.nn.functional as F

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


def _train_output_head(model, inputs, targets, batch_size, device, learning_rate, checkpoint_dir):
    _set_all_trainable(model, False)
    for parameter in model.head.parameters():
        parameter.requires_grad_(True)

    optimizer = torch.optim.SGD(model.head.parameters(), lr=learning_rate)
    losses = []
    model.train()
    for x_batch, y_batch in _batches(inputs, targets, batch_size):
        x_batch = x_batch.to(device)
        y_batch = y_batch.to(device)
        with torch.no_grad():
            latent = model.encode(x_batch)
        optimizer.zero_grad(set_to_none=True)
        prediction = model.head(latent)
        loss = F.mse_loss(prediction, y_batch)
        loss.backward()
        optimizer.step()
        losses.append(float(loss.detach().cpu()))

    save_head(model, checkpoint_dir)
    return sum(losses) / max(len(losses), 1)


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
):
    inputs = inputs.detach().to("cpu", dtype=torch.float32)
    targets = targets.detach().to("cpu", dtype=torch.float32)
    model.to(device)
    history = {"head": [], "groups": [], "joint": []}

    for cycle in range(group_cycles):
        head_loss = _train_output_head(
            model, inputs, targets, batch_size, device, learning_rate, checkpoint_dir
        )
        history["head"].append({"cycle": cycle, "loss": head_loss})

        for group_index, group in enumerate(model.groups):
            _set_all_trainable(model, False)
            for parameter in group.parameters():
                parameter.requires_grad_(True)

            optimizer = torch.optim.SGD(group.parameters(), lr=learning_rate)
            group_losses = []
            model.train()

            for _ in range(group_epochs):
                for x_batch, y_batch in _batches(inputs, targets, batch_size):
                    x_batch = x_batch.to(device)
                    y_batch = y_batch.to(device)
                    optimizer.zero_grad(set_to_none=True)
                    prediction = model.forward_groupwise(
                        x_batch, group_index, checkpoint_suffix=True
                    )
                    loss = F.mse_loss(prediction, y_batch)
                    if not torch.isfinite(loss):
                        raise FloatingPointError(
                            f"Non-finite loss while training group {group_index}."
                        )
                    loss.backward()
                    torch.nn.utils.clip_grad_norm_(group.parameters(), max_norm=1.0)
                    optimizer.step()
                    group_losses.append(float(loss.detach().cpu()))

            save_group(model, group_index, checkpoint_dir)
            group_mean = sum(group_losses) / max(len(group_losses), 1)
            history["groups"].append({
                "cycle": cycle,
                "group_index": group_index,
                "layers": model.config.group_sizes[group_index],
                "loss": group_mean,
            })

    if joint_epochs > 0:
        _set_all_trainable(model, True)
        optimizer = torch.optim.SGD(model.parameters(), lr=learning_rate * 0.1)
        for epoch in range(joint_epochs):
            epoch_losses = []
            model.train()
            for x_batch, y_batch in _batches(inputs, targets, batch_size):
                x_batch = x_batch.to(device)
                y_batch = y_batch.to(device)
                optimizer.zero_grad(set_to_none=True)
                prediction = model(x_batch, checkpoint_groups=True)
                loss = F.mse_loss(prediction, y_batch)
                if not torch.isfinite(loss):
                    raise FloatingPointError("Non-finite loss during joint fine-tuning.")
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
                optimizer.step()
                epoch_losses.append(float(loss.detach().cpu()))
            history["joint"].append({
                "epoch": epoch,
                "loss": sum(epoch_losses) / max(len(epoch_losses), 1),
            })

        for group_index in range(len(model.groups)):
            save_group(model, group_index, checkpoint_dir)
        save_head(model, checkpoint_dir)

    _set_all_trainable(model, False)
    return history
