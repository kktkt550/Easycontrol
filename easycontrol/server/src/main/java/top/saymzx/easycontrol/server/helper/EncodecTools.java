package top.saymzx.easycontrol.server.helper;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import java.util.ArrayList;
import java.util.Objects;

public class EncodecTools {
    private static ArrayList<String> hevcEncodecList = null;
    private static ArrayList<String> avcEncodecList = null;
    private static ArrayList<String> opusEncodecList = null;

    // 获取解码器列表
    private static void getEncodecList() {
        MediaCodecList mediaCodecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        hevcEncodecList = new ArrayList<>();
        avcEncodecList = new ArrayList<>();
        opusEncodecList = new ArrayList<>();
        for (MediaCodecInfo mediaCodecInfo : mediaCodecList.getCodecInfos()) {
            if (mediaCodecInfo.isEncoder()) {
                String codecName = mediaCodecInfo.getName();
                if (codecName.toLowerCase().contains("opus")) opusEncodecList.add(codecName);
                // 要求硬件实现
                if (!codecName.startsWith("OMX.google") && !codecName.startsWith("c2.android")) {
                    for (String supportType : mediaCodecInfo.getSupportedTypes()) {
                        if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_HEVC))
                            hevcEncodecList.add(codecName);
                        else if (Objects.equals(supportType, MediaFormat.MIMETYPE_VIDEO_AVC))
                            avcEncodecList.add(codecName);
                    }
                }
            }
        }
    }

    // 获取解码器是否支持
    public static boolean isSupportOpus() {
        if (opusEncodecList == null) getEncodecList();
        return opusEncodecList.size() > 0;
    }

    public static boolean isSupportH265() {
        if (hevcEncodecList == null) getEncodecList();
        return hevcEncodecList.size() > 0;
    }

    // 取除默认(createEncoderByType所选)外的其它硬件编码器名，供编码器出错时切换；没有则返回null
    public static String getNextVideoEncoder(boolean h265) {
        if (h265) {
            if (hevcEncodecList == null) getEncodecList();
            return hevcEncodecList.size() > 0 ? hevcEncodecList.get(0) : null;
        }
        if (avcEncodecList == null) getEncodecList();
        return avcEncodecList.size() > 0 ? avcEncodecList.get(0) : null;
    }

}
