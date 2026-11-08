package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.GitHubDepotSearch;
import com.fongmi.android.tv.api.RecommendedSource;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.ActivityDepotBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.DepotAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 首页「发现多仓」：搜 GitHub 多仓配置 → 测连通 → 通过的自动进 Config 表。
 * 只新增这一条搜索→进源旁路，不改 VodConfig / LiveConfig 既有解析。
 */
public class DepotSearchActivity extends BaseActivity {

    private ActivityDepotBinding mBinding;
    private DepotAdapter mAdapter;
    private boolean running;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DepotSearchActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDepotBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        mBinding.title.setText(R.string.depot_title);
        mBinding.status.setText(R.string.depot_idle);
    }

    @Override
    protected void initEvent() {
        mBinding.search.setOnClickListener(v -> search());
        mBinding.recommend.setOnClickListener(v -> recommend());
    }

    /** 推荐源入口：弹列表，点击即 {@code Config.find(url, type)} + load，和配置对话框「确定」同一路径。 */
    private void recommend() {
        CharSequence[] items = new CharSequence[RecommendedSource.size()];
        for (int i = 0; i < items.length; i++) items[i] = getString(RecommendedSource.nameRes(i));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.depot_rec_title)
                .setItems(items, (dialog, which) -> loadRecommended(which))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void loadRecommended(int index) {
        Config config = RecommendedSource.find(index);
        Notify.progress(this);
        mBinding.status.setText(R.string.depot_rec_loading);
        com.fongmi.android.tv.impl.Callback callback = new com.fongmi.android.tv.impl.Callback() {
            @Override
            public void start() {
                Notify.progress(DepotSearchActivity.this);
            }

            @Override
            public void success() {
                Notify.dismiss();
                Notify.show(R.string.depot_rec_loaded);
                mBinding.status.setText(R.string.depot_rec_loaded);
                ConfigEvent.vod();
                ConfigEvent.live();
                RefreshEvent.home();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                if (!TextUtils.isEmpty(msg)) Notify.show(msg);
                mBinding.status.setText(msg);
            }
        };
        if (config.getType() == GitHubDepotSearch.VOD) VodConfig.load(config, callback);
        else LiveConfig.load(config, callback);
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setItemAnimator(null);
        mBinding.recycler.setAdapter(mAdapter = new DepotAdapter());
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.addItemDecoration(new SpaceItemDecoration(12));
    }

    private void search() {
        if (running) return;
        running = true;
        mBinding.search.setEnabled(false);
        mBinding.progressLayout.showProgress();
        mBinding.status.setText(R.string.depot_searching);
        GitHubDepotSearch.search(new GitHubDepotSearch.Listener() {
            @Override
            public void onProgress(String stage, int done, int total) {
                mBinding.status.setText(stageText(stage, done, total));
            }

            @Override
            public void onDone(GitHubDepotSearch.Summary summary) {
                running = false;
                mBinding.search.setEnabled(true);
                mBinding.progressLayout.showContent();
                showResult(summary);
            }
        });
    }

    /** 把搜索阶段的进度映射为三语文案；未知阶段保持上一条不动。 */
    private String stageText(String stage, int done, int total) {
        if (GitHubDepotSearch.STAGE_SEARCH.equals(stage)) return getString(R.string.depot_stage_search_progress, done, total);
        if (GitHubDepotSearch.STAGE_PROBE.equals(stage)) return getString(R.string.depot_stage_probe, done, total);
        if (GitHubDepotSearch.STAGE_ADD.equals(stage)) return getString(R.string.depot_stage_add, done);
        return mBinding.status.getText().toString();
    }

    private void showResult(GitHubDepotSearch.Summary summary) {
        mAdapter.setItems(summary.results);
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.depot_summary, summary.added, summary.pass, summary.fail, summary.duplicate));
        if (summary.limited) text.append('\n').append(getString(R.string.depot_limited));
        if (summary.timeout) text.append('\n').append(getString(R.string.depot_timeout));
        if (summary.candidates == 0) text.append('\n').append(getString(R.string.depot_empty));
        if (!TextUtils.isEmpty(summary.error)) text.append('\n').append(summary.error);
        mBinding.status.setText(text.toString());
        Notify.show(text.toString());
        // 走既有 event 机制让首页立即反映新增的源。
        if (summary.added > 0) {
            ConfigEvent.vod();
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (running) mBinding.search.setEnabled(true);
        super.onBackInvoked();
    }
}