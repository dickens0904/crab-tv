package com.fongmi.android.tv.ui.fragment;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.presenter.FilterPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.VodListCache;
import com.fongmi.android.tv.utils.RatingEnricher;
import com.fongmi.android.tv.utils.VodSort;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodPresenter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private ArrayObjectAdapter mAdapter;
    private ArrayObjectAdapter mLast;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private List<Filter> mFilters;
    private boolean headerVisible;
    private boolean filterVisible;
    // 2026-11-12: 分栏内跨页全局排序（与 mobile 同修）。仅在用户启用排序时启用，
    // 默认模式保持原有按页追加逻辑，避免改变电视端焦点行为。
    private final VodSort.Accumulator mSorter = new VodSort.Accumulator();
    // 缓存 + 增量探针（与 mobile 同）：恢复全量列表后拿第 1 页比对是否新增。
    private String mCacheKey;
    private int mCachedPageCount;
    private boolean mProbing;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder) {
        Bundle args = new Bundle();
        args.putString("key", key);
        args.putString("typeId", typeId);
        args.putBoolean("folder", folder);
        args.putParcelable("style", style);
        args.putSerializable("extend", extend);
        TypeFragment fragment = new TypeFragment();
        fragment.setArguments(args);
        return fragment;
    }

    private String getKey() {
        return getArguments().getString("key");
    }

    private String getTypeId() {
        return getArguments().getString("typeId");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private List<Filter> getFilter() {
        return Cache.copy(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return ((FolderFragment) getParentFragment());
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        mFilters = getFilter();
        setRecyclerView();
        setViewModel();
        setFilters();
        initCacheOrLoad();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(8, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED), FilterPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setHeader(getActivity(), R.id.recycler);
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void setFilters() {
        for (Filter filter : mFilters) {
            if (mExtends.containsKey(filter.getKey())) {
                filter.setSelected(mExtends.get(filter.getKey()));
            }
        }
    }

    private void setClick(ArrayObjectAdapter adapter, String key, Value item) {
        for (int i = 0; i < adapter.size(); i++) ((Value) adapter.get(i)).setSelected(item);
        adapter.notifyArrayItemRangeChanged(0, adapter.size());
        if (item.isSelected()) mExtends.put(key, item.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    /**
     * 2026-11-12: 命中缓存先渲染全量列表并恢复页码，再请求第 1 页作增量探针。
     * 无新增则完全不动（保留电视端焦点与已滚动位置），有新增才重建内容区。
     */
    private void initCacheOrLoad() {
        mCacheKey = VodListCache.key(getKey(), getTypeId(), Setting.getVodSort(), mExtends);
        VodListCache.Entry cached = VodListCache.get(mCacheKey);
        if (cached == null) {
            getVideo();
            return;
        }
        mSorter.restore(cached.items, cached.sortMode);
        mScroller.restore(cached.page, cached.pageCount);
        mCachedPageCount = cached.pageCount;
        List<Vod> all = mSorter.snapshot();
        RatingEnricher.backfill(all);                 // 渲染前回填缓存评分
        mBinding.progressLayout.showContent(true, all.size());
        renderAll(all, getStyle());
        RatingEnricher.enrich(all, this::onEnriched);  // 后台补全当前列表未命中条目
        mProbing = true;
        getVideo(getTypeId(), "1");
    }

    private void onProbeResult(Result result) {
        mProbing = false;
        mBinding.swipeLayout.setRefreshing(false);
        if (result.getPageCount() > 0) mCachedPageCount = result.getPageCount();
        List<Vod> before = mSorter.snapshot();
        List<Vod> after = mSorter.append(result);
        if (after.size() <= before.size()) return;
        renderAll(after, result.getStyle(getStyle()));
        VodListCache.put(mCacheKey, after, mScroller.getPage(), mCachedPageCount, Setting.getVodSort());
    }

    private void getVideo() {
        mCacheKey = VodListCache.key(getKey(), getTypeId(), Setting.getVodSort(), mExtends);
        VodListCache.invalidate(mCacheKey);
        mProbing = false;
        mLast = null;
        mSorter.reset();
        checkFilter();
        mScroller.reset();
        getVideo(getTypeId(), "1");
    }

    private void getVideo(String typeId, String page) {
        mViewModel.categoryContent(getKey(), typeId, page, true, mExtends);
    }

    private void setAdapter(Result result) {
        if (mProbing) {
            onProbeResult(result);
            return;
        }
        boolean first = mScroller.first();
        boolean flag = mExtends.isEmpty();
        int size = result.getList().size();
        mBinding.progressLayout.showContent(first & flag, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        Style style = result.getStyle(getStyle());
        // 无论哪种排序模式都维护累积器：DEFAULT 也需要它来写缓存。
        RatingEnricher.backfill(result.getList());     // 本页先回填，保证排序用到最新分
        List<Vod> all = mSorter.append(result);
        if (mSorter.isDefault()) {
            // 默认（不排序）：沿用原有按页追加逻辑，电视端焦点行为不变。
            if (style.isList()) mAdapter.addAll(mAdapter.size(), VodSort.sorted(result));
            else addGrid(VodSort.sorted(result), style);
        } else {
            // 排序模式：全量重建，保证任意时刻都是全局有序而非一段一段。
            renderAll(all, style);
        }
        checkMore();
        VodListCache.put(mCacheKey, all, mScroller.getPage(), result.getPageCount(), Setting.getVodSort());
        RatingEnricher.enrich(result.getList(), null);   // 本页未命中条目后台补全（下次加载见效）
    }

    /** 后台补全完成后：重新回填并（评分排序模式下）重排，使新拿到的分立即生效。 */
    private void onEnriched() {
        try {
            List<Vod> all = mSorter.snapshot();
            RatingEnricher.backfill(all);
            if (!mSorter.isDefault()) renderAll(all, getStyle());
        } catch (Throwable ignored) {
        }
    }

    /** 用「全量有序列表」重建内容区（保留筛选行）；缓存恢复与增量更新共用。 */
    private void renderAll(List<Vod> all, Style style) {
        int start = filterVisible ? mFilters.size() : 0;
        if (mAdapter.size() > start) mAdapter.removeItems(start, mAdapter.size() - start);
        mLast = null;
        if (style.isList()) {
            mAdapter.addAll(start, all);
            return;
        }
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(all, Product.getColumn(style))) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(start, rows);
    }

    private void checkMore() {
        if (mScroller.isDisable() || mAdapter.size() >= 5) return;
        mScroller.checkMore();
    }

    private boolean checkLastSize(List<Vod> items, Style style) {
        if (mLast == null || items.isEmpty()) return false;
        int size = Product.getColumn(style) - mLast.size();
        if (size == 0) return false;
        size = Math.min(size, items.size());
        mLast.addAll(mLast.size(), items.subList(0, size));
        addGrid(items.subList(size, items.size()), style);
        return true;
    }

    private void addGrid(List<Vod> items, Style style) {
        if (checkLastSize(items, style)) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            mLast = new ArrayObjectAdapter(presenter);
            mLast.addAll(0, part);
            rows.add(new ListRow(mLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private ListRow getRow(Filter filter) {
        FilterPresenter presenter = new FilterPresenter(filter.getKey());
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        presenter.setOnClickListener((key, item) -> setClick(adapter, key, item));
        adapter.setItems(filter.getValue(), null);
        return new ListRow(adapter);
    }

    private void showFilter() {
        List<ListRow> rows = new ArrayList<>();
        for (Filter filter : mFilters) rows.add(getRow(filter));
        mBinding.recycler.postDelayed(() -> mBinding.recycler.scrollToPosition(0), 48);
        mAdapter.addAll(0, rows);
    }

    private void hideFilter() {
        mAdapter.removeItems(0, mFilters.size());
    }

    public void toggleFilter(boolean visible) {
        if (mFilters.isEmpty()) return;
        this.filterVisible = visible;
        if (visible) showFilter();
        else hideFilter();
    }

    private void checkFilter() {
        int adapterSize = mAdapter.size();
        int filterSize = filterVisible ? mFilters.size() : 0;
        if (adapterSize > filterSize) mAdapter.removeItems(filterSize, mAdapter.size() - filterSize);
        if (adapterSize == 0) mBinding.progressLayout.showProgress();
        else mBinding.swipeLayout.setRefreshing(true);
    }

    public void onRefresh() {
        getVideo();
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
            headerVisible = mBinding.recycler.isHeaderVisible();
        } else {
            if (getSite().isIndex()) CollectActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        CollectActivity.start(requireActivity(), item.getName());
        return true;
    }

    /**
     * 当前栏已加载列表的评分覆盖率：{有评分条数, 总条数}。
     * 供排序对话框判断「当前源豆瓣覆盖是否过低」并提示用户切到豆瓣较全的源。
     */
    public int[] ratingStats() {
        return VodSort.ratingStats(mSorter.snapshot());
    }

    @Override
    public boolean onLoadMore(String page) {
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            mBinding.recycler.showHeader();
        } else {
            if (headerVisible) mBinding.recycler.showHeader();
            else mBinding.recycler.hideHeader();
            mBinding.recycler.requestFocus();
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (mBinding != null) mBinding.recycler.moveToTop();
    }
}
