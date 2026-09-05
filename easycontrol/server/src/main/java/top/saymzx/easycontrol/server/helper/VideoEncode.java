/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package top.saymzx.easycontrol.server.helper;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.ErrnoException;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.Objects;

import top.saymzx.easycontrol.server.Server;
import top.saymzx.easycontrol.server.entity.Device;
import top.saymzx.easycontrol.server.entity.Options;
import top.saymzx.easycontrol.server.wrappers.DisplayManager;
import top.saymzx.easycontrol.server.wrappers.SurfaceControl;
import top.saymzx.easycontrol.server.wrappers.WindowManager;

public final class VideoEncode {
    private static MediaCodec encedec;
    private static MediaFormat encodecFormat;
    public static boolean isHasChangeConfig = false;
    private static boolean useH265;

    // 编码器自愈：部分ROM(Android15小米8等)的编码器/display合成链路会出现
    // "Pending dequeue output buffer request cancelled"等异常或长时间无帧输出(与scrcpy issue#4265同类)。
    // 自愈矩阵：display路径(DMS mirror/shadow) × 编码器(硬件H265/硬件H264/软件)全轮换，全失败才拆会话。
    // 诊断日志统一走 System.out（会被 shell 输出捕获，客户端日志窗口可见）
    private static int restartCount = 0;
    private static boolean usingSwEncoder = false;

    // DMS镜像display路径(Android11+普通投屏)：DMS管理内容合成，surface为persistent input surface
    private static android.hardware.display.VirtualDisplay dmsDisplay;
    private static Surface persistentSurface;
    private static boolean useDmsMirror;

    private static IBinder display;

    // 帧输出看门狗：display合成/编码器链路死掉时无任何输出（含csd），超时触发自愈。
    // 15秒给WMS侧建立ContentRecording会话（AUTO_MIRROR的镜像机制）留足时间，
    // 太短会导致会话刚建立就被销毁重建、永远无法出帧
    private static final long NO_OUTPUT_TIMEOUT_MS = 15000;
    private static long lastOutputElapsed;

    private static void log(String msg) {
        System.out.println("[EC] " + msg);
        // 实时回传给主控端日志窗口（连接活着时也能看到诊断）
        try {
            ControlPacket.sendLogEvent(msg);
        } catch (Exception ignored) {
        }
    }

