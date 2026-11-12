# Loaded by name from assets/xposed_init rather than from an Android component.
-keep,allowoptimization class com.github.voxqon.xposed.autoflow.XposedEntry {
    public <init>();
    public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# Xposed invokes these overrides in the host process.
-keepclassmembers,allowoptimization class * extends de.robv.android.xposed.XC_MethodHook {
    protected void beforeHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
    protected void afterHookedMethod(de.robv.android.xposed.XC_MethodHook$MethodHookParam);
}

# Reflection in the hooks targets framework/ROM classes, not module class names.
