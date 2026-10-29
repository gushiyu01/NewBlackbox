package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


/**
 * LocaleManager answers per-package locale questions only for the caller that
 * owns the package, and the guest's package is not registered with the real
 * system. Play Store sets and reads its own locales during startup, so both
 * calls threw SecurityException and killed its worker threads.
 *
 * Inside the container a guest has no per-app locale of its own, so reporting
 * "no override" is also the truthful answer.
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";
    public static final String LOCALE_SERVICE = "locale";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService(LOCALE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(BRServiceManager.get().getService(LOCALE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(LOCALE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getApplicationLocales")
    public static class GetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return emptyLocaleList();
        }
    }

    @ProxyMethod("getOverrideLocaleConfig")
    public static class GetOverrideLocaleConfig extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    /** LocaleList.getEmptyLocaleList() without depending on the class at build time. */
    private static Object emptyLocaleList() {
        try {
            Class<?> localeList = Class.forName("android.os.LocaleList");
            return localeList.getMethod("getEmptyLocaleList").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }
}
