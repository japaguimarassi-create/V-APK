# V-APK

Android app in Java 17 with a floating HUD, a restricted Java-like script runner, and a reinforcement-learning agent.

## Current flow

1. Select a text or `.java` file.
2. V-APK reads the complete file (up to 128 KB) and validates every command.
3. After validation, the agent enters **standby**. Nothing in the file runs during analysis.
4. Tap **LIGAR IA + EXECUTAR ARQUIVO** in the floating HUD. The app brings the runner forward and emits its internal `SCRIPT_STARTED` event immediately before the first command.
5. The agent changes to active state and the validated command plan runs in the app's restricted virtual-bot environment.

The HUD is an Android overlay. Android will ask for the "Display over other apps" permission.

## Java script format

This is a deliberately restricted Java-like `BotApi` program. It is parsed and interpreted by the app; it is **not** compiled as arbitrary Java bytecode.

```java
public final class ExampleBot {
    public static void run(BotApi bot) {
        bot.log("Starting sequence");
        bot.move("right");
        bot.waitMs(250);
        bot.attack();
        bot.move("up");
        bot.log("Sequence complete");
    }
}
```

Allowed calls:

- `bot.move("up" | "down" | "left" | "right")`
- `bot.attack()`
- `bot.waitMs(0..2000)`
- `bot.log("message")`

Maximums: 128 KB per file, 300 commands per file, 2,000 ms per wait. Any unsupported statement causes the entire file to be rejected before execution. The current BotApi controls only the app's virtual bot; it does not inject code into other apps or control Android globally.

## Neural network and training

The model is a Deep Q-Network with 8 inputs, four hidden layers of 100 neurons each, and 5 action values:

`8 → 100 → 100 → 100 → 100 → 5`

Training happens on-device in a 10×10 arena. The agent faces a pursuing enemy, learns to reach a goal, and can use an attack action when close to the enemy. Rewards and penalties are stored in a replay buffer and used for temporal-difference updates. The model is persisted in the app's private storage as `neural-4x100.bin`.

The app starts with a short training run when no model is saved. The training button starts another 250-episode run.

## Build

Pushes to `main` run the Android build workflow. The debug APK is uploaded as the GitHub Actions artifact `V-APK-debug`.

Manual build requirements: JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.9.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.
