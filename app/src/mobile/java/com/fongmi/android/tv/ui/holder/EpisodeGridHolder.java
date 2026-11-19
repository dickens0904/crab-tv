package com.fongmi.android.tv.ui.holder;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.OfflineCache;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.offline.OfflineCacheManager;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.fongmi.android.tv.utils.ResUtil;

public class EpisodeGridHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeGridBinding binding;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void initView(Episode item) {
        binding.text.setSelected(item.isSelected());
        OfflineCache cache = OfflineCacheManager.get().find(item.getUrl());
        String badge = "";
        if (cache != null && cache.isDone()) badge = ResUtil.getString(R.string.offline_cache_badge_done);
        else if (cache != null && cache.isActive()) badge = ResUtil.getString(R.string.offline_cache_badge_active);
        binding.text.setText(badge + item.getDesc().concat(item.getName()));
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }
}