    public static void init() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
        useH265 = Options.supportH265 && EncodecTools.isSupportH265();
        restartCount = 0;
        usingSwEncoder = false;
        // 协议: 0=H264, 1=H265, 2=AV1（保留扩展，当前仅支持H264/H265）
        ByteBuffer byteBuffer = ByteBuffer.allocate(9);
        byteBuffer.put((byte) (useH265 ? 1 : 0));
        byteBuffer.putInt(Device.videoSize.first);
        byteBuffer.putInt(Device.videoSize.second);
        byteBuffer.flip();
        Server.writeVideo(byteBuffer);
        // 创建显示器。两条路径：
        // DMS mirror(公开API)：与MediaProjection投屏同路径，内容合成由系统保证；
        // shadow display(DisplayControl直建)：scrcpy同款路径，支持息屏投屏，但部分ROM无合成输出。
        // 默认Android11+普通投屏走DMS。单应用投屏只能走shadow。
        // 时序关键：SF在processDisplayAdded时查询surface尺寸激活虚拟屏，persistent input surface
        // 必须先被编码器configure+setInputSurface才有尺寸，否则SF以0x0激活后无帧。
        // 正确顺序：createEncodecFormat -> configure+setInputSurface -> createDisplay -> start
        useDmsMirror = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Objects.equals(Options.startApp, "");
        if (useDmsMirror) {
            // 先创建persistent surface并让编码器认领（configure+setInputSurface）
            try {
                if (persistentSurface == null) persistentSurface = MediaCodec.createPersistentInputSurface();
                createEncodecFormat(null);
                startEncodeDms();
            } catch (Exception e) {
                log("DMS前置失败: " + e + "，回退shadow路径");
                useDmsMirror = false;
                if (persistentSurface != null) {
                    try {
                        persistentSurface.release();
                    } catch (Throwable ignored) {
                    }
                    persistentSurface = null;
                }
                if (encedec != null) {
                    try {
                        encedec.release();
                    } catch (Throwable ignored) {
                    }
                    encedec = null;
                }
            }
        }
        if (useDmsMirror) {
            // surface已有尺寸，现在创建display让SF以正确尺寸激活
            useDmsMirror = setupDmsDisplay();
        }
        if (!useDmsMirror) {
            if (persistentSurface != null) {
                try {
                    persistentSurface.release();
                } catch (Throwable ignored) {
                }
                persistentSurface = null;
            }
            if (encedec != null) {
                try {
                    encedec.release();
                } catch (Throwable ignored) {
                }
                encedec = null;
            }
            setupShadowDisplay();
            createEncodecFormat(null);
            startEncode();
        }
        log("init: path=" + (useDmsMirror ? "DMS" : "shadow") + " sdk=" + Build.VERSION.SDK_INT + " h265=" + useH265);
    }

    private static boolean setupDmsDisplay() {
        try {
            // Android15+global路径：surface已被编码器认领（有尺寸），此时创建display
            // SF才能以正确尺寸激活
            if (Build.VERSION.SDK_INT >= 35) {
                int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL_PUBLIC;
                DisplayManager.mirrorDisplayId = DisplayManager.createVirtualDisplayMirrorByBinder(persistentSurface, Device.videoSize.first, Device.videoSize.second, Device.displayInfo.density, flags);
                if (DisplayManager.mirrorDisplayId < 0) return false;
                // 强制WMS建立主屏->虚拟屏的ContentRecording会话（息屏/无窗口状态下
                // updateRecording不会被自动触发）
                WindowManager.startContentRecording(DisplayManager.mirrorDisplayId, 0 /* Display.DEFAULT_DISPLAY */);
                return true;
            }
            VirtualDisplay vd = DisplayManager.createVirtualDisplayMirror(persistentSurface, Device.videoSize.first, Device.videoSize.second, Device.displayInfo.density);
            if (vd != null) {
                dmsDisplay = vd;
                return true;
            }
            return false;
        } catch (Throwable e) {
            log("setupDmsDisplay failed: " + e);
            dmsDisplay = null;
            return false;
        }
    }

    private static void setupShadowDisplay() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        display = SurfaceControl.createDisplay("easycontrol", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S".equals(Build.VERSION.CODENAME)));
    }

    // 释放当前display资源，切到另一条路径。
    private static boolean switchDisplayPath() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        if (useDmsMirror) {
            // DMS -> shadow
            releaseDmsDisplay();
            setupShadowDisplay();
            useDmsMirror = false;
        } else {
            // shadow -> DMS
            try {
                if (display != null) SurfaceControl.destroyDisplay(display);
            } catch (Exception ignored) {
            }
            display = null;
            useDmsMirror = setupDmsDisplay();
            if (!useDmsMirror) {
                setupShadowDisplay();
                return false;
            }
        }
        return true;
    }

    private static void releaseDmsDisplay() {
        DisplayManager.releaseVirtualDisplayMirror();
        try {
            if (persistentSurface != null) {
                persistentSurface.release();
                persistentSurface = null;
            }
        } catch (Throwable ignored) {
        }
        dmsDisplay = null;
        DisplayManager.mirrorDisplayId = -1;
    }

    private static void createEncodecFormat(String codecName) throws IOException {
        String codecMime = useH265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        if (codecName == null) encedec = MediaCodec.createEncoderByType(codecMime);
        else encedec = MediaCodec.createByCodecName(codecName);
        encodecFormat = new MediaFormat();
        encodecFormat.setString(MediaFormat.KEY_MIME, codecMime);
        encodecFormat.setInteger(MediaFormat.KEY_BIT_RATE, Options.maxVideoBit);
        // must be present to configure the encoder, but does not impact the actual frame rate, which is variable
        encodecFormat.setInteger(MediaFormat.KEY_FRAME_RATE, Options.maxFps);
        encodecFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        // display the very first frame, and recover from bad quality when no new frames
        encodecFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        encodecFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // 编码器参数与scrcpy对齐：不设置KEY_PRIORITY/KEY_LATENCY/KEY_INTRA_REFRESH_PERIOD/max-fps-to-encoder，
        // 这些非必需键在部分ROM(小米8/Android15)的硬件及软件编码器上都会导致输出异常
    }

    // 编码器出错/无输出后的自愈：display路径与编码器全矩阵轮换。
    // 全部失败返回false，由调用方拆会话。重启成功后发送流重启标记，客户端重建解码器。
    public static boolean restartAfterError(String reason) {
        String oldCodecName = null;
        try {
            oldCodecName = encedec.getName();
        } catch (Exception ignored) {
        }
        log("restart #" + (restartCount + 1) + " reason=" + reason + " path=" + (useDmsMirror ? "DMS" : "shadow") + " codec=" + oldCodecName + " h265=" + useH265 + " sw=" + usingSwEncoder);
        // 首次无帧时dump系统内display真实状态（是否存在/是否被DMS认作mirror/电源状态），
        // 确认WMS ContentRecording会话是否建立
        if (restartCount == 0 && useDmsMirror) {
            try {
                String d = Device.execReadOutput("dumpsys display | grep -iE 'easycontrol|mState=|mDisplayId=[0-9]' | head -40");
                log("display诊断:\n" + d);
            } catch (Throwable ignored) {
            }
            try {
                String w = Device.execReadOutput("dumpsys window displays | grep -iE 'easycontrol|mDisplayId=[0-9]|DisplayContent' | head -20");
                log("window诊断:\n" + w);
            } catch (Throwable ignored) {
            }
            try {
                String sf = Device.execReadOutput("dumpsys SurfaceFlinger --display-id");
                log("SF display-id:\n" + sf);
            } catch (Throwable ignored) {
            }
            try {
                String rec = Device.execReadOutput("dumpsys window | grep -iE 'recording' | head -10");
                log("recording会话:\n" + rec);
            } catch (Throwable ignored) {
            }
        }
        try {
            stopEncode();
            encedec.release();
        } catch (Exception ignored) {
        }
        restartCount++;
        String nextCodec = null;
        boolean switchPath = false;
        // 自愈矩阵：1-2同路径同编码器；3起循环轮换 display路径 × 编码器组合，永不放弃（保持连接不断，
        // 每轮换到有效组合后画面恢复，同时实时日志会让主控端看到每个组合的尝试结果）
        if (restartCount <= 2) {
            // 第一阶段：同路径同编码器重启（瞬时错误重试常可恢复）
        } else {
            switchPath = true;
            switch ((restartCount - 3) % 4) {
                case 0: // 换display路径，编码器不变
                    break;
                case 1: // 换display路径 + 换其它硬件编码器（无其它硬件编码器时直接降级H264）
                    nextCodec = EncodecTools.getNextVideoEncoder(useH265);
                    if (nextCodec == null && useH265) useH265 = false;
                    break;
                case 2: // 换display路径 + H265降级H264
                    if (useH265) useH265 = false;
                    break;
                default: // 换display路径 + 软件编码器
                    usingSwEncoder = true;
                    nextCodec = "c2.android.avc.encoder";
                    break;
            }
        }
        try {
            if (switchPath) switchDisplayPath();
            // DMS路径：编码器/流程全重置后，按正确时序重建（先configure+setInputSurface，
            // 再创建display让SF以有效尺寸激活，最后建立ContentRecording会话）
            if (useDmsMirror) {
                DisplayManager.releaseVirtualDisplayMirror();
                DisplayManager.mirrorDisplayId = -1;
                createEncodecFormat(nextCodec);
                startEncodeDms();
                setupDmsDisplay();
            } else {
                createEncodecFormat(nextCodec);
                startEncode();
            }
            // 发送流重启标记(size=-1)+新流头，客户端收到后重建解码器；随后编码器会输出新的csd帧
            ByteBuffer byteBuffer = ByteBuffer.allocate(13);
            byteBuffer.putInt(-1);
            byteBuffer.put((byte) (useH265 ? 1 : 0));
            byteBuffer.putInt(Device.videoSize.first);
            byteBuffer.putInt(Device.videoSize.second);
            byteBuffer.flip();
            Server.writeVideo(byteBuffer);
            lastOutputElapsed = android.os.SystemClock.elapsedRealtime();
            log("restarted ok: path=" + (useDmsMirror ? "DMS" : "shadow") + " codec=" + nextCodec);
            return true;
        } catch (Exception e) {
            log("restart failed: " + e);
            return false;
        }
    }

    // 初始化编码器
    private static Surface surface;

    // DMS路径第一阶段：configure编码器+认领persistent surface+start。
    // 注意：必须在display创建之前执行——SF在processDisplayAdded时查询surface尺寸，
    // persistent surface需先被编码器认领才有尺寸，否则SF以0x0激活虚拟屏后无帧。
    // display创建完成后再调startEncodeDmsAfterDisplay()。
    private static void startEncodeDms() throws IOException {
        ControlPacket.sendVideoSizeEvent();
        encodecFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
        encodecFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
        // 编码器的输入surface只能通过setInputSurface设置persistent input surface——
        // configure的surface参数是解码器的输出surface，对编码器传surface会直接IllegalArgumentException
        encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encedec.setInputSurface(persistentSurface);
        encedec.start();
    }

    public static void startEncode() throws InvocationTargetException, NoSuchMethodException, IllegalAccessException, IOException, ErrnoException {
        if (useDmsMirror) {
            // DMS路径：display已在init时创建，编码器已start；这里只需尺寸跟随
            try {
                DisplayManager.resizeVirtualDisplayMirror(Device.videoSize.first, Device.videoSize.second, Device.displayInfo.density);
            } catch (Throwable ignored) {
            }
            return;
        }
        ControlPacket.sendVideoSizeEvent();
        encodecFormat.setInteger(MediaFormat.KEY_WIDTH, Device.videoSize.first);
        encodecFormat.setInteger(MediaFormat.KEY_HEIGHT, Device.videoSize.second);
        encedec.configure(encodecFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // 绑定Display和Surface
        surface = encedec.createInputSurface();
        setDisplaySurface(display, surface);
        // 启动编码
        encedec.start();
    }

    public static void stopEncode() {
        try {
            encedec.stop();
        } catch (Exception ignored) {
        }
        try {
            encedec.reset();
        } catch (Exception ignored) {
        }
        if (!useDmsMirror && surface != null) {
            try {
                surface.release();
            } catch (Exception ignored) {
            }
            surface = null;
        }
    }

    private static void setDisplaySurface(IBinder display, Surface surface) throws InvocationTargetException, NoSuchMethodException, IllegalAccessException {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, new Rect(0, 0, Device.displayInfo.width, Device.displayInfo.height), new Rect(0, 0, Device.videoSize.first, Device.videoSize.second));
            SurfaceControl.setDisplayLayerStack(display, Device.displayInfo.layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    // 客户端开始录屏时请求立即输出关键帧，避免等最长10秒的I帧周期；部分编码器在
    // 开启INTRA_REFRESH时忽略该请求，客户端也有"等首关键帧"的兜底，此处尽力而为
    public static void requestSyncFrame() {
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encedec.setParameters(params);
        } catch (Exception ignored) {
        }
    }

    // 供截图使用：返回投屏shadow display令牌，captureDisplay可截其合成输出(息屏/折叠也能拿到画面)
    // DMS镜像路径下返回null，截图回退到screencap
    public static IBinder getCaptureDisplay() {
        return useDmsMirror ? null : display;
    }

    private static final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    public static void encodeOut() throws IOException {
        // 用有限超时轮询而非无限阻塞：长时间无输出（display合成层无帧）时能触发自愈，
        // 否则线程永远阳塞在dequeueOutputBuffer里无法自救；
        // 编解码器出错向上抛由 executeVideoOut 尝试 restartAfterError 自愈
        int outIndex = encedec.dequeueOutputBuffer(bufferInfo, 300_000);
        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // REPEAT_PREVIOUS_FRAME_AFTER保证静止画面也每100ms出帧，超过5秒无任何输出
            // （含csd）说明display合成或编码链路死亡，触发自愈轮换
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastOutputElapsed == 0) lastOutputElapsed = now;
            else if (now - lastOutputElapsed > NO_OUTPUT_TIMEOUT_MS)
                throw new IllegalStateException("no video output for " + (now - lastOutputElapsed) + "ms");
            return;
        }
        if (outIndex < 0) {
            // INFO_OUTPUT_FORMAT_CHANGED/INFO_OUTPUT_BUFFERS_CHANGED：算作有效输出（csd即在此后跟随）
            lastOutputElapsed = android.os.SystemClock.elapsedRealtime();
            return;
        }
        ByteBuffer buffer = encedec.getOutputBuffer(outIndex);
        if (buffer == null) return;
        lastOutputElapsed = android.os.SystemClock.elapsedRealtime();
        ControlPacket.sendVideoEvent(bufferInfo.presentationTimeUs, buffer);
        encedec.releaseOutputBuffer(outIndex, false);
    }

    public static void release() {
        try {
            stopEncode();
            encedec.release();
        } catch (Exception ignored) {
        }
        try {
            if (dmsDisplay != null) {
                dmsDisplay.release();
                dmsDisplay = null;
            }
            DisplayManager.releaseVirtualDisplayMirror();
            if (persistentSurface != null) {
                persistentSurface.release();
                persistentSurface = null;
            }
            if (display != null) SurfaceControl.destroyDisplay(display);
        } catch (Exception ignored) {
        }
    }

}
