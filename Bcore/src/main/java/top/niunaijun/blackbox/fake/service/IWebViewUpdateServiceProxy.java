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
import top.niunaijun.blackbox.utils.Reflector;
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
            Slog.w(TAG, "webviewupdate service is not published; proxy will not be installed");
            return null;
        }
        try {
            return BRIWebViewUpdateServiceStub.get().asInterface(binder);
        } catch (Throwable e) {
            // ClassInvocationStub drops the whole proxy when getWho() returns null, so
            // fall back to the raw binder to keep the hooks reachable.
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

    @ProxyMethod("waitForAndGetProvider")
    public static class WaitForAndGetProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return disableMultiprocess(method.invoke(who, args));
        }
    }

    /**
     * WebViewFactory reads the provider's "Multiprocess" meta-data out of the
     * WebViewProviderResponse this call returns, then hands it to Chromium. That
     * response is a plain object, so the flag has to be cleared on the nested
     * ProviderInfo/PackageInfo rather than on a bare PackageInfo[].
     */
    private static Object disableMultiprocess(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof PackageInfo[]) {
            for (PackageInfo info : (PackageInfo[]) result) {
                clearFlag(info);
            }
            return result;
        }
        try {
            Object providerInfo = Reflector.on(result.getClass()).field("info").get(result);
            if (providerInfo instanceof PackageInfo) {
                clearFlag((PackageInfo) providerInfo);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "could not reach provider info on " + result.getClass().getName() + ": " + e.getMessage());
        }
        return result;
    }

    private static void clearFlag(PackageInfo info) {
        if (info == null || info.applicationInfo == null) {
            return;
        }
        Bundle metaData = info.applicationInfo.metaData;
        if (metaData != null && metaData.containsKey(MULTIPROCESS_METADATA)) {
            metaData.putBoolean(MULTIPROCESS_METADATA, false);
            Slog.d(TAG, "Disabled multiprocess WebView for " + info.packageName);
        }
    }
}
