package com.ash.phonecontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Only listens to WhatsApp (see accessibility_service_config.xml).
 * It presses the Send button once, right after you ask for a WhatsApp message.
 */
public class AshAccessibilityService extends AccessibilityService {

    public static volatile boolean connected = false;
    public static volatile long sendUntil = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (System.currentTimeMillis() > sendUntil) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo send = findSend(root);
        if (send != null && clickNode(send)) {
            sendUntil = 0; // only one message per command
        }
    }

    private AccessibilityNodeInfo findSend(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
        if (byId != null && !byId.isEmpty()) return byId.get(0);
        return searchByDescription(root);
    }

    private AccessibilityNodeInfo searchByDescription(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence d = node.getContentDescription();
        if (d != null && d.toString().equalsIgnoreCase("Send")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = searchByDescription(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        while (n != null) {
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            n = n.getParent();
        }
        return false;
    }
}
