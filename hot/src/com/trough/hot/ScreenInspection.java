package com.trough.hot;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityEvent;
import android.graphics.Rect;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** On-demand, read-only host diagnostics for the signed development channel. */
final class ScreenInspection {
    private static volatile WeakReference<AccessibilityService> connected = new WeakReference<>(null);
    static void component(Object host, String event) {
        if (!(host instanceof AccessibilityService)) return;
        AccessibilityService service = (AccessibilityService)host;
        if ("unbind".equals(event)) {
            if (connected.get() == service) connected = new WeakReference<>(null);
            return;
        }
        if ("connected".equals(event) || connected.get() != service) {
            // Observation must not turn ordinary touches into exploration or filter keys.
            AccessibilityServiceInfo info = service.getServiceInfo();
            if (info != null) {
                info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_VIEW_FOCUSED;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.notificationTimeout = 100;
                service.setServiceInfo(info);
            }
            connected = new WeakReference<>(service);
        }
    }
    private static String bounded(CharSequence value) {
        String text = value == null ? "" : value.toString();
        return text.length() > 64 ? text.substring(0,64) : text;
    }
    private static void nodes(AccessibilityNodeInfo node, List<Object> out, int depth, int[] budget) {
        if (node == null) return;
        try {
            if (budget[0] == 0 || depth > 20) return;
            budget[0]--;
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            out.add(Json.map("depth",depth,"package",bounded(node.getPackageName()),
                "class",bounded(node.getClassName()),"id",bounded(node.getViewIdResourceName()),
                "text",node.isPassword() ? "[password]" : bounded(node.getText()),
                "description",node.isPassword() ? "" : bounded(node.getContentDescription()),
                "bounds",bounds.flattenToString(),"visible",node.isVisibleToUser(),
                "focused",node.isFocused(),"editable",node.isEditable(),"clickable",node.isClickable()));
            for (int i = 0; i < node.getChildCount() && budget[0] > 0; ++i)
                nodes(node.getChild(i),out,depth+1,budget);
        } finally { node.recycle(); }
    }
    static Map<String,Object> tree() {
        AccessibilityService service = connected.get();
        if (service == null) return Json.map("ok",false,"connected",false,
            "error","Trough accessibility service is not connected");
        List<Object> windows = new ArrayList<>();
        int[] budget = {80};
        for (AccessibilityWindowInfo window : service.getWindows()) {
            try {
                if (windows.size() >= 6) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                List<Object> content = new ArrayList<>(); nodes(window.getRoot(),content,0,budget);
                windows.add(Json.map("id",window.getId(),"type",window.getType(),
                    "active",window.isActive(),"focused",window.isFocused(),
                    "bounds",bounds.flattenToString(),"nodes",content));
            } finally { window.recycle(); }
        }
        return Json.map("ok",true,"connected",true,"windows",windows,"node_limit_reached",budget[0]==0);
    }
    static Map<String,Object> inspect(Context ctx) {
        AccessibilityManager manager = (AccessibilityManager)ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<Object> services = new ArrayList<>();
        for (AccessibilityServiceInfo service : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            services.add(Json.map("id",service.getId(),"flags",service.flags,
                "capabilities",service.getCapabilities(),"event_types",service.eventTypes));
        List<Object> devices = new ArrayList<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null) devices.add(Json.map("id",id,"name",device.getName(),
                "sources",device.getSources(),"virtual",device.isVirtual(),
                "keyboard_type",device.getKeyboardType()));
        }
        PowerManager power = (PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memory);
        Intent battery = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return Json.map("ok",true,"accessibility_enabled",manager.isEnabled(),
            "trough_service_connected",connected.get()!=null,
            "touch_exploration_enabled",manager.isTouchExplorationEnabled(),"enabled_services",services,
            "default_input_method",Settings.Secure.getString(ctx.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD),
            "input_devices",devices,"thermal_status",power.getCurrentThermalStatus(),
            "power_save",power.isPowerSaveMode(),"interactive",power.isInteractive(),
            "available_memory",memory.availMem,"low_memory",memory.lowMemory,
            "battery_level",battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),
            "battery_temperature_tenths_c",battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1));
    }
}
