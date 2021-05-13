package xyz.fycz.myreader.ui.presenter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.*;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;

import android.widget.PopupMenu;

import androidx.core.app.ActivityCompat;

import org.jetbrains.annotations.NotNull;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import xyz.fycz.myreader.R;
import xyz.fycz.myreader.application.App;
import xyz.fycz.myreader.application.SysManager;
import xyz.fycz.myreader.base.BasePresenter;
import xyz.fycz.myreader.base.observer.MyObserver;
import xyz.fycz.myreader.ui.dialog.BookGroupDialog;
import xyz.fycz.myreader.util.utils.RxUtils;
import xyz.fycz.myreader.webapi.ResultCallback;
import xyz.fycz.myreader.common.APPCONST;
import xyz.fycz.myreader.ui.dialog.MyAlertDialog;
import xyz.fycz.myreader.greendao.entity.BookGroup;
import xyz.fycz.myreader.greendao.service.BookGroupService;
import xyz.fycz.myreader.model.backup.UserService;
import xyz.fycz.myreader.ui.activity.*;
import xyz.fycz.myreader.webapi.crawler.base.ReadCrawler;
import xyz.fycz.myreader.webapi.crawler.ReadCrawlerUtil;
import xyz.fycz.myreader.ui.dialog.DialogCreator;
import xyz.fycz.myreader.widget.custom.DragSortGridView;
import xyz.fycz.myreader.entity.Setting;
import xyz.fycz.myreader.enums.BookcaseStyle;
import xyz.fycz.myreader.greendao.entity.Book;
import xyz.fycz.myreader.greendao.entity.Chapter;
import xyz.fycz.myreader.greendao.service.BookService;
import xyz.fycz.myreader.greendao.service.ChapterService;
import xyz.fycz.myreader.ui.adapter.BookcaseAdapter;
import xyz.fycz.myreader.ui.adapter.BookcaseDetailedAdapter;
import xyz.fycz.myreader.ui.adapter.BookcaseDragAdapter;
import xyz.fycz.myreader.ui.fragment.BookcaseFragment;
import xyz.fycz.myreader.util.*;
import xyz.fycz.myreader.util.notification.NotificationUtil;
import xyz.fycz.myreader.util.utils.NetworkUtils;
import xyz.fycz.myreader.webapi.CommonApi;

import static xyz.fycz.myreader.application.App.checkVersionByServer;


public class BookcasePresenter implements BasePresenter {

    private final BookcaseFragment mBookcaseFragment;
    private final ArrayList<Book> mBooks = new ArrayList<>();//书目数组
    private BookcaseAdapter mBookcaseAdapter;
    private final BookService mBookService;
    private final ChapterService mChapterService;
    private final BookGroupService mBookGroupService;
    private final MainActivity mMainActivity;
    private PermissionsChecker mPermissionsChecker;
    private boolean isBookcaseStyleChange;
    private Setting mSetting;
    private final List<Book> errorLoadingBooks = new ArrayList<>();
    private int finishLoadBookCount = 0;
    //    private int notifyId = 11;
    private ExecutorService es = Executors.newFixedThreadPool(1);//更新/下载线程池

    public ExecutorService getEs() {
        return es;
    }

    private NotificationUtil notificationUtil;//通知工具类
    private String downloadingBook;//正在下载的书名
    private String downloadingChapter;//正在下载的章节名
    private boolean isDownloadFinish = true;//单本书是否下载完成
    private static boolean isStopDownload = true;//是否停止下载
    private int curCacheChapterNum;//当前下载的章节数
    private int needCacheChapterNum;//需要下载的章节数
    private int successCathe;//成功章节数
    private int errorCathe;//失败章节数
    private int tempCacheChapterNum;//上次下载的章节数
    private int tempCount;//下载超时时间
    private int downloadInterval = 150;//下载间隔
    private Runnable sendDownloadNotification;//发送通知的线程
    private boolean isFirstRefresh = true;//是否首次进入刷新
    private boolean isGroup;
    private MainActivity.OnGroupChangeListener ogcl;
    private final BookGroupDialog mBookGroupDia;

    public static final String CANCEL_ACTION = "cancelAction";

