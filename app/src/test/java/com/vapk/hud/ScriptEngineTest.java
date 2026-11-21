package com.vapk.hud;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ScriptEngineTest {
    @Test
    public void executesHudAndCrossAppCommandsInOrder() throws Exception {
        List<String> calls = new ArrayList<>();
        ScriptEngine.Host host = new ScriptEngine.Host() {
            @Override
            public void hud(String command, String value) {
                calls.add("HUD." + command + ":" + value);
            }

            @Override
            public String app(String command, String value) {
                calls.add("APP." + command + ":" + value);
                return "ok";
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        String script = String.join("\n",
            "public class Example {",
            "public static void run() {",
            "HUD.setTitle(\"V-APK\");",
            "APP.tapText(\"Continue\");",
            "APP.typeText(\"sample text\");",
            "APP.scroll(\"forward\");",
            "APP.back();",
            "HUD.setVisible(true);",
            "}",
            "}"
        );

        String result = new ScriptEngine(host).execute(script);

        assertEquals(6, calls.size());
        assertEquals("HUD.setTitle:V-APK", calls.get(0));
        assertEquals("APP.tapText:Continue", calls.get(1));
        assertEquals("APP.typeText:sample text", calls.get(2));
        assertEquals("APP.scroll:forward", calls.get(3));
        assertEquals("APP.back:", calls.get(4));
        assertEquals("HUD.setVisible:true", calls.get(5));
        assertTrue(result.contains("6 comando"));
    }

    @Test
    public void rejectsArbitraryJavaStatements() {
        ScriptEngine.Host host = new ScriptEngine.Host() {
            @Override
            public void hud(String command, String value) {
            }

            @Override
            public String app(String command, String value) {
                return "";
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        assertThrows(IllegalArgumentException.class,
            () -> new ScriptEngine(host).execute("Runtime.getRuntime().exec(\"id\");"));
    }

    @Test
    public void enforcesWaitLimit() {
        ScriptEngine.Host host = new ScriptEngine.Host() {
            @Override
            public void hud(String command, String value) {
            }

            @Override
            public String app(String command, String value) {
                return "";
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        assertThrows(IllegalArgumentException.class,
            () -> new ScriptEngine(host).execute("APP.waitMs(5001);"));
    }

    @Test
    public void stopsWhenCancelled() {
        ScriptEngine.Host host = new ScriptEngine.Host() {
            @Override
            public void hud(String command, String value) {
                throw new AssertionError("No command should be executed");
            }

            @Override
            public String app(String command, String value) {
                return "";
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        assertThrows(InterruptedException.class,
            () -> new ScriptEngine(host).execute("HUD.log(\"nope\");"));
    }
}
