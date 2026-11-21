public final class BasicBot {
    public static void run(BotApi bot) {
        bot.log("Sequence started");
        bot.move("right");
        bot.waitMs(250);
        bot.attack();
        bot.move("up");
        bot.log("Sequence finished");
    }
}
