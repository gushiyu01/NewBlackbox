package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.app.role.BRIRoleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IRoleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IRoleManagerProxy";
    public static final String ROLE_SERVICE = "role";

    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(ROLE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIRoleManagerStub.get().asInterface(BRServiceManager.get().getService(ROLE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(ROLE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("isRoleHeldAsUser")
    public static class IsRoleHeldAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // The framework checks that the calling UID owns the queried package before
            // answering. Guest apps run under the host UID, so that check throws
            // SecurityException instead of returning. A sandboxed process is never a role
            // holder on the real system, so answer that directly.
            return false;
        }
    }

    @ProxyMethod("isRoleHeld")
    public static class IsRoleHeld extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return false;
        }
    }

    @ProxyMethod("isRoleAvailable")
    public static class IsRoleAvailable extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }

    @ProxyMethod("getRoleHoldersAsUser")
    public static class GetRoleHoldersAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<String>();
        }
    }

    @ProxyMethod("getDefaultApplication")
    public static class GetDefaultApplication extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }
}
