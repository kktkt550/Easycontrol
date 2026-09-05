/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.wrappers;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.os.Build;
import android.view.Display;
import android.view.Surface;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.DisplayInfo;
import top.saymzx.easycontrol.server.helper.FakeContext;

public final class DisplayManager {
    private static Object manager;

    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    public static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL_PUBLIC = VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    public static void init(Object m) {
        manager = m;
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Device.execReadOutput("dumpsys display");
            Matcher m = Pattern.compile("mOverrideDisplayInfo=DisplayInfo.*?, displayId " + displayId + ".*?, real ([0-9]+) x ([0-9]+).*?, rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)").matcher(dumpsysDisplayOutput);
            if (!m.find()) return null;
            int width = Integer.parseInt(Objects.requireNonNull(m.group(1)));
            int height = Integer.parseInt(Objects.requireNonNull(m.group(2)));
            int rotation = Integer.parseInt(Objects.requireNonNull(m.group(3)));
            int density = Integer.parseInt(Objects.requireNonNull(m.group(4)));
            int layerStack = Integer.parseInt(Objects.requireNonNull(m.group(5)));
            return new DisplayInfo(displayId, width, height, rotation, density, layerStack);
        } catch (Exception e) {
            return null;
        }
    }

    public static DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            // fallback when displayInfo is null
            if (displayInfo == null) return getDisplayInfoFromDumpsysDisplay(displayId);
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int density = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            return new DisplayInfo(displayId, width, height, rotation, density, layerStack);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // 此处大量借鉴了 群友 @○_○ 所编写的易控车机版本相应功能
    public static VirtualDisplay createVirtualDisplay() throws Exception {
        DisplayInfo realDisplayinfo = getDisplayInfo(Display.DEFAULT_DISPLAY);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new Exception("Virtual display is not supported before Android 11");
        }

        int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;

        Surface surface = MediaCodec.createPersistentInputSurface();
        android.hardware.display.DisplayManager displayManager = android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class).newInstance(FakeContext.get());
        return displayManager.createVirtualDisplay("easycontrol", realDisplayinfo.width, realDisplayinfo.height, realDisplayinfo.density, surface, flags);
    }

    // 创建镜像主屏的DMS display：不带OWN_CONTENT_ONLY才会镜像主屏内容；
    // 不带TRUSTED(Android15对shell uid强制检查ADD_TRUSTED_DISPLAY权限会直接抛SecurityException)，
    // 与MediaProjection投屏同一路径，内容合成由系统保证。
    // Android15部分ROM上DisplayControl直建的shadow display无合成输出，此路径作为普通投屏的替代。
    // 不走DisplayManager(Context)构造：FakeContext(无base context)在Android15上调getSystemServiceName直接NPE，
    // 而systemMain().getSystemContext()在app_process里也不可用。直接反射DisplayManagerGlobal：
    // 其createVirtualDisplay只用context.getPackageName()（FakeContext可提供）。
    // 注意：VirtualDisplayConfig为Android15新增的Parcelable，老版本用Context构造路径兜底。
    public static VirtualDisplay createVirtualDisplayMirror(Surface surface, int width, int height, int density) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new Exception("Virtual display is not supported before Android 11");
        }
        // AUTO_MIRROR触发主屏镜像（DMS侧对PUBLIC+无OWN_CONTENT_ONLY会自动补上，这里显式声明）；
        // DESTROY_CONTENT_ON_REMOVAL确保断开时display被回收
        int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        if (Build.VERSION.SDK_INT >= 35) {
            mirrorDisplayId = createVirtualDisplayMirrorByBinder(surface, width, height, density, flags);
            return null; // 15+的display存活于DMS侧，仅持有callback句柄
        }
        android.hardware.display.DisplayManager displayManager = android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class).newInstance(FakeContext.get());
        return displayManager.createVirtualDisplay("easycontrol_mirror", width, height, density, surface, flags);
    }

    // Android15+：不走DisplayManagerGlobal.createVirtualDisplay（各ROM签名不一致），直接反射
    // IDisplayManager binder（固定签名：createVirtualDisplay(VirtualDisplayConfig,
    // IVirtualDisplayCallback, IMediaProjection, String)）。Builder默认displayIdToMirror=主屏。
    // 成功后保存callback供resize/release使用。返回displayId。
    public static int createVirtualDisplayMirrorByBinder(Surface surface, int width, int height, int density, int flags) throws Exception {
        Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dmg = dmgClass.getMethod("getInstance").invoke(null);
        java.lang.reflect.Field dmField = dmgClass.getDeclaredField("mDm");
        dmField.setAccessible(true);
        Object dm = dmField.get(dmg);
        // 构造VirtualDisplayConfig via Builder(name,width,height,density).setFlags().setSurface().build()
        Class<?> vdcClass = Class.forName("android.hardware.display.VirtualDisplayConfig");
        Class<?> builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Object builder = builderClass.getConstructor(String.class, int.class, int.class, int.class).newInstance("easycontrol_mirror", width, height, density);
        builder = builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
        builder = builderClass.getMethod("setSurface", android.view.Surface.class).invoke(builder, surface);
        Object config = builderClass.getMethod("build").invoke(builder);
        // VirtualDisplayCallback：只有(Callback, Executor)两参构造（均@Nullable可传null）
        Class<?> cbClass = Class.forName("android.hardware.display.DisplayManagerGlobal$VirtualDisplayCallback");
        Class<?> vdcCallbackClass = Class.forName("android.hardware.display.VirtualDisplay$Callback");
        java.lang.reflect.Constructor<?> cbCtor = cbClass.getDeclaredConstructor(vdcCallbackClass, java.util.concurrent.Executor.class);
        cbCtor.setAccessible(true);
        mirrorVirtualDisplayCallback = cbCtor.newInstance(null, null);
        // binder调用：IDisplayManager.createVirtualDisplay(config, callback, projectionToken=null, packageName)
        Class<?> idmClass = Class.forName("android.hardware.display.IDisplayManager");
        Class<?> cbIntfClass = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        Class<?> projectionClass = Class.forName("android.media.projection.IMediaProjection");
        Object displayIdObj = idmClass.getMethod("createVirtualDisplay", vdcClass, cbIntfClass, projectionClass, String.class)
                .invoke(dm, config, mirrorVirtualDisplayCallback, null, FakeContext.PACKAGE_NAME);
        int displayId = (Integer) displayIdObj;
        if (displayId < 0) throw new Exception("DMS returned displayId " + displayId);
        return displayId;
    }

    // DMS镜像display的callback句柄（用于后续resize/release）
    public static Object mirrorVirtualDisplayCallback;

    public static void resizeVirtualDisplayMirror(int width, int height, int density) throws Exception {
        if (mirrorVirtualDisplayCallback == null) return;
        Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dmg = dmgClass.getMethod("getInstance").invoke(null);
        Class<?> cbIntfClass = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        dmgClass.getMethod("resizeVirtualDisplay", cbIntfClass, int.class, int.class, int.class)
                .invoke(dmg, mirrorVirtualDisplayCallback, width, height, density);
    }

    public static void releaseVirtualDisplayMirror() {
        try {
            if (mirrorVirtualDisplayCallback == null) return;
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgClass.getMethod("getInstance").invoke(null);
            Class<?> cbIntfClass = Class.forName("android.hardware.display.IVirtualDisplayCallback");
            dmgClass.getMethod("releaseVirtualDisplay", cbIntfClass)
                    .invoke(dmg, mirrorVirtualDisplayCallback);
        } catch (Throwable ignored) {
        }
        mirrorVirtualDisplayCallback = null;
    }

    // DMS镜像display句柄（兼容字段，11-14路径持有VirtualDisplay对象）
    public static Object mirrorVirtualDisplay;
    public static int mirrorDisplayId = -1;
}
