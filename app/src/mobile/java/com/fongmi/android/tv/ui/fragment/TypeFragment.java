package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentTypeBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.VodAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.utils.EmptyText;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.VodListCache;
import com.fongmi.android.tv.utils.VodSort;

import java.util.HashMap;
import java.util.List;

public class TypeFragment extends BaseFragment implements CustomScroller.Callback, VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private HashMap<String, String> mExtends;
    private FragmentTypeBinding mBinding;
    private CustomScroller mScroller;
    private SiteViewModel mViewModel;
    private VodAdapter mAdapter;
    // 2026-11-12: 分栏内跨页全局排序。旧实现每页各自排序后 addAll，多次下拉后排序
    // 变成一段一段；改为累积全量有序列表，每次追加新页后整体 setItems。
    private final VodSort.Accumulator mSorter = new VodSort.Accumulator();
    // 缓存 + 增量探针：进入分栏先恢复已加载的全量，再拿第 1 页比对是否新增。
    private String mCacheKey;
    private int mCachedPageCount;
    private boolean mProbing;

    public static TypeFragment newInstance(String key, String typeId, Style style, HashMap<String, String> extend, boolean folder, int y) {
        Bundle args = new Bundle();
        args.putInt("y", y);
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

    private Style getStyle() {
        return isFolder() ? Style.list() : getSite().getStyle(getArguments().getParcelable("style"));
    }

    private HashMap<String, String> getExtend() {
        return (HashMap<String, String>) getArguments().getSerializable("extend");
    }

    private int getY() {
        return getArguments().getInt("y");
    }

    private boolean isFolder() {
        return getArguments().getBoolean("folder");
    }

    private boolean isHome() {
        return "home".equals(getTypeId());
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private FolderFragment getParent() {
        return (FolderFragment) getParentFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentTypeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.progressLayout.showProgress();
        mScroller = new CustomScroller(this);
        mExtends = getExtend();
        setRecyclerView();
        setViewModel();
        initCacheOrLoad();
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.recycler.addOnScrollListener(mScroller);
    }

    private void setRecyclerView() {
        mBinding.recycler.setTranslationY(-ResUtil.dp2px(getY()));
        mBinding.recycler.setHasFixedSize(true);
        setStyle(getStyle());
    }

    private void setStyle(Style style) {
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, Product.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(style.isList() ? new LinearLayoutManager(requireActivity()) : new GridLayoutManager(getContext(), Product.getColumn(requireActivity(), style)));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
        mViewModel.getAction().observe(getViewLifecycleOwner(), result -> Notify.show(result.getMsg()));
    }

    private void getHome() {
        mSorter.reset();
        mAdapter.clear(() -> mViewModel.homeContent());
    }

    /**
     * 2026-11-12: 优先秒开缓存。命中后恢复「全量有序列表 + 分页游标」，
     * 再请求第 1 页作增量探针：无新增就原样不动，有新增才插入排序并回写缓存。
     * 首页有自己的 result 缓存，不走这里。
     */
    private void initCacheOrLoad() {
        if (isHome()) {
            getVideo();
            return;
        }
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
        mBinding.progressLayout.showContent(true, all.size());
        mAdapter.setItems(all);
        mProbing = true;
        getVideo(getTypeId(), "1");
    }

    /** 探针回来：只比较有没有新条目，没有就完全不碰界面（保留排序与滚动位置）。 */
    private void onProbeResult(Result result) {
        mProbing = false;
        mBinding.swipeLayout.setRefreshing(false);
        if (result.getPageCount() > 0) mCachedPageCount = result.getPageCount();
        List<Vod> before = mSorter.snapshot();
        List<Vod> after = mSorter.append(result);
        if (after.size() <= before.size()) return;
        mAdapter.setItems(after, this::checkMore);
        VodListCache.put(mCacheKey, after, mScroller.getPage(), mCachedPageCount, Setting.getVodSort());
    }

    private void getVideo() {
        // 下拉刷新 / 切排序 / 切筛选：key 里含排序模式与筛选条件，这里重算并显式失效。
        if (!isHome()) {
            mCacheKey = VodListCache.key(getKey(), getTypeId(), Setting.getVodSort(), mExtends);
            VodListCache.invalidate(mCacheKey);
        }
        mProbing = false;
        mScroller.reset();
        // 刷新 / 换分栏 / 切换排序方式：丢弃上一轮累积，按当前排序偏好重新建序。
        mSorter.reset();
        mAdapter.clear(() -> {
            if (!mBinding.swipeLayout.isRefreshing()) mBinding.progressLayout.showProgress();
            if (isHome()) setAdapter(getParent().getResult());
            else getVideo(getTypeId(), "1");
        });
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
        int size = result.getList().size();
        // 2026-10-13 手机端：栏目空时不再静默留白。文案按 SiteApi 判定的原因分级，
        // 区分「源确实没这个分类」和「源返回了风控页/错误页」——后者原来一律被说成
        // "多半依赖 X5/JS 引擎"，实测下来基本不成立，会误导人。
        if (first && size == 0) mBinding.progressLayout.showEmpty(EmptyText.of(requireContext(), result));
        else mBinding.progressLayout.showContent(first, size);
        mBinding.swipeLayout.setRefreshing(false);
        mScroller.endLoading(result);
        if (size > 0) addVideo(result);
    }

    private void addVideo(Result result) {
        Style style = result.getVod().getStyle(getStyle());
        if (!style.equals(mAdapter.getStyle())) setStyle(style);
        // 整体提交「累积到本页为止的全量有序列表」，而不是把本页排序后追加到尾部。
        // AsyncListDiffer 只把新条目识别为插入，已有条目位置不动，滚动不跳。
        List<Vod> all = mSorter.append(result);
        mAdapter.setItems(all, this::checkMore);
        // 每加载一页都回写缓存（内存即时 + 磁盘异步），中途退出也不丢已翻到的页数。
        VodListCache.put(mCacheKey, all, mScroller.getPage(), result.getPageCount(), Setting.getVodSort());
    }

    private void checkMore() {
        mBinding.recycler.post(() -> {
            if (isHome()) return;
            mScroller.checkMore(mBinding.recycler);
        });
    }

    public void scrollToTop() {
        mBinding.recycler.smoothScrollToPosition(0);
    }

    public void setFilter(String key, Value value) {
        if (value.isSelected()) mExtends.put(key, value.getV());
        else mExtends.remove(key);
        onRefresh();
    }

    @Override
    public void onRefresh() {
        if (isHome()) getHome();
        else getVideo();
    }

    @Override
    public boolean onLoadMore(String page) {
        if (isHome()) return false;
        getVideo(getTypeId(), page);
        return true;
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) {
            mViewModel.action(getKey(), item.getAction());
        } else if (item.isFolder()) {
            getParent().openFolder(item.getId(), mExtends);
        } else {
            if (getSite().isIndex()) SearchActivity.start(requireActivity(), item.getName());
            else VideoActivity.start(requireActivity(), getKey(), item.getId(), item.getName(), item.getPic(), isFolder() ? item.getName() : null);
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction() || item.isFolder()) return false;
        SearchActivity.start(requireActivity(), item.getName());
        return true;
    }
}