    static final String[] PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    @SuppressLint("HandlerLeak")
    public final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    if (!App.isDestroy(mMainActivity)) {
                        App.runOnUiThread(() -> mBookcaseAdapter.notifyDataSetChanged());
                        finishLoadBookCount++;
                        mBookcaseFragment.getSrlContent().finishRefresh();
                    }
                    break;
                case 2:
                    mBookcaseFragment.getSrlContent().finishRefresh();
                    break;
                case 3:
                    mBookcaseAdapter.notifyDataSetChanged();
                    break;
                case 4:
                    showErrorLoadingBooks();
                    if (App.isDebug()) {
                        if (isFirstRefresh) {
                            initBook();
                            isFirstRefresh = false;
                        }
                        downloadAll(false, false);
                    }
                    break;
                case 5:
                    break;
                case 6:
                    break;
                case 7:
                    init();
                    break;
                case 8:
                    sendNotification();
                    break;
                case 9:
                    //mBookcaseFragment.getRlDownloadTip().setVisibility(View.GONE);
                    isDownloadFinish = true;
                    break;
                case 10:
                    mBookcaseFragment.getTvDownloadTip().setText("正在初始化缓存任务...");
                    mBookcaseFragment.getPbDownload().setProgress(0);
                    mBookcaseFragment.getRlDownloadTip().setVisibility(View.VISIBLE);
                    break;
                case 11:
                    ToastUtils.showInfo("正在后台缓存书籍，具体进度可查看通知栏！");
                    notificationUtil.requestNotificationPermissionDialog(mMainActivity);
                    break;
            }
        }
    };

    //构造方法
    public BookcasePresenter(BookcaseFragment bookcaseFragment) {
        mBookcaseFragment = bookcaseFragment;
        mBookService = BookService.getInstance();
        mChapterService = ChapterService.getInstance();
        mBookGroupService = BookGroupService.getInstance();
        mMainActivity = (MainActivity) (mBookcaseFragment.getActivity());
//        mChapterService = new ChapterService();
        mSetting = SysManager.getSetting();
        mBookGroupDia = new BookGroupDialog(mMainActivity);
    }

    //启动
    @Override
    public void start() {
        checkVersionByServer(mMainActivity, false);
        if (mSetting.getBookcaseStyle() == null) {
            mSetting.setBookcaseStyle(BookcaseStyle.listMode);
        }

        sendDownloadNotification = this::sendNotification;
        notificationUtil = NotificationUtil.getInstance();

        getData();

        /*if (mSetting.isAutoSyn() && UserService.isLogin()) {
            synBookcaseToWeb(true);
        }*/

        //是否启用下拉刷新（默认启用）
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mBookcaseFragment.getSrlContent().setEnableRefresh(false);
        }
        //设置是否启用内容视图拖动效果
        mBookcaseFragment.getSrlContent().setEnableHeaderTranslationContent(false);
        //设置刷新监听器
        mBookcaseFragment.getSrlContent().setOnRefreshListener(refreshlayout -> initNoReadNum());
        //搜索按钮监听器
        mBookcaseFragment.getLlNoDataTips().setOnClickListener(view -> {
            Intent intent = new Intent(mBookcaseFragment.getContext(), SearchBookActivity.class);
            mBookcaseFragment.startActivity(intent);
        });

        //长按事件监听
        mBookcaseFragment.getGvBook().setOnItemLongClickListener((parent, view, position, id) -> false);

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            //滑动监听器
            mBookcaseFragment.getGvBook().getmScrollView().setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (!mBookcaseAdapter.ismEditState()) {
                    mBookcaseFragment.getSrlContent().setEnableRefresh(scrollY == 0);
                }
            });
        }

        //全选监听器
        mBookcaseFragment.getmCbSelectAll().setOnClickListener(v -> {
            //设置全选状态
            boolean isChecked = mBookcaseFragment.getmCbSelectAll().isChecked();
            mBookcaseAdapter.setCheckedAll(isChecked);
        });

        //删除监听器
        mBookcaseFragment.getmBtnDelete().setOnClickListener(v -> {
            if (!isGroup) {
                DialogCreator.createCommonDialog(mMainActivity, "批量删除书籍",
                        "确定要删除这些书籍吗？", true, (dialog, which) -> {
                            for (Book book : mBookcaseAdapter.getSelectBooks()) {
                                mBookService.deleteBook(book);
                            }
                            ToastUtils.showSuccess("书籍删除成功！");
                            init();
                            mBookcaseAdapter.setCheckedAll(false);
                        }, null);
            } else {
                DialogCreator.createCommonDialog(mMainActivity, "批量删除/移除书籍",
                        "您是希望是要删除这些书籍及其所有缓存还是从分组中移除(不会删除书籍)呢？", true,
                        "删除书籍", "从分组中移除", (dialog, which) -> {
                            for (Book book : mBookcaseAdapter.getSelectBooks()) {
                                mBookService.deleteBook(book);
                            }
                            ToastUtils.showSuccess("书籍删除成功！");
                            init();
                            mBookcaseAdapter.setCheckedAll(false);
                        }, (dialog, which) -> {
                            for (Book book : mBookcaseAdapter.getSelectBooks()) {
                                book.setGroupId("");
                                mBookService.updateEntity(book);
                            }
                            ToastUtils.showSuccess("书籍已从分组中移除！");
                            init();
                            mBookcaseAdapter.setCheckedAll(false);
                        });
            }
        });

        //加入分组监听器
        mBookcaseFragment.getmBtnAddGroup().setOnClickListener(v -> {
            mBookGroupDia.addGroup(mBookcaseAdapter.getSelectBooks(), new BookGroupDialog.OnGroup() {
                @Override
                public void change() {
                    init();
                    mBookcaseAdapter.setCheckedAll(false);
                    if (hasOnGroupChangeListener())
                        ogcl.onChange();
                }

                @Override
                public void addGroup() {
                    mBookcaseFragment.getmBtnAddGroup().performClick();
                }
            });
        });
    }


    //获取数据
    public void getData() {
        init();
        if (mSetting.isRefreshWhenStart()) {
            mHandler.postDelayed(this::initNoReadNum, 500);
        }
    }

    //初始化
    public void init() {
        initBook();
        if (mBooks.size() == 0) {
            mBookcaseFragment.getGvBook().setVisibility(View.GONE);
            mBookcaseFragment.getLlNoDataTips().setVisibility(View.VISIBLE);
        } else {
            if (mBookcaseAdapter == null || isBookcaseStyleChange) {
                switch (mSetting.getBookcaseStyle()) {
                    case listMode:
                        mBookcaseAdapter = new BookcaseDetailedAdapter(mMainActivity, R.layout.gridview_book_detailed_item, mBooks, false, this, isGroup);
                        mBookcaseFragment.getGvBook().setNumColumns(1);
                        break;
                    case threePalaceMode:
                        mBookcaseAdapter = new BookcaseDragAdapter(mMainActivity, R.layout.gridview_book_item, mBooks, false, this, isGroup);
                        mBookcaseFragment.getGvBook().setNumColumns(3);
                        break;
                }
                mBookcaseAdapter.setOnBookCheckedListener(isChecked -> {
                    changeCheckedAllStatus();
                    //设置删除和加入分组按钮是否可用
                    setBtnClickable(mBookcaseAdapter.getmCheckedCount() > 0);
                });
                mBookcaseFragment.getGvBook().setDragModel(-1);
                mBookcaseFragment.getGvBook().setTouchClashparent(mMainActivity.getViewPagerMain());
                mBookcaseFragment.getGvBook().setOnDragSelectListener(new DragSortGridView.OnDragSelectListener() {
                    @Override
                    public void onDragSelect(View mirror) {
                        if (mSetting.getBookcaseStyle() == BookcaseStyle.listMode) {
                            mirror.setBackgroundColor(mMainActivity.getResources().getColor(R.color.colorBackground));
                        } else {
                            mirror.setScaleX(1.05f);
                        }
                        mirror.setScaleY(1.05f);
                    }

                    @Override
                    public void onPutDown(View itemView) {
                    }
                });
                mBookcaseFragment.getGvBook().setAdapter(mBookcaseAdapter);
                isBookcaseStyleChange = false;
            } else {
                mBookcaseAdapter.notifyDataSetChanged();
            }
            mBookcaseFragment.getLlNoDataTips().setVisibility(View.GONE);
            mBookcaseFragment.getGvBook().setVisibility(View.VISIBLE);
        }
    }

    //初始化书籍
    private void initBook() {
        mBooks.clear();
        String curBookGroupId = SharedPreUtils.getInstance().getString(mMainActivity.getString(R.string.curBookGroupId), "");
        BookGroup bookGroup = mBookGroupService.getGroupById(curBookGroupId);
        if (bookGroup == null) {
            curBookGroupId = "";
            SharedPreUtils.getInstance().putString(mMainActivity.getString(R.string.curBookGroupId), "");
            SharedPreUtils.getInstance().putString(mMainActivity.getString(R.string.curBookGroupName), "");
            if (hasOnGroupChangeListener())
                ogcl.onChange();
        }
        isGroup = !"".equals(curBookGroupId);
        if (mBookcaseAdapter != null) {
            mBookcaseAdapter.setGroup(isGroup);
        }
        mBooks.addAll(mBookService.getGroupBooks(curBookGroupId));

        if (mSetting.getSortStyle() == 1) {
            Collections.sort(mBooks, (o1, o2) -> {
                if (o1.getLastReadTime() < o2.getLastReadTime()) {
                    return 1;
                } else if (o1.getLastReadTime() > o2.getLastReadTime()) {
                    return -1;
                }
                return 0;
            });
        } else if (mSetting.getSortStyle() == 2) {
            Collections.sort(mBooks, (o1, o2) -> {
                Collator cmp = Collator.getInstance(java.util.Locale.CHINA);
                return cmp.compare(o1.getName(), o2.getName());
            });
        }

        for (int i = 0; i < mBooks.size(); i++) {
            int sort = !isGroup ? mBooks.get(i).getSortCode() : mBooks.get(i).getGroupSort();
            if (sort != i + 1) {
                if (!isGroup) {
                    mBooks.get(i).setSortCode(i + 1);
                } else {
                    mBooks.get(i).setGroupSort(i + 1);
                }
                mBookService.updateEntity(mBooks.get(i));
            }
        }
    }

    //检查书籍更新
    public void initNoReadNum() {
        errorLoadingBooks.clear();
        finishLoadBookCount = 0;
        for (Book book : mBooks) {
            mBookcaseAdapter.getIsLoading().put(book.getId(), true);
        }
        if (mBooks.size() > 0) {
            mHandler.sendMessage(mHandler.obtainMessage(3));
        }
        for (final Book book : mBooks) {
            if ("本地书籍".equals(book.getType()) || book.getIsCloseUpdate()) {
                mBookcaseAdapter.getIsLoading().put(book.getId(), false);
                mHandler.sendMessage(mHandler.obtainMessage(1));
                continue;
            }
            Thread update = new Thread(() -> {
                final ArrayList<Chapter> mChapters = (ArrayList<Chapter>) mChapterService.findBookAllChapterByBookId(book.getId());
                final ReadCrawler mReadCrawler = ReadCrawlerUtil.getReadCrawler(book.getSource());
                CommonApi.getBookChapters(book.getChapterUrl(), mReadCrawler).flatMap(chapters -> Observable.create(emitter -> {
                    int noReadNum = chapters.size() - book.getChapterTotalNum();
                    book.setNoReadNum(Math.max(noReadNum, 0));
                    book.setNewestChapterTitle(chapters.get(chapters.size() - 1).getTitle());
                    mBookcaseAdapter.getIsLoading().put(book.getId(), false);
                    mChapterService.updateAllOldChapterData(mChapters, chapters, book.getId());
                    mBookService.updateEntity(book);
                    emitter.onComplete();
                })).compose(RxUtils::toSimpleSingle).subscribe(new MyObserver<Object>() {
                    @Override
                    public void onNext(@NotNull Object o) {

                    }

                    @Override
                    public void onComplete() {
                        mHandler.sendMessage(mHandler.obtainMessage(1));
                    }

                    @Override
                    public void onError(Throwable e) {
                        mBookcaseAdapter.getIsLoading().put(book.getId(), false);
                        errorLoadingBooks.add(book);
                        mHandler.sendMessage(mHandler.obtainMessage(1));
                        if (App.isDebug()) e.printStackTrace();
                    }
                });
            });
            es.submit(update);
        }
        App.getApplication().newThread(() -> {
            while (true) {
                if (finishLoadBookCount == mBooks.size()) {
                    mHandler.sendMessage(mHandler.obtainMessage(4));
                    mHandler.sendMessage(mHandler.obtainMessage(2));
                    break;
                }
            }
        });
    }

    /**
     * 显示更新失败的书籍信息
     */
    private void showErrorLoadingBooks() {
        StringBuilder s = new StringBuilder();
        for (Book book : errorLoadingBooks) {
            s.append(book.getName());
            s.append("、");
        }
        if (errorLoadingBooks.size() > 0) {
            s.deleteCharAt(s.lastIndexOf("、"));
            s.append(" 更新失败");
            ToastUtils.showError(s.toString());
        }
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_change_group:
                mBookcaseFragment.getmBookcasePresenter()
                        .showBookGroupMenu(mMainActivity.findViewById(R.id.action_change_group));
                return true;
            case R.id.action_edit:
                editBookcase(true);
                return true;
            case R.id.action_styleChange:
                mBookcaseFragment.getGvBook().getmScrollView().setScrollY(0);
                if (mSetting.getBookcaseStyle().equals(BookcaseStyle.listMode)) {
                    mSetting.setBookcaseStyle(BookcaseStyle.threePalaceMode);
                    ToastUtils.show("已切换为三列网格视图！");
                } else {
                    mSetting.setBookcaseStyle(BookcaseStyle.listMode);
                    ToastUtils.show("已切换为列表视图！");
                }
                isBookcaseStyleChange = true;
                SysManager.saveSetting(mSetting);
                init();
                return true;
            case R.id.action_group_man:
                showGroupManDia();
                return true;
            case R.id.action_addLocalBook:
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
                    if (mPermissionsChecker == null) {
                        mPermissionsChecker = new PermissionsChecker(mMainActivity);
                    }
                    //获取读取和写入SD卡的权限
                    if (mPermissionsChecker.lacksPermissions(PERMISSIONS)) {
                        //请求权限
                        ActivityCompat.requestPermissions(mMainActivity, PERMISSIONS, APPCONST.PERMISSIONS_REQUEST_STORAGE);
                        return true;
                    }
                }
                Intent fileSystemIntent = new Intent(mMainActivity, FileSystemActivity.class);
                mMainActivity.startActivity(fileSystemIntent);
                break;
            case R.id.action_download_all:
                if (!SharedPreUtils.getInstance().getBoolean(mMainActivity.getString(R.string.isReadDownloadAllTip), false)) {
                    DialogCreator.createCommonDialog(mMainActivity, "一键缓存",
                            mMainActivity.getString(R.string.all_cathe_tip), true,
                            (dialog, which) -> {
                                downloadAll(true, true);
                                SharedPreUtils.getInstance().putBoolean(mMainActivity.getString(R.string.isReadDownloadAllTip), true);
                            }, null);
                } else {
                    downloadAll(true, true);
                }
                return true;

        }
        return false;
    }

    /**
     * 显示书籍分组菜单
     */
    public void showBookGroupMenu(View view) {
        mBookGroupDia.initBookGroups(false);
        PopupMenu popupMenu = new PopupMenu(mMainActivity, view, Gravity.END);
        popupMenu.getMenu().add(0, 0, 0, "所有书籍");
        for (int i = 0; i < mBookGroupDia.getmGroupNames().length; i++) {
            popupMenu.getMenu().add(0, 0, i + 1, mBookGroupDia.getmGroupNames()[i]);
        }
        popupMenu.setOnMenuItemClickListener(menuItem -> {
            String curBookGroupId = "";
            String curBookGroupName = "";
            if (menuItem.getOrder() > 0) {
                curBookGroupId = mBookGroupDia.getmBookGroups().get(menuItem.getOrder() - 1).getId();
                curBookGroupName = mBookGroupDia.getmBookGroups().get(menuItem.getOrder() - 1).getName();
            }
            SharedPreUtils.getInstance().putString(mMainActivity.getString(R.string.curBookGroupId), curBookGroupId);
            SharedPreUtils.getInstance().putString(mMainActivity.getString(R.string.curBookGroupName), curBookGroupName);
            ogcl.onChange();
            init();
            return true;
        });
        popupMenu.show();
    }

    /**
     * 编辑书架
     *
     * @param isEdit
     */
    private void editBookcase(boolean isEdit) {
        if (isEdit) {
            if (canEditBookcase()) {
                mBookcaseFragment.getSrlContent().setEnableRefresh(false);
                mBookcaseAdapter.setmEditState(true);
                if (mSetting.getSortStyle() == 0) {
                    mBookcaseFragment.getGvBook().setDragModel(DragSortGridView.DRAG_BY_LONG_CLICK);
                }
                mBookcaseFragment.getRlBookEdit().setVisibility(View.VISIBLE);
                mMainActivity.initMenuAnim();
                mBookcaseFragment.getRlBookEdit().startAnimation(mMainActivity.getmBottomInAnim());
                setBtnClickable(false);
                changeCheckedAllStatus();
                mBookcaseAdapter.notifyDataSetChanged();
            } else {
                ToastUtils.showWarring("当前无任何书籍，无法编辑书架!");
            }
        } else {
            if (mBookcaseFragment.getGvBook().getmScrollView().getScrollY() == 0
                    && android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mBookcaseFragment.getSrlContent().setEnableRefresh(true);
            }
            mBookcaseFragment.getGvBook().setDragModel(-1);
            mBookcaseAdapter.setmEditState(false);
            mBookcaseFragment.getRlBookEdit().setVisibility(View.GONE);
            mMainActivity.initMenuAnim();
            mBookcaseFragment.getRlBookEdit().startAnimation(mMainActivity.getmBottomOutAnim());
            mBookcaseAdapter.notifyDataSetChanged();
        }
    }

    public boolean canEditBookcase() {
        return mBooks.size() > 0;
    }

    /**
     * 分组管理对话框
     */
    private void showGroupManDia() {
        MyAlertDialog.build(mMainActivity)
                .setTitle("分组管理")
                .setItems(mMainActivity.getResources().getStringArray(R.array.group_man)
                        , (dialog, which) -> {
                            mBookGroupDia.initBookGroups(false);
                            switch (which) {
                                case 0:
                                    mBookGroupDia.showAddOrRenameGroupDia(false, false, 0, new BookGroupDialog.OnGroup() {
                                        @Override
                                        public void change() {
                                            ogcl.onChange();
                                        }

                                        @Override
                                        public void addGroup() {
                                            mBookcaseFragment.getmBtnAddGroup().performClick();
                                        }
                                    });
                                    break;
                                case 1:
                                    mBookGroupDia.showSelectGroupDia((dialog1, which1) -> {
                                        mBookGroupDia.showAddOrRenameGroupDia(true, false, which1, new BookGroupDialog.OnGroup() {
                                            @Override
                                            public void change() {
                                                ogcl.onChange();
                                            }

                                            @Override
                                            public void addGroup() {
                                                mBookcaseFragment.getmBtnAddGroup().performClick();
                                            }
                                        });
                                    });
                                    break;
                                case 2:
                                    mBookGroupDia.showDeleteGroupDia(new BookGroupDialog.OnGroup() {
                                        @Override
                                        public void change() {
                                            ogcl.onChange();
                                            init();
                                        }
                                    });
                                    break;
                            }
                        }).show();
    }

    //分组切换监听器
    public void addOnGroupChangeListener(MainActivity.OnGroupChangeListener ogcl) {
        this.ogcl = ogcl;
    }

    //是否有分组切换监听器
    public boolean hasOnGroupChangeListener() {
        return this.ogcl != null;
    }

