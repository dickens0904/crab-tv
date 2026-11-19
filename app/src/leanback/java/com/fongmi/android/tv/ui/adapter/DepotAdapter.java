package com.fongmi.android.tv.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.GitHubDepotSearch;
import com.fongmi.android.tv.databinding.AdapterDepotBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DepotAdapter extends RecyclerView.Adapter<DepotAdapter.ViewHolder> {

    private final List<GitHubDepotSearch.Result> mItems = new ArrayList<>();

    public void setItems(List<GitHubDepotSearch.Result> items) {
        mItems.clear();
        if (items != null) mItems.addAll(items);
        Collections.sort(mItems, (a, b) -> Boolean.compare(b.pass, a.pass));
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDepotBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        GitHubDepotSearch.Result item = mItems.get(position);
        holder.binding.name.setText(item.name);
        holder.binding.url.setText(item.url);
        holder.binding.tag.setText(item.pass ? item.message : "✕ " + item.message);
        holder.binding.tag.setAlpha(item.pass ? 1f : 0.6f);
        bindType(holder, item);
    }

    /**
     * 源类型标签：接口（绿，可用）/ 爬虫依赖（灰，大概率因缺 X5 空栏目）/ 多仓待展开（暗灰，不作承诺）。
     * 判定不了的一律降级成「待展开」，不猜——猜错比不猜更坑人。
     */
    private void bindType(ViewHolder holder, GitHubDepotSearch.Result item) {
        int color;
        int text;
        if (item.needsJar() == null) {
            color = R.color.depot_type_unknown;
            text = R.string.depot_type_unknown;
        } else if (item.needsJar()) {
            color = R.color.depot_type_jar;
            text = R.string.depot_type_jar;
        } else {
            color = R.color.depot_type_api;
            text = R.string.depot_type_api;
        }
        Context context = holder.binding.getRoot().getContext();
        holder.binding.type.setText(text);
        holder.binding.type.setTextColor(ContextCompat.getColor(context, color));
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterDepotBinding binding;

        public ViewHolder(@NonNull AdapterDepotBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}