package com.musicconverter.miui.hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/** Modern Xposed API 102 module; reference QQ Music version: 20.7.0.8. */
public final class QQMusicDownloadHook extends XposedModule {
    private static final String TARGET_PACKAGE = "com.tencent.qqmusic";
    private static final String REFERENCE_VERSION = "20.7.0.8";
    private static final String TASK_CLASS =
            "com.tencent.qqmusic.business.musicdownload.DownloadSongTask";
    private static final String TAG = "MusicConverter-QQDownload";
    private final Set<ClassLoader> installed =
            Collections.newSetFromMap(new WeakHashMap<ClassLoader, Boolean>());
    private final AtomicBoolean attachInstalled = new AtomicBoolean(false);
    private String processName = "unknown";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "LOADED API=102 process=" + processName);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()
                || !attachInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            // Wait until attach completes, preserving the original hook's context/classloader timing.
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            hook(attach).intercept(chain -> {
                Object result = chain.proceed();
                Object argument = chain.getArg(0);
                if (argument instanceof Context) {
                    Context context = (Context) argument;
                    if (TARGET_PACKAGE.equals(context.getPackageName())) {
                        install(context);
                    }
                }
                return result;
            });
        } catch (Throwable error) {
            attachInstalled.set(false);
            log(Log.ERROR, TAG, "ERROR attach", error);
        }
    }

    @SuppressWarnings("deprecation")
    private void install(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            String version = info.versionName;
            if (!REFERENCE_VERSION.equals(version)) {
                log(Log.WARN, TAG, "WARN unverified version=" + version
                        + "; reference=" + REFERENCE_VERSION + "; trying hook");
            }
            ClassLoader loader = context.getClassLoader();
            synchronized (installed) {
                if (installed.contains(loader)) {
                    return;
                }
                Class<?> taskClass = Class.forName(TASK_CLASS, false, loader);
                Method needEncrypt = taskClass.getDeclaredMethod("n");
                if (needEncrypt.getReturnType() != boolean.class
                        || !Modifier.isPublic(needEncrypt.getModifiers())
                        || Modifier.isStatic(needEncrypt.getModifiers())) {
                    log(Log.WARN, TAG, "SKIP unexpected method signature");
                    return;
                }
                AtomicBoolean firstCall = new AtomicBoolean(true);
                hook(needEncrypt).intercept(chain -> {
                    if (firstCall.compareAndSet(true, false)) {
                        log(Log.INFO, TAG, "HIT needEncrypt=false process=" + processName);
                    }
                    return Boolean.FALSE;
                });
                installed.add(loader);
                log(Log.INFO, TAG, "INSTALLED version=" + version
                        + " DownloadSongTask.n()Z process=" + processName);
            }
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "ERROR install", error);
        }
    }
}