/**********************************************缓存书籍***************************************************************/
    /**
     * 缓存所有书籍
     */
    private void downloadAll(boolean isDownloadAllChapters, boolean isFromUser) {
        if (!NetworkUtils.isNetWorkAvailable()) {
            ToastUtils.showWarring("无网络连接！");
            return;
        }
        if (mBooks.size() == 0) {
            if (isFromUser)
                ToastUtils.showWarring("当前书架没有任何书籍，无法一键缓存！");
            return;
        }
        App.getApplication().newThread(() -> {
            ArrayList<Book> needDownloadBooks = new ArrayList<>();
            for (Book book : mBooks) {
                //if (!LocalBookSource.pinshu.toString().equals(book.getSource()) && !"本地书籍".equals(book.getType())
                if (!"本地书籍".equals(book.getType())
                        && book.getIsDownLoadAll()) {
                    needDownloadBooks.add(book);
                }
            }
            if (needDownloadBooks.size() == 0) {
                if (isFromUser)
                    ToastUtils.showWarring("当前书架书籍不支持/已关闭(可在设置开启)一键缓存！");
                return;
            }
            if (isDownloadAllChapters) {
                mHandler.sendEmptyMessage(11);
            }
            downloadFor:
            for (final Book book : needDownloadBooks) {
                isDownloadFinish = false;
                Thread downloadThread = new Thread(() -> {
                    ArrayList<Chapter> chapters = (ArrayList<Chapter>) mChapterService.findBookAllChapterByBookId(book.getId());
                    int end;
                    if (isDownloadAllChapters) {
                        end = chapters.size();
                    } else {
                        end = book.getHisttoryChapterNum() + 5;
                    }
                    addDownload(book, chapters,
                            book.getHisttoryChapterNum(), end, true);
                });
                es.submit(downloadThread);
                do {
                    try {
                        Thread.sleep(downloadInterval);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (isStopDownload) {
                        break downloadFor;
                    }
                } while (!isDownloadFinish);
            }
            if (isDownloadAllChapters && !isStopDownload) {
                //通知
                Intent mainIntent = new Intent(mMainActivity, MainActivity.class);
                PendingIntent mainPendingIntent = PendingIntent.getActivity(mMainActivity, 0, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                Notification notification = notificationUtil.build(APPCONST.channelIdDownload)
                        .setSmallIcon(R.drawable.ic_download)
                        //通知栏大图标
                        .setLargeIcon(BitmapFactory.decodeResource(App.getApplication().getResources(), R.mipmap.ic_launcher))
                        .setOngoing(false)
                        //点击通知后自动清除
                        .setAutoCancel(true)
                        .setContentTitle("缓存完成")
                        .setContentText("书籍一键缓存完成！")
                        .setContentIntent(mainPendingIntent)
                        .build();
                notificationUtil.notify(1002, notification);
            }
        });
    }

    /**
     * 添加下载
     *
     * @param book
     * @param mChapters
     * @param begin
     * @param end
     */
    public void addDownload(final Book book, final ArrayList<Chapter> mChapters, int begin, int end, boolean isDownloadAll) {
        if ("本地书籍".equals(book.getType())) {
            ToastUtils.showWarring("《" + book.getName() + "》是本地书籍，不能缓存");
            return;
        }
        if (mChapters.size() == 0) {
            if (!isDownloadAll) {
                ToastUtils.showWarring("《" + book.getName() + "》章节目录为空，缓存失败，请刷新后重试");
            }
            return;
        }
        if (SysManager.getSetting().getCatheGap() != 0) {
            downloadInterval = SysManager.getSetting().getCatheGap();
        }
        //取消之前下载
        if (!isDownloadAll) {
            if (!isStopDownload) {
                isStopDownload = true;
                try {
                    Thread.sleep(2 * downloadInterval);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        //mHandler.sendMessage(mHandler.obtainMessage(10));
        downloadingBook = book.getName();
        final int finalBegin = Math.max(0, begin);
        final int finalEnd = Math.min(end, mChapters.size());
        needCacheChapterNum = finalEnd - finalBegin;
        curCacheChapterNum = 0;
        tempCacheChapterNum = 0;
        successCathe = 0;
        errorCathe = 0;
        isStopDownload = false;
        ArrayList<Chapter> needDownloadChapters = new ArrayList<>();
        for (int i = finalBegin; i < finalEnd; i++) {
            final Chapter chapter = mChapters.get(i);
            if (StringHelper.isEmpty(chapter.getContent())) {
                needDownloadChapters.add(chapter);
            }
        }
        needCacheChapterNum = needDownloadChapters.size();
        if (!isDownloadAll && needCacheChapterNum > 0) {
            mHandler.sendEmptyMessage(11);
        }
        mHandler.postDelayed(sendDownloadNotification, 2 * downloadInterval);
        for (Chapter chapter : needDownloadChapters) {
            if (StringHelper.isEmpty(chapter.getBookId())) {
                chapter.setBookId(book.getId());
            }
            ReadCrawler mReadCrawler = ReadCrawlerUtil.getReadCrawler(book.getSource());
            CommonApi.getChapterContent(chapter.getUrl(), mReadCrawler).flatMap(s -> Observable.create(emitter -> {
                downloadingChapter = chapter.getTitle();
                mChapterService.saveOrUpdateChapter(chapter, s);
                successCathe++;
                curCacheChapterNum++;
            })).subscribeOn(Schedulers.from(App.getApplication().getmFixedThreadPool())).subscribe(new MyObserver<Object>() {
                @Override
                public void onNext(@NotNull Object o) {

                }

                @Override
                public void onError(Throwable e) {
                    curCacheChapterNum++;
                    errorCathe++;
                    if (App.isDebug()) e.printStackTrace();
                }
            });
            try {
                Thread.sleep(downloadInterval);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (curCacheChapterNum == needCacheChapterNum) {
                if (!isDownloadAll) {
                    isStopDownload = true;
                }
                mHandler.sendMessage(mHandler.obtainMessage(9));
            }
            if (isStopDownload) {
                break;
            }
        }
        if (!isDownloadAll) {
            if (curCacheChapterNum == needCacheChapterNum) {
                ToastUtils.showInfo("《" + book.getName() + "》" + mMainActivity.getString(R.string.download_already_all_tips));
            }
        }
    }



    /**
     * 发送通知
     */
    private void sendNotification() {
        if (curCacheChapterNum == needCacheChapterNum) {
            mHandler.sendEmptyMessage(9);
            notificationUtil.cancelAll();
            return;
        } else {
            Notification notification = notificationUtil.build(APPCONST.channelIdDownload)
                    .setSmallIcon(R.drawable.ic_download)
                    //通知栏大图标
                    .setLargeIcon(BitmapFactory.decodeResource(App.getApplication().getResources(), R.mipmap.ic_launcher))
                    .setOngoing(true)
                    //点击通知后自动清除
                    .setAutoCancel(true)
                    .setContentTitle("正在下载：" + downloadingBook +
                            "[" + curCacheChapterNum + "/" + needCacheChapterNum + "]")
                    .setContentText(downloadingChapter == null ? "  " : downloadingChapter)
                    .addAction(R.drawable.ic_stop_black_24dp, "停止",
                            notificationUtil.getChancelPendingIntent(cancelDownloadReceiver.class))
                    .build();
            notificationUtil.notify(1000, notification);
        }
        if (tempCacheChapterNum < curCacheChapterNum) {
            tempCount = 1500 / downloadInterval;
            tempCacheChapterNum = curCacheChapterNum;
        } else if (tempCacheChapterNum == curCacheChapterNum) {
            tempCount--;
            if (tempCount == 0) {
                isDownloadFinish = true;
                notificationUtil.cancel(1000);
                return;
            }
        }
        mHandler.postDelayed(sendDownloadNotification, 2 * downloadInterval);
    }

    public static class cancelDownloadReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            //todo 跳转之前要处理的逻辑
            if (CANCEL_ACTION.equals(intent.getAction())) {
                isStopDownload = true;
            }
        }
    }


    /**
     * 同步书架
     */
    private void synBookcaseToWeb(boolean isAutoSyn) {
        if (!NetworkUtils.isNetWorkAvailable()) {
            if (!isAutoSyn) {
                ToastUtils.showWarring("无网络连接！");
            }
            return;
        }
        ArrayList<Book> mBooks = (ArrayList<Book>) BookService.getInstance().getAllBooks();
        if (mBooks.size() == 0) {
            if (!isAutoSyn) {
                ToastUtils.showWarring("当前书架无任何书籍，无法同步！");
            }
            return;
        }
        Date nowTime = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yy-MM-dd");
        String nowTimeStr = sdf.format(nowTime);
        SharedPreUtils spb = SharedPreUtils.getInstance();
        String synTime = spb.getString(mMainActivity.getString(R.string.synTime));
        if (!nowTimeStr.equals(synTime) || !isAutoSyn) {
            UserService.webBackup(new ResultCallback() {
                @Override
                public void onFinish(Object o, int code) {
                    if ((boolean) o) {
                        spb.putString(mMainActivity.getString(R.string.synTime), nowTimeStr);
                        if (!isAutoSyn) {
                            DialogCreator.createTipDialog(mMainActivity, "成功将书架同步至网络！");
                        }
                    } else {
                        if (!isAutoSyn) {
                            DialogCreator.createTipDialog(mMainActivity, "同步失败，请重试！");
                        }
                    }
                }

                @Override
                public void onError(Exception e) {
                    if (!isAutoSyn) {
                        DialogCreator.createTipDialog(mMainActivity, "同步失败，请重试！\n" + e.getLocalizedMessage());
                    }
                }
            });
        }
    }

    /*****************************************用于返回按钮判断*************************************/
    /**
     * 判断是否处于编辑状态
     *
     * @return
     */
    public boolean ismEditState() {
        if (mBookcaseAdapter != null) {
            return mBookcaseAdapter.ismEditState();
        }
        return false;
    }

    /**
     * 取消编辑状态
     */
    public void cancelEdit() {
        editBookcase(false);
    }

    /**
     * 销毁
     */
    public void destroy() {
        notificationUtil.cancelAll();
        mHandler.removeCallbacks(sendDownloadNotification);
        for (int i = 0; i < 13; i++) {
            mHandler.removeMessages(i + 1);
        }
    }


    /********************************编辑状态下下方按钮********************************************/
    private void setBtnClickable(boolean isClickable) {
        mBookcaseFragment.getmBtnDelete().setEnabled(isClickable);
        mBookcaseFragment.getmBtnDelete().setClickable(isClickable);
        mBookcaseFragment.getmBtnAddGroup().setEnabled(isClickable);
        mBookcaseFragment.getmBtnAddGroup().setClickable(isClickable);
    }

    /**
     * 改变全选按钮的状态
     */
    private void changeCheckedAllStatus() {
        //设置是否全选
        if (mBookcaseAdapter.getmCheckedCount() == mBookcaseAdapter.getmCheckableCount()) {
            mBookcaseAdapter.setIsCheckedAll(true);
        } else if (mBookcaseAdapter.isCheckedAll()) {
            mBookcaseAdapter.setIsCheckedAll(false);
        }
        mBookcaseFragment.getmCbSelectAll().setChecked(mBookcaseAdapter.isCheckedAll());
        //重置全选的文字
        if (mBookcaseAdapter.isCheckedAll()) {
            mBookcaseFragment.getmCbSelectAll().setText("取消");
        } else {
            mBookcaseFragment.getmCbSelectAll().setText("全选");
        }
    }

}
