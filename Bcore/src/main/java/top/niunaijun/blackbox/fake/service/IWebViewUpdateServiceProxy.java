package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.webkit.BRIWebViewUpdateServiceStub;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


public class IWebViewUpdateServiceProxy extends BinderInvocationStub {
    public static final String TAG = "IWebViewUpdateServiceProxy";
    public static final String WEBVIEW_UPDATE_SERVICE = "webviewupdate";
    public static final String MULTIPROCESS_METADATA = "android.webkit.WebView.Multiprocess";

    public IWebViewUpdateServiceProxy() {
        super(BRServiceManager.get().getService(WEBVIEW_UPDATE_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(WEBVIEW_UPDATE_SERVICE);
        if (binder == null) {
            return null;
        }
        try {
            return BRIWebViewUpdateServiceStub.get().asInterface(binder);
        } catch (Throwable e) {
            Slog.w(TAG, "asInterface failed, proxying the raw binder: " + e.getMessage());
            return binder;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(WEBVIEW_UPDATE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isMultiProcessEnabled")
    public static class IsMultiProcessEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("getValidWebViewPackages")
    public static class GetValidWebViewPackages extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return disableMultiprocess(method.invoke(who, args));
        }
    }

    @ProxyMethod("getWebViewPackages")
    public static class GetWebViewPackages extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return disableMultiprocess(method.invoke(who, args));
        }
    }

    /**
     * WebViewFactory reads the provider's "Multiprocess" meta-data and hands it to
     * Chromium, which then starts its renderer in a separate OS process. That
     * process gets none of the container's path redirection, so the guest's WebView
     * data directory looks missing and pages fail with net::ERR_CACHE_MISS.
     * Clearing the flag keeps rendering in the host process.
     */
    private static Object disableMultiprocess(Object result) {
        if (!(result instanceof PackageInfo[])) {
            return result;
        }
        for (PackageInfo info : (PackageInfo[]) result) {
            if (info == null || info.applicationInfo == null) {
                continue;
            }
            Bundle metaData = info.applicationInfo.metaData;
            if (metaData != null && metaData.containsKey(MULTIPROCESS_METADATA)) {
                metaData.putBoolean(MULTIPROCESS_METADATA, false);
                Slog.d(TAG, "Disabled multiprocess WebView for " + info.packageName);
            }
        }
        return result;
    }
}
