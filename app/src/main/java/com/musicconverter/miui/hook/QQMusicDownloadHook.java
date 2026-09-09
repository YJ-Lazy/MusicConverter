package com.musicconverter.miui.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Fixed-version adapter, independently implemented from the inspected download flow. */
public final class QQMusicDownloadHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.tencent.qqmusic";
    private static final String TARGET_VERSION = "20.7.0.8";
    private static final String TASK_CLASS =
            "com.tencent.qqmusic.business.musicdownload.DownloadSongTask";
    private static final String TAG = "[MusicConverter-QQDownload] ";
    private static final Set<ClassLoader> INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName) || !lpparam.isFirstApplication) {
            return;
        }
        log("LOADED process=" + lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || !(param.args[0] instanceof Context)) {
                                return;
                            }
                            Context context = (Context) param.args[0];
                            if (TARGET_PACKAGE.equals(context.getPackageName())) {
                                install(context, lpparam.processName);
                            }
                        }
                    });
        } catch (Throwable error) {
            log("ERROR attach: " + error);
        }
    }

    @SuppressWarnings("deprecation")
    private static void install(Context context, final String processName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (!TARGET_VERSION.equals(info.versionName)) {
                log("SKIP unsupported version=" + info.versionName
                        + "; expected=" + TARGET_VERSION);
                return;
            }
            ClassLoader loader = context.getClassLoader();
            synchronized (INSTALLED) {
                if (INSTALLED.contains(loader)) {
                    return;
                }
                Class<?> taskClass = Class.forName(TASK_CLASS, false, loader);
                Method needEncrypt = taskClass.getDeclaredMethod("n");
                if (needEncrypt.getReturnType() != boolean.class
                        || !Modifier.isPublic(needEncrypt.getModifiers())
                        || Modifier.isStatic(needEncrypt.getModifiers())) {
                    log("SKIP unexpected method signature");
                    return;
                }
                final AtomicBoolean firstCall = new AtomicBoolean(true);
                XposedBridge.hookMethod(needEncrypt, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        if (firstCall.compareAndSet(true, false)) {
                            log("HIT needEncrypt=false process=" + processName);
                        }
                        return Boolean.FALSE;
                    }
                });
                INSTALLED.add(loader);
                log("INSTALLED " + TARGET_VERSION + " DownloadSongTask.n()Z process="
                        + processName);
            }
        } catch (Throwable error) {
            log("ERROR install: " + error);
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
