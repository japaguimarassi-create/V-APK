public class Example {
    public static void run() {
        HUD.setTitle("V-APK / cross-app script");
        HUD.setText("HUD connected");
        HUD.log("Start the automation in the target app");

        APP.waitMs(500);
        APP.tapText("Continue");
        APP.waitMs(250);
        APP.typeText("Test input from V-APK");
        APP.scroll("forward");
        APP.back();
    }
}
