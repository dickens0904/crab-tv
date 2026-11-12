package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.SiteManager;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteBinding;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private boolean search;
    private boolean change;
    private boolean manage;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        search = true;
        return this;
    }

    public SiteDialog change() {
        change = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof SiteListener) listener = (SiteListener) fragment;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setNeutralButton(manage ? R.string.dialog_done : R.string.dialog_manage, (d, w) -> toggleManage());
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this);
        binding.recycler.setAdapter(adapter);
        adapter.search(search).change(change).manage(manage);
        binding.recycler.post(this::bindManageButton);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(VodConfig.getHomeIndex()));
    }

    @Override
    public void onTextClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onSearchClick(int position, Site item) {
        item.setSearchable(!item.isSearchable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onChangeClick(int position, Site item) {
        item.setChangeable(!item.isChangeable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public boolean onSearchLongClick(Site item) {
        boolean result = !item.isSearchable();
        adapter.getItems().forEach(site -> site.setSearchable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onChangeLongClick(Site item) {
        boolean result = !item.isChangeable();
        adapter.getItems().forEach(site -> site.setChangeable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else if (ResUtil.isLand(requireContext())) setWidth(0.5f);
    }

    private void bindManageButton() {
        if (getDialog() == null) return;
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) getDialog();
        android.widget.Button btn = d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL);
        if (btn != null) {
            btn.setText(manage ? R.string.dialog_done : R.string.dialog_manage);
            // 接管点击：neutral 按钮默认 onClick 后自动 dismiss，会让管理态直接丢弹窗
            btn.setOnClickListener(v -> toggleManage());
        }
    }

    private void toggleManage() {
        manage = !manage;
        adapter.manage(manage);
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        bindManageButton();
    }

    @Override
    public void onDeleteClick(int position, Site item) {
        SiteManager.hide(item.getKey());
        VodConfig.get().getSites().remove(item);
        adapter.getItems().remove(position);
        adapter.notifyItemRemoved(position);
        adapter.notifyItemRangeChanged(position, adapter.getItemCount() - position);
        RefreshEvent.home();
    }

    @Override
    public void onUpClick(int position, Site item) {
        if (position <= 0) return;
        SiteManager.moveUp(item.getKey(), adapter.getItems());
        java.util.Collections.swap(adapter.getItems(), position, position - 1);
        java.util.Collections.swap(VodConfig.get().getSites(), VodConfig.get().getSites().indexOf(item), VodConfig.get().getSites().indexOf(item) - 1);
        adapter.notifyItemMoved(position, position - 1);
        adapter.notifyItemRangeChanged(position - 1, 2);
        RefreshEvent.home();
    }

    @Override
    public void onDownClick(int position, Site item) {
        if (position >= adapter.getItemCount() - 1) return;
        SiteManager.moveDown(item.getKey(), adapter.getItems());
        java.util.Collections.swap(adapter.getItems(), position, position + 1);
        java.util.Collections.swap(VodConfig.get().getSites(), VodConfig.get().getSites().indexOf(item), VodConfig.get().getSites().indexOf(item) + 1);
        adapter.notifyItemMoved(position, position + 1);
        adapter.notifyItemRangeChanged(position, 2);
        RefreshEvent.home();
    }
}