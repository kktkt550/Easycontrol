package top.saymzx.easycontrol.app;

import android.content.ClipData;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import top.saymzx.easycontrol.app.databinding.ActivityLogBinding;
import top.saymzx.easycontrol.app.entity.AppData;
import top.saymzx.easycontrol.app.helper.PublicTools;
import top.saymzx.easycontrol.app.helper.ViewTools;

public class LogActivity extends AppCompatActivity {
    private ActivityLogBinding activityLogBinding;
    private Runnable refreshRunnable;
    private boolean autoScroll = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ViewTools.setStatusAndNavBar(this);
        ViewTools.setLocale(this);
        activityLogBinding = ActivityLogBinding.inflate(this.getLayoutInflater());
        setContentView(activityLogBinding.getRoot());
        activityLogBinding.backButton.setOnClickListener(v -> finish());
        activityLogBinding.copyButton.setOnClickListener(v -> copyLogs());
        activityLogBinding.clearButton.setOnClickListener(v -> {
            PublicTools.clearLogs();
            refreshLogs();
        });
        // 用户手动上滑查看历史时关闭自动滚动，滚到底部自动恢复
        activityLogBinding.logScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            ScrollView scrollView = activityLogBinding.logScroll;
            int diff = scrollView.getChildAt(0).getBottom() - (scrollView.getHeight() + scrollView.getScrollY());
            autoScroll = diff < 20;
        });
        refreshRunnable = this::refreshLogs;
        refreshLogs();
        AppData.uiHandler.postDelayed(refreshRunnable, 1000);
    }

    private void refreshLogs() {
        String logs = PublicTools.getLogs();
        TextView logText = activityLogBinding.logText;
        if (!logs.contentEquals(logText.getText())) logText.setText(logs);
        if (autoScroll) activityLogBinding.logScroll.post(() -> activityLogBinding.logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        AppData.uiHandler.postDelayed(refreshRunnable, 1000);
    }

    private void copyLogs() {
        AppData.clipBoard.setPrimaryClip(ClipData.newPlainText("easycontrol_logs", PublicTools.getLogs()));
        Toast.makeText(this, getString(R.string.toast_copy), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppData.uiHandler.removeCallbacks(refreshRunnable);
    }
}
