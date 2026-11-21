package com.vapk.hud;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.text.InputType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class AutomationAccessibilityService extends AccessibilityService {
    private static volatile AutomationAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 120;
            info.flags = AccessibilityServiceInfo.DEFAULT;
            setServiceInfo(info);
        }
        HudOverlayService.command("log", "Automação de interface conectada");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events are intentionally not recorded or transmitted.
    }

    @Override
    public void onInterrupt() {
        HudOverlayService.command("log", "Automação interrompida pelo Android");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static String executeCommand(String command, String value) throws Exception {
        AutomationAccessibilityService service = instance;
        if (service == null) {
            throw new IllegalStateException(
                "Ative V-APK em Configurações > Acessibilidade > Serviços instalados."
            );
        }
        return service.runOnServiceThread(command, value == null ? "" : value);
    }

    private String runOnServiceThread(String command, String value) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return performCommand(command, value);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();

        mainHandler.post(() -> {
            try {
                result.set(performCommand(command, value));
            } catch (Exception error) {
                failure.set(error);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(4, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Tempo limite aguardando a interface do aplicativo.");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private String performCommand(String command, String value) throws Exception {
        switch (command) {
            case "back":
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                    throw new IllegalStateException("O Android não aceitou a ação Voltar.");
                }
                return "Voltar executado";
            case "tapText":
                return tapExactText(value);
            case "typeText":
                return setFocusedText(value);
            case "scroll":
                return scroll(value);
            default:
                throw new IllegalArgumentException("Ação APP desconhecida: " + command);
        }
    }

    private String tapExactText(String target) {
        if (target.trim().isEmpty()) throw new IllegalArgumentException("tapText exige um texto.");
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("Não foi possível acessar a janela ativa.");
        AccessibilityNodeInfo match = findText(root, target);
        if (match == null) throw new IllegalStateException("Texto visível não encontrado: " + target);

        AccessibilityNodeInfo clickable = match;
        int depth = 0;
        while (clickable != null && !clickable.isClickable() && depth++ < 8) {
            clickable = clickable.getParent();
        }

        boolean clicked = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked && match.isClickable()) clicked = match.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!clicked) throw new IllegalStateException("O elemento foi encontrado, mas não aceita clique.");
        return "Toque enviado para: " + target;
    }

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if ((text != null && target.contentEquals(text))
                || (description != null && target.contentEquals(description))) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findText(node.getChild(i), target);
            if (result != null) return result;
        }
        return null;
    }

    private String setFocusedText(String value) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("Não há janela ativa.");
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null || !focused.isEditable()) {
            throw new IllegalStateException("Toque primeiro em um campo de texto editável.");
        }

        int type = focused.getInputType();
        int variation = type & (InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION);
        if (variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                || variation == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
                || variation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            throw new IllegalStateException("Por segurança, V-APK não preenche campos de senha.");
        }

        Bundle arguments = new Bundle();
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value
        );
        if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw new IllegalStateException("O campo não permite inserir texto por acessibilidade.");
        }
        return "Texto inserido no campo em foco";
    }

    private String scroll(String direction) {
        final boolean forward;
        if ("forward".equals(direction)) forward = true;
        else if ("backward".equals(direction)) forward = false;
        else throw new IllegalArgumentException("scroll aceita forward ou backward.");

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) throw new IllegalStateException("Não há janela ativa.");
        AccessibilityNodeInfo target = findScrollable(root);
        if (target == null) throw new IllegalStateException("Nenhuma área rolável foi encontrada.");

        int action = forward
            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if (!target.performAction(action)) throw new IllegalStateException("A área não aceitou rolagem.");
        return forward ? "Rolagem para frente" : "Rolagem para trás";
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findScrollable(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }
}
