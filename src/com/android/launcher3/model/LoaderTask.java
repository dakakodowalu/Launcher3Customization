/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.model;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.TimingLogger;

import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherModel;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.folder.Folder;
import com.android.launcher3.folder.FolderGridOrganizer;
import com.android.launcher3.folder.FolderNameInfos;
import com.android.launcher3.folder.FolderNameProvider;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.ComponentWithLabelAndIcon;
import com.android.launcher3.icons.ComponentWithLabelAndIcon.ComponentWithIconCachingLogic;
import com.android.launcher3.icons.IconCache;
import com.android.launcher3.icons.LauncherActivityCachingLogic;
import com.android.launcher3.icons.ShortcutCachingLogic;
import com.android.launcher3.icons.cache.IconCacheUpdateHandler;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.FolderInfo;
import com.android.launcher3.model.data.IconRequestInfo;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.model.data.ItemInfoWithIcon;
import com.android.launcher3.model.data.LauncherAppWidgetInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;
import com.android.launcher3.pm.InstallSessionHelper;
import com.android.launcher3.pm.PackageInstallInfo;
import com.android.launcher3.pm.UserCache;
import com.android.launcher3.qsb.QsbContainerView;
import com.android.launcher3.settings.MxSettings;
import com.android.launcher3.shortcuts.ShortcutKey;
import com.android.launcher3.shortcuts.ShortcutRequest;
import com.android.launcher3.shortcuts.ShortcutRequest.QueryResult;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.IOUtils;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSet;
import com.android.launcher3.util.LooperIdleLock;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.util.TraceHelper;
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo;
import com.android.launcher3.widget.WidgetManagerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static com.android.launcher3.model.BgDataModel.Callbacks.FLAG_HAS_SHORTCUT_PERMISSION;
import static com.android.launcher3.model.BgDataModel.Callbacks.FLAG_QUIET_MODE_CHANGE_PERMISSION;
import static com.android.launcher3.model.BgDataModel.Callbacks.FLAG_QUIET_MODE_ENABLED;
import static com.android.launcher3.model.ModelUtils.filterCurrentWorkspaceItems;
import static com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_DISABLED_LOCKED_USER;
import static com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_DISABLED_SAFEMODE;
import static com.android.launcher3.model.data.ItemInfoWithIcon.FLAG_DISABLED_SUSPENDED;
import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;
import static com.android.launcher3.util.PackageManagerHelper.hasShortcutsPermission;
import static com.android.launcher3.util.PackageManagerHelper.isSystemApp;

/**
 * 加载各个模块Task的显示类，如workspace工作区icon、all工作区icon初始化工作。
 * Runnable for the thread that loads the contents of the launcher:
 *   - workspace icons
 *   - widgets
 *   - all apps icons
 *   - deep shortcuts within apps
 */
public class LoaderTask implements Runnable {
    private static final String TAG = "Launcher.LoaderTask";

    private static final boolean DEBUG = true;

    protected final LauncherAppState mApp;
    private final AllAppsList mBgAllAppsList;
    protected final BgDataModel mBgDataModel;
    private final ModelDelegate mModelDelegate;

    private FirstScreenBroadcast mFirstScreenBroadcast;

    private final LoaderResults mResults;

    private final LauncherApps mLauncherApps;
    private final UserManager mUserManager;
    private final UserCache mUserCache;

    private final InstallSessionHelper mSessionHelper;
    private final IconCache mIconCache;

    private final UserManagerState mUserManagerState = new UserManagerState();

    protected final Map<ComponentKey, AppWidgetProviderInfo> mWidgetProvidersMap = new ArrayMap<>();

    private boolean mStopped;

    private final Set<PackageUserKey> mPendingPackages = new HashSet<>();
    private boolean mItemsDeleted = false;
    private String mDbName;

    public LoaderTask(LauncherAppState app, AllAppsList bgAllAppsList, BgDataModel dataModel,
            ModelDelegate modelDelegate, LoaderResults results) {
        mApp = app;
        mBgAllAppsList = bgAllAppsList;
        mBgDataModel = dataModel;
        mModelDelegate = modelDelegate;
        mResults = results;

        mLauncherApps = mApp.getContext().getSystemService(LauncherApps.class);
        mUserManager = mApp.getContext().getSystemService(UserManager.class);
        mUserCache = UserCache.INSTANCE.get(mApp.getContext());
        mSessionHelper = InstallSessionHelper.INSTANCE.get(mApp.getContext());
        mIconCache = mApp.getIconCache();
    }

    protected synchronized void waitForIdle() {
        // Wait until the either we're stopped or the other threads are done.
        // This way we don't start loading all apps until the workspace has settled
        // down.
        LooperIdleLock idleLock = mResults.newIdleLock(this);
        // Just in case mFlushingWorkerThread changes but we aren't woken up,
        // wait no longer than 1sec at a time
        while (!mStopped && idleLock.awaitLocked(1000));
    }

    private synchronized void verifyNotStopped() throws CancellationException {
        if (mStopped) {
            throw new CancellationException("Loader stopped");
        }
    }

    private void sendFirstScreenActiveInstallsBroadcast() {
        ArrayList<ItemInfo> firstScreenItems = new ArrayList<>();
        ArrayList<ItemInfo> allItems = mBgDataModel.getAllWorkspaceItems();

        // Screen set is never empty
        IntArray allScreens = mBgDataModel.collectWorkspaceScreens();
        final int firstScreen = allScreens.get(0);
        IntSet firstScreens = IntSet.wrap(firstScreen);

        filterCurrentWorkspaceItems(firstScreens, allItems, firstScreenItems,
                new ArrayList<>() /* otherScreenItems are ignored */);
        mFirstScreenBroadcast.sendBroadcasts(mApp.getContext(), firstScreenItems);
    }

    public void run() {
        synchronized (this) {
            // Skip fast if we are already stopped.
            if (mStopped) {
                return;
            }
        }

        Object traceToken = TraceHelper.INSTANCE.beginSection(TAG);
        TimingLogger logger = new TimingLogger(TAG, "run");
        LoaderMemoryLogger memoryLogger = new LoaderMemoryLogger();
        try (LauncherModel.LoaderTransaction transaction = mApp.getModel().beginLoader(this)) {
            List<ShortcutInfo> allShortcuts = new ArrayList<>();
            Trace.beginSection("LoadWorkspace");
            try {
                loadWorkspace(allShortcuts, memoryLogger);
            } finally {
                Trace.endSection();
            }
            logASplit(logger, "loadWorkspace");

            // Sanitize data re-syncs widgets/shortcuts based on the workspace loaded from db.
            // sanitizeData should not be invoked if the workspace is loaded from a db different
            // from the main db as defined in the invariant device profile.
            // (e.g. both grid preview and minimal device mode uses a different db)
            if (mApp.getInvariantDeviceProfile().dbFile.equals(mDbName)) {
                verifyNotStopped();
                sanitizeData();
                logASplit(logger, "sanitizeData");
            }

            verifyNotStopped();
            mResults.bindWorkspace(true /* incrementBindId */);
            logASplit(logger, "bindWorkspace");

            mModelDelegate.workspaceLoadComplete();
            // Notify the installer packages of packages with active installs on the first screen.
            sendFirstScreenActiveInstallsBroadcast();
            logASplit(logger, "sendFirstScreenActiveInstallsBroadcast");

            // Take a break
            waitForIdle();
            logASplit(logger, "step 1 complete");
            verifyNotStopped();

            // second step
            Trace.beginSection("LoadAllApps");
            List<LauncherActivityInfo> allActivityList;
            try {
               allActivityList = loadAllApps();
            } finally {
                Trace.endSection();
            }
            logASplit(logger, "loadAllApps");

            verifyNotStopped();
            //设置是否显示抽屉
            if (MxSettings.getInstance().isDrawerEnable()) {
                mResults.bindAllApps();
                logASplit(logger, "bindAllApps");
            } else {
                mResults.bindAllAppToWorkspace();
                logASplit(logger, "bindAllAppToWorkspace");
            }

            verifyNotStopped();
            IconCacheUpdateHandler updateHandler = mIconCache.getUpdateHandler();
            setIgnorePackages(updateHandler);
            updateHandler.updateIcons(allActivityList,
                    LauncherActivityCachingLogic.newInstance(mApp.getContext()),
                    mApp.getModel()::onPackageIconsUpdated);
            logASplit(logger, "update icon cache");

            if (FeatureFlags.ENABLE_DEEP_SHORTCUT_ICON_CACHE.get()) {
                verifyNotStopped();
                logASplit(logger, "save shortcuts in icon cache");
                updateHandler.updateIcons(allShortcuts, new ShortcutCachingLogic(),
                        mApp.getModel()::onPackageIconsUpdated);
            }

            // Take a break
            waitForIdle();
            logASplit(logger, "step 2 complete");
            verifyNotStopped();

            // third step
            List<ShortcutInfo> allDeepShortcuts = loadDeepShortcuts();
            logASplit(logger, "loadDeepShortcuts");

            verifyNotStopped();
            mResults.bindDeepShortcuts();
            logASplit(logger, "bindDeepShortcuts");

            if (FeatureFlags.ENABLE_DEEP_SHORTCUT_ICON_CACHE.get()) {
                verifyNotStopped();
                logASplit(logger, "save deep shortcuts in icon cache");
                updateHandler.updateIcons(allDeepShortcuts,
                        new ShortcutCachingLogic(), (pkgs, user) -> { });
            }

            // Take a break
            waitForIdle();
            logASplit(logger, "step 3 complete");
            verifyNotStopped();

            // fourth step
            List<ComponentWithLabelAndIcon> allWidgetsList =
                    mBgDataModel.widgetsModel.update(mApp, null);
            logASplit(logger, "load widgets");

            verifyNotStopped();
            mResults.bindWidgets();
            logASplit(logger, "bindWidgets");
            verifyNotStopped();

            updateHandler.updateIcons(allWidgetsList,
                    new ComponentWithIconCachingLogic(mApp.getContext(), true),
                    mApp.getModel()::onWidgetLabelsUpdated);
            logASplit(logger, "save widgets in icon cache");

            // fifth step
            if (FeatureFlags.FOLDER_NAME_SUGGEST.get()) {
                loadFolderNames();
            }

            mResults.finishBindAllApps(mApp.getModel());

            verifyNotStopped();
            updateHandler.finish();
            logASplit(logger, "finish icon update");

            mModelDelegate.modelLoadComplete();
            transaction.commit();
            memoryLogger.clearLogs();
        } catch (CancellationException e) {
            // Loader stopped, ignore
            logASplit(logger, "Cancelled");
        } catch (Exception e) {
            memoryLogger.printLogs();
            throw e;
        } finally {
            logger.dumpToLog();
        }
        TraceHelper.INSTANCE.endSection(traceToken);
    }

    public synchronized void stopLocked() {
        mStopped = true;
        this.notify();
    }

    private void loadWorkspace(List<ShortcutInfo> allDeepShortcuts, LoaderMemoryLogger logger) {
        loadWorkspace(allDeepShortcuts, LauncherSettings.Favorites.getContentUri(),
                null /* selection */, logger);
    }

    protected void loadWorkspace(
            List<ShortcutInfo> allDeepShortcuts, Uri contentUri, String selection) {
        loadWorkspace(allDeepShortcuts, contentUri, selection, null);
    }

    protected void loadWorkspace(
            List<ShortcutInfo> allDeepShortcuts,
            Uri contentUri,
            String selection,
            @Nullable LoaderMemoryLogger logger) {
        //先创建一些对象
        final Context context = mApp.getContext();
        final ContentResolver contentResolver = context.getContentResolver();
        final PackageManagerHelper pmHelper = new PackageManagerHelper(context);
        final boolean isSafeMode = pmHelper.isSafeMode();
        final boolean isSdCardReady = Utilities.isBootCompleted();
        final WidgetManagerHelper widgetHelper = new WidgetManagerHelper(context);

        boolean clearDb = false;
        if (!GridSizeMigrationTaskV2.migrateGridIfNeeded(context)) {
            // Migration failed. Clear workspace.
            clearDb = true;
        }

        if (clearDb) {
            Log.d(TAG, "loadWorkspace: resetting launcher database");
            LauncherSettings.Settings.call(contentResolver,
                    LauncherSettings.Settings.METHOD_CREATE_EMPTY_DB);
        }

        Log.d(TAG, "loadWorkspace: loading default favorites");
        LauncherSettings.Settings.call(contentResolver,
                LauncherSettings.Settings.METHOD_LOAD_DEFAULT_FAVORITES);

        synchronized (mBgDataModel) {
            mBgDataModel.clear();
            mPendingPackages.clear();

            final HashMap<PackageUserKey, SessionInfo> installingPkgs =
                    mSessionHelper.getActiveSessions();
            installingPkgs.forEach(mApp.getIconCache()::updateSessionCache);

            final PackageUserKey tempPackageKey = new PackageUserKey(null, null);
            mFirstScreenBroadcast = new FirstScreenBroadcast(installingPkgs);

            Map<ShortcutKey, ShortcutInfo> shortcutKeyToPinnedShortcuts = new HashMap<>();
            //获取数据库
            final LoaderCursor c = new LoaderCursor(
                    contentResolver.query(contentUri, null, selection, null, null), contentUri,
                    mApp, mUserManagerState);
            final Bundle extras = c.getExtras();
            mDbName = extras == null
                    ? null : extras.getString(LauncherSettings.Settings.EXTRA_DB_NAME);
            try {
                final int appWidgetIdIndex = c.getColumnIndexOrThrow(
                        LauncherSettings.Favorites.APPWIDGET_ID);
                final int appWidgetProviderIndex = c.getColumnIndexOrThrow(
                        LauncherSettings.Favorites.APPWIDGET_PROVIDER);
                final int spanXIndex = c.getColumnIndexOrThrow
                        (LauncherSettings.Favorites.SPANX);
                final int spanYIndex = c.getColumnIndexOrThrow(
                        LauncherSettings.Favorites.SPANY);
                final int rankIndex = c.getColumnIndexOrThrow(
                        LauncherSettings.Favorites.RANK);
                final int optionsIndex = c.getColumnIndexOrThrow(
                        LauncherSettings.Favorites.OPTIONS);
                final int sourceContainerIndex = c.getColumnIndexOrThrow(
                        LauncherSettings.Favorites.APPWIDGET_SOURCE);

                final LongSparseArray<Boolean> unlockedUsers = new LongSparseArray<>();

                mUserManagerState.init(mUserCache, mUserManager);

                for (UserHandle user : mUserCache.getUserProfiles()) {
                    long serialNo = mUserCache.getSerialNumberForUser(user);
                    boolean userUnlocked = mUserManager.isUserUnlocked(user);

                    // We can only query for shortcuts when the user is unlocked.
                    if (userUnlocked) {
                        QueryResult pinnedShortcuts = new ShortcutRequest(context, user)
                                .query(ShortcutRequest.PINNED);
                        if (pinnedShortcuts.wasSuccess()) {
                            for (ShortcutInfo shortcut : pinnedShortcuts) {
                                shortcutKeyToPinnedShortcuts.put(ShortcutKey.fromInfo(shortcut),
                                        shortcut);
                            }
                        } else {
                            // Shortcut manager can fail due to some race condition when the
                            // lock state changes too frequently. For the purpose of the loading
                            // shortcuts, consider the user is still locked.
                            userUnlocked = false;
                        }
                    }
                    unlockedUsers.put(serialNo, userUnlocked);
                }

                WorkspaceItemInfo info;
                LauncherAppWidgetInfo appWidgetInfo;
                LauncherAppWidgetProviderInfo widgetProviderInfo;
                Intent intent;
                String targetPkg;
                List<IconRequestInfo<WorkspaceItemInfo>> iconRequestInfos = new ArrayList<>();

                while (!mStopped && c.moveToNext()) {
                    try {
                        if (c.user == null) {
                            // User has been deleted, remove the item.
                            c.markDeleted("User has been deleted");
                            continue;
                        }

                        boolean allowMissingTarget = false;
                        //按类型区分
                        //图标类型
                        switch (c.itemType) {
                        case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                        case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION:
                        case LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT:
                            //解析快捷方式的intent并检查其有效性。
                            //根据用户的设置确定禁用状态。
                            //确定快捷方式的目标包名，并检查其有效性。
                            //处理非深度快捷方式的情况，包括检查目标组件是否存在，查找备用活动组件等。
                            //处理目标包名无效的情况，如应用程序尚未恢复、包在SD卡上但不可用等。
                            //根据快捷方式的类型和属性加载相应的图标，并创建相应的WorkspaceItemInfo对象。
                            //将创建的WorkspaceItemInfo对象应用于快捷方式，并将其添加到数据模型中。

                            //获取intent
                            //来自xml(首次)\packmanager\快捷方式生成的
                            intent = c.parseIntent();
                            if (intent == null) {
                                c.markDeleted("Invalid or null intent");
                                continue;
                            }

                            int disabledState = mUserManagerState.isUserQuiet(c.serialNumber)
                                    ? WorkspaceItemInfo.FLAG_DISABLED_QUIET_USER : 0;
                            ComponentName cn = intent.getComponent();
                            targetPkg = cn == null ? intent.getPackage() : cn.getPackageName();

                            if (TextUtils.isEmpty(targetPkg) &&
                                    c.itemType != LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT) {
                                c.markDeleted("Only legacy shortcuts can have null package");
                                continue;
                            }

                            // If there is no target package, its an implicit intent
                            // (legacy shortcut) which is always valid
                            boolean validTarget = TextUtils.isEmpty(targetPkg) ||
                                    mLauncherApps.isPackageEnabled(targetPkg, c.user);

                            // If it's a deep shortcut, we'll use pinned shortcuts to restore it
                            if (cn != null && validTarget && c.itemType
                                    != LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
                                // If the apk is present and the shortcut points to a specific
                                // component.

                                // If the component is already present
                                if (mLauncherApps.isActivityEnabled(cn, c.user)) {
                                    // no special handling necessary for this item
                                    c.markRestored();
                                } else {
                                    // Gracefully try to find a fallback activity.
                                    intent = pmHelper.getAppLaunchIntent(targetPkg, c.user);
                                    if (intent != null) {
                                        c.restoreFlag = 0;
                                        c.updater().put(
                                                LauncherSettings.Favorites.INTENT,
                                                intent.toUri(0)).commit();
                                        cn = intent.getComponent();
                                    } else {
                                        c.markDeleted("Unable to find a launch target");
                                        continue;
                                    }
                                }
                            }
                            // else if cn == null => can't infer much, leave it
                            // else if !validPkg => could be restored icon or missing sd-card

                            if (!TextUtils.isEmpty(targetPkg) && !validTarget) {
                                // Points to a valid app (superset of cn != null) but the apk
                                // is not available.

                                if (c.restoreFlag != 0) {
                                    // Package is not yet available but might be
                                    // installed later.
                                    FileLog.d(TAG, "package not yet restored: " + targetPkg);

                                    tempPackageKey.update(targetPkg, c.user);
                                    if (c.hasRestoreFlag(WorkspaceItemInfo.FLAG_RESTORE_STARTED)) {
                                        // Restore has started once.
                                    } else if (installingPkgs.containsKey(tempPackageKey)) {
                                        // App restore has started. Update the flag
                                        c.restoreFlag |= WorkspaceItemInfo.FLAG_RESTORE_STARTED;
                                        c.updater().put(LauncherSettings.Favorites.RESTORED,
                                                c.restoreFlag).commit();
                                    } else {
                                        c.markDeleted("Unrestored app removed: " + targetPkg);
                                        continue;
                                    }
                                } else if (pmHelper.isAppOnSdcard(targetPkg, c.user)) {
                                    // Package is present but not available.
                                    disabledState |= WorkspaceItemInfo.FLAG_DISABLED_NOT_AVAILABLE;
                                    // Add the icon on the workspace anyway.
                                    // 这个时候仍然把图标放置到桌面上
                                    allowMissingTarget = true;
                                } else if (!isSdCardReady) {
                                    // SdCard is not ready yet. Package might get available,
                                    // once it is ready.
                                    Log.d(TAG, "Missing pkg, will check later: " + targetPkg);
                                    mPendingPackages.add(new PackageUserKey(targetPkg, c.user));
                                    // Add the icon on the workspace anyway.
                                    allowMissingTarget = true;
                                } else {
                                    // Do not wait for external media load anymore.
                                    c.markDeleted("Invalid package removed: " + targetPkg);
                                    continue;
                                }
                            }

                            if ((c.restoreFlag & WorkspaceItemInfo.FLAG_SUPPORTS_WEB_UI) != 0) {
                                validTarget = false;
                            }

                            if (validTarget) {
                                // The shortcut points to a valid target (either no target
                                // or something which is ready to be used)
                                c.markRestored();
                            }

                            boolean useLowResIcon = !c.isOnWorkspaceOrHotseat();

                            if (c.restoreFlag != 0) {
                                // Already verified above that user is same as default user
                                info = c.getRestoredItemInfo(intent);
                            } else if (c.itemType ==
                                    LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
                                info = c.getAppShortcutInfo(
                                        intent,
                                        allowMissingTarget,
                                        useLowResIcon,
                                        !FeatureFlags.ENABLE_BULK_WORKSPACE_ICON_LOADING.get());
                            } else if (c.itemType ==
                                    LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT) {

                                ShortcutKey key = ShortcutKey.fromIntent(intent, c.user);
                                if (unlockedUsers.get(c.serialNumber)) {
                                    ShortcutInfo pinnedShortcut =
                                            shortcutKeyToPinnedShortcuts.get(key);
                                    if (pinnedShortcut == null) {
                                        // The shortcut is no longer valid.
                                        c.markDeleted("Pinned shortcut not found");
                                        continue;
                                    }
                                    info = new WorkspaceItemInfo(pinnedShortcut, context);
                                    // If the pinned deep shortcut is no longer published,
                                    // use the last saved icon instead of the default.
                                    mIconCache.getShortcutIcon(info, pinnedShortcut, c::loadIcon);

                                    if (pmHelper.isAppSuspended(
                                            pinnedShortcut.getPackage(), info.user)) {
                                        info.runtimeStatusFlags |= FLAG_DISABLED_SUSPENDED;
                                    }
                                    intent = info.getIntent();
                                    allDeepShortcuts.add(pinnedShortcut);
                                } else {
                                    // Create a shortcut info in disabled mode for now.
                                    info = c.loadSimpleWorkspaceItem();
                                    info.runtimeStatusFlags |= FLAG_DISABLED_LOCKED_USER;
                                }
                            } else { // item type == ITEM_TYPE_SHORTCUT
                                info = c.loadSimpleWorkspaceItem();

                                // Shortcuts are only available on the primary profile
                                if (!TextUtils.isEmpty(targetPkg)
                                        && pmHelper.isAppSuspended(targetPkg, c.user)) {
                                    disabledState |= FLAG_DISABLED_SUSPENDED;
                                }
                                info.options = c.getInt(optionsIndex);

                                // App shortcuts that used to be automatically added to Launcher
                                // didn't always have the correct intent flags set, so do that
                                // here
                                if (intent.getAction() != null &&
                                    intent.getCategories() != null &&
                                    intent.getAction().equals(Intent.ACTION_MAIN) &&
                                    intent.getCategories().contains(Intent.CATEGORY_LAUNCHER)) {
                                    intent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK |
                                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                                }
                            }

                            if (info != null) {
                                if (info.itemType
                                        != LauncherSettings.Favorites.ITEM_TYPE_DEEP_SHORTCUT) {
                                    // Skip deep shortcuts; their title and icons have already been
                                    // loaded above.
                                    // 添加创建应用图标的请求对象到请求列表
                                    iconRequestInfos.add(
                                            c.createIconRequestInfo(info, useLowResIcon));
                                }

                                c.applyCommonProperties(info);

                                info.intent = intent;
                                info.rank = c.getInt(rankIndex);
                                info.spanX = 1;
                                info.spanY = 1;
                                info.runtimeStatusFlags |= disabledState;
                                if (isSafeMode && !isSystemApp(context, intent)) {
                                    info.runtimeStatusFlags |= FLAG_DISABLED_SAFEMODE;
                                }
                                    LauncherActivityInfo activityInfo = c.getLauncherActivityInfo();
                                    if (activityInfo != null) {
                                        info.setProgressLevel(
                                                PackageManagerHelper
                                                    .getLoadingProgress(activityInfo),
                                                PackageInstallInfo.STATUS_INSTALLED_DOWNLOADING);
                                    }

                                if (c.restoreFlag != 0 && !TextUtils.isEmpty(targetPkg)) {
                                    tempPackageKey.update(targetPkg, c.user);
                                    SessionInfo si = installingPkgs.get(tempPackageKey);
                                        if (si == null) {
                                            info.runtimeStatusFlags &=
                                                ~ItemInfoWithIcon.FLAG_INSTALL_SESSION_ACTIVE;
                                        } else if (activityInfo == null) {
                                            int installProgress = (int) (si.getProgress() * 100);

                                            info.setProgressLevel(
                                                    installProgress,
                                                    PackageInstallInfo.STATUS_INSTALLING);
                                        }
                                }

                                //最终目的?
                                c.checkAndAddItem(info, mBgDataModel, logger);
                            } else {
                                throw new RuntimeException("Unexpected null WorkspaceItemInfo");
                            }
                            break;

                        case LauncherSettings.Favorites.ITEM_TYPE_FOLDER:
                            FolderInfo folderInfo = mBgDataModel.findOrMakeFolder(c.id);
                            c.applyCommonProperties(folderInfo);

                            // Do not trim the folder label, as is was set by the user.
                            folderInfo.title = c.getString(c.titleIndex);
                            folderInfo.spanX = 1;
                            folderInfo.spanY = 1;
                            folderInfo.options = c.getInt(optionsIndex);

                            // no special handling required for restored folders
                            c.markRestored();

                            c.checkAndAddItem(folderInfo, mBgDataModel, logger);
                            break;

                        case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET:
                            if (WidgetsModel.GO_DISABLE_WIDGETS) {
                                c.markDeleted("Only legacy shortcuts can have null package");
                                continue;
                            }
                            // Follow through
                        case LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET:
                            // Read all Launcher-specific widget details
                            boolean customWidget = c.itemType ==
                                LauncherSettings.Favorites.ITEM_TYPE_CUSTOM_APPWIDGET;

                            int appWidgetId = c.getInt(appWidgetIdIndex);
                            String savedProvider = c.getString(appWidgetProviderIndex);
                            final ComponentName component;

                            boolean isSearchWidget = (c.getInt(optionsIndex)
                                    & LauncherAppWidgetInfo.OPTION_SEARCH_WIDGET) != 0;
                            if (isSearchWidget) {
                                component  = QsbContainerView.getSearchComponentName(context);
                                if (component == null) {
                                    c.markDeleted("Discarding SearchWidget without packagename ");
                                    continue;
                                }
                            } else {
                                component = ComponentName.unflattenFromString(savedProvider);
                            }
                            final boolean isIdValid = !c.hasRestoreFlag(
                                    LauncherAppWidgetInfo.FLAG_ID_NOT_VALID);
                            final boolean wasProviderReady = !c.hasRestoreFlag(
                                    LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY);

                            ComponentKey providerKey = new ComponentKey(component, c.user);
                            if (!mWidgetProvidersMap.containsKey(providerKey)) {
                                mWidgetProvidersMap.put(providerKey,
                                        widgetHelper.findProvider(component, c.user));
                            }
                            final AppWidgetProviderInfo provider =
                                    mWidgetProvidersMap.get(providerKey);

                            final boolean isProviderReady = isValidProvider(provider);
                            if (!isSafeMode && !customWidget &&
                                    wasProviderReady && !isProviderReady) {
                                c.markDeleted(
                                        "Deleting widget that isn't installed anymore: "
                                        + provider);
                            } else {
                                if (isProviderReady) {
                                    appWidgetInfo = new LauncherAppWidgetInfo(appWidgetId,
                                            provider.provider);

                                    // The provider is available. So the widget is either
                                    // available or not available. We do not need to track
                                    // any future restore updates.
                                    int status = c.restoreFlag &
                                            ~LauncherAppWidgetInfo.FLAG_RESTORE_STARTED &
                                            ~LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY;
                                    if (!wasProviderReady) {
                                        // If provider was not previously ready, update the
                                        // status and UI flag.

                                        // Id would be valid only if the widget restore broadcast was received.
                                        if (isIdValid) {
                                            status |= LauncherAppWidgetInfo.FLAG_UI_NOT_READY;
                                        }
                                    }
                                    appWidgetInfo.restoreStatus = status;
                                } else {
                                    Log.v(TAG, "Widget restore pending id=" + c.id
                                            + " appWidgetId=" + appWidgetId
                                            + " status =" + c.restoreFlag);
                                    appWidgetInfo = new LauncherAppWidgetInfo(appWidgetId,
                                            component);
                                    appWidgetInfo.restoreStatus = c.restoreFlag;

                                    tempPackageKey.update(component.getPackageName(), c.user);
                                    SessionInfo si =
                                            installingPkgs.get(tempPackageKey);
                                    Integer installProgress = si == null
                                            ? null
                                            : (int) (si.getProgress() * 100);

                                    if (c.hasRestoreFlag(LauncherAppWidgetInfo.FLAG_RESTORE_STARTED)) {
                                        // Restore has started once.
                                    } else if (installProgress != null) {
                                        // App restore has started. Update the flag
                                        appWidgetInfo.restoreStatus |=
                                                LauncherAppWidgetInfo.FLAG_RESTORE_STARTED;
                                    } else if (!isSafeMode) {
                                        c.markDeleted("Unrestored widget removed: " + component);
                                        continue;
                                    }

                                    appWidgetInfo.installProgress =
                                            installProgress == null ? 0 : installProgress;
                                }
                                if (appWidgetInfo.hasRestoreFlag(
                                        LauncherAppWidgetInfo.FLAG_DIRECT_CONFIG)) {
                                    appWidgetInfo.bindOptions = c.parseIntent();
                                }

                                c.applyCommonProperties(appWidgetInfo);
                                appWidgetInfo.spanX = c.getInt(spanXIndex);
                                appWidgetInfo.spanY = c.getInt(spanYIndex);
                                appWidgetInfo.options = c.getInt(optionsIndex);
                                appWidgetInfo.user = c.user;
                                appWidgetInfo.sourceContainer = c.getInt(sourceContainerIndex);

                                if (appWidgetInfo.spanX <= 0 || appWidgetInfo.spanY <= 0) {
                                    c.markDeleted("Widget has invalid size: "
                                            + appWidgetInfo.spanX + "x" + appWidgetInfo.spanY);
                                    continue;
                                }
                                widgetProviderInfo =
                                        widgetHelper.getLauncherAppWidgetInfo(appWidgetId);
                                if (widgetProviderInfo != null
                                        && (appWidgetInfo.spanX < widgetProviderInfo.minSpanX
                                        || appWidgetInfo.spanY < widgetProviderInfo.minSpanY)) {
                                    FileLog.d(TAG, "Widget " + widgetProviderInfo.getComponent()
                                            + " minSizes not meet: span=" + appWidgetInfo.spanX
                                            + "x" + appWidgetInfo.spanY + " minSpan="
                                            + widgetProviderInfo.minSpanX + "x"
                                            + widgetProviderInfo.minSpanY);
                                    logWidgetInfo(mApp.getInvariantDeviceProfile(),
                                            widgetProviderInfo);
                                }
                                if (!c.isOnWorkspaceOrHotseat()) {
                                    c.markDeleted("Widget found where container != " +
                                            "CONTAINER_DESKTOP nor CONTAINER_HOTSEAT - ignoring!");
                                    continue;
                                }

                                if (!customWidget) {
                                    String providerName =
                                            appWidgetInfo.providerName.flattenToString();
                                    if (!providerName.equals(savedProvider) ||
                                            (appWidgetInfo.restoreStatus != c.restoreFlag)) {
                                        c.updater()
                                                .put(LauncherSettings.Favorites.APPWIDGET_PROVIDER,
                                                        providerName)
                                                .put(LauncherSettings.Favorites.RESTORED,
                                                        appWidgetInfo.restoreStatus)
                                                .commit();
                                    }
                                }

                                if (appWidgetInfo.restoreStatus !=
                                        LauncherAppWidgetInfo.RESTORE_COMPLETED) {
                                    appWidgetInfo.pendingItemInfo = WidgetsModel.newPendingItemInfo(
                                            mApp.getContext(),
                                            appWidgetInfo.providerName,
                                            appWidgetInfo.user);
                                    mIconCache.getTitleAndIconForApp(
                                            appWidgetInfo.pendingItemInfo, false);
                                }

                                c.checkAndAddItem(appWidgetInfo, mBgDataModel);
                            }
                            break;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Desktop items loading interrupted", e);
                    }
                }
                // 根据请求列表获取应用的标题和Icon
                if (FeatureFlags.ENABLE_BULK_WORKSPACE_ICON_LOADING.get()) {
                    Trace.beginSection("LoadWorkspaceIconsInBulk");
                    try {
                        //ENABLE_BULK_WORKSPACE_ICON_LOADING就会在这里一次性批量加载图标
                        mIconCache.getTitlesAndIconsInBulk(iconRequestInfos);
                        for (IconRequestInfo<WorkspaceItemInfo> iconRequestInfo :
                                iconRequestInfos) {
                            WorkspaceItemInfo wai = iconRequestInfo.itemInfo;

                            //在此处可以替换所有icon
                            Bitmap bi = getIconBitmap(iconRequestInfo.itemInfo.intent.getPackage());
                            if(bi != null){
                                iconRequestInfo.itemInfo.bitmap = new BitmapInfo(bi, iconRequestInfo.itemInfo.bitmap.color);
                            }

                            if (mIconCache.isDefaultIcon(wai.bitmap, wai.user)) {
                                iconRequestInfo.loadWorkspaceIcon(mApp.getContext());
                            }
                        }
                    } finally {
                        Trace.endSection();
                    }
                }
            } finally {
                IOUtils.closeSilently(c);
            }

            // Load delegate items
            mModelDelegate.loadItems(mUserManagerState, shortcutKeyToPinnedShortcuts);

            // Load string cache
            mModelDelegate.loadStringCache(mBgDataModel.stringCache);

            // Break early if we've stopped loading
            if (mStopped) {
                mBgDataModel.clear();
                return;
            }

            // Remove dead items
            mItemsDeleted = c.commitDeleted();

            // Sort the folder items, update ranks, and make sure all preview items are high res.
            FolderGridOrganizer verifier =
                    new FolderGridOrganizer(mApp.getInvariantDeviceProfile());
            for (FolderInfo folder : mBgDataModel.folders) {
                Collections.sort(folder.contents, Folder.ITEM_POS_COMPARATOR);
                verifier.setFolderInfo(folder);
                int size = folder.contents.size();

                // Update ranks here to ensure there are no gaps caused by removed folder items.
                // Ranks are the source of truth for folder items, so cellX and cellY can be ignored
                // for now. Database will be updated once user manually modifies folder.
                for (int rank = 0; rank < size; ++rank) {
                    WorkspaceItemInfo info = folder.contents.get(rank);
                    info.rank = rank;

                    if (info.usingLowResIcon()
                            && info.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION
                            && verifier.isItemInPreview(info.rank)) {
                        mIconCache.getTitleAndIcon(info, false);
                    }
                }
            }

            c.commitRestoredItems();
        }
    }

    private void setIgnorePackages(IconCacheUpdateHandler updateHandler) {
        // Ignore packages which have a promise icon.
        synchronized (mBgDataModel) {
            for (ItemInfo info : mBgDataModel.itemsIdMap) {
                if (info instanceof WorkspaceItemInfo) {
                    WorkspaceItemInfo si = (WorkspaceItemInfo) info;
                    if (si.isPromise() && si.getTargetComponent() != null) {
                        updateHandler.addPackagesToIgnore(
                                si.user, si.getTargetComponent().getPackageName());
                    }
                } else if (info instanceof LauncherAppWidgetInfo) {
                    LauncherAppWidgetInfo lawi = (LauncherAppWidgetInfo) info;
                    if (lawi.hasRestoreFlag(LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY)) {
                        updateHandler.addPackagesToIgnore(
                                lawi.user, lawi.providerName.getPackageName());
                    }
                }
            }
        }
    }

    private void sanitizeData() {
        Context context = mApp.getContext();
        ContentResolver contentResolver = context.getContentResolver();
        if (mItemsDeleted) {
            // Remove any empty folder
            int[] deletedFolderIds = LauncherSettings.Settings
                    .call(contentResolver,
                            LauncherSettings.Settings.METHOD_DELETE_EMPTY_FOLDERS)
                    .getIntArray(LauncherSettings.Settings.EXTRA_VALUE);
            synchronized (mBgDataModel) {
                for (int folderId : deletedFolderIds) {
                    mBgDataModel.workspaceItems.remove(mBgDataModel.folders.get(folderId));
                    mBgDataModel.folders.remove(folderId);
                    mBgDataModel.itemsIdMap.remove(folderId);
                }
            }

        }
        // Remove any ghost widgets
        LauncherSettings.Settings.call(contentResolver,
                LauncherSettings.Settings.METHOD_REMOVE_GHOST_WIDGETS);

        // Update pinned state of model shortcuts
        mBgDataModel.updateShortcutPinnedState(context);

        if (!Utilities.isBootCompleted() && !mPendingPackages.isEmpty()) {
            context.registerReceiver(
                    new SdCardAvailableReceiver(mApp, mPendingPackages),
                    new IntentFilter(Intent.ACTION_BOOT_COMPLETED),
                    null,
                    MODEL_EXECUTOR.getHandler());
        }
    }

    private List<LauncherActivityInfo> loadAllApps() {
        final List<UserHandle> profiles = mUserCache.getUserProfiles();
        List<LauncherActivityInfo> allActivityList = new ArrayList<>();
        // Clear the list of apps
        mBgAllAppsList.clear();

        List<IconRequestInfo<AppInfo>> iconRequestInfos = new ArrayList<>();
        for (UserHandle user : profiles) {
            // Query for the set of apps
            final List<LauncherActivityInfo> apps = mLauncherApps.getActivityList(null, user);
            // Fail if we don't have any apps
            // TODO: Fix this. Only fail for the current user.
            if (apps == null || apps.isEmpty()) {
                return allActivityList;
            }
            boolean quietMode = mUserManagerState.isUserQuiet(user);
            Log.d(TAG, "loadAllApps: " + !FeatureFlags.ENABLE_BULK_ALL_APPS_ICON_LOADING.get());
            // Create the ApplicationInfos
            for (int i = 0; i < apps.size(); i++) {
                LauncherActivityInfo app = apps.get(i);
                AppInfo appInfo = new AppInfo(app, user, quietMode);

                iconRequestInfos.add(new IconRequestInfo<>(
                        appInfo, app, /* useLowResIcon= */ false));
                mBgAllAppsList.add(
                        appInfo, app, !FeatureFlags.ENABLE_BULK_ALL_APPS_ICON_LOADING.get());
            }
            allActivityList.addAll(apps);
        }


        if (FeatureFlags.PROMISE_APPS_IN_ALL_APPS.get()) {
            // get all active sessions and add them to the all apps list
            for (PackageInstaller.SessionInfo info :
                    mSessionHelper.getAllVerifiedSessions()) {
                AppInfo promiseAppInfo = mBgAllAppsList.addPromiseApp(
                        mApp.getContext(),
                        PackageInstallInfo.fromInstallingState(info),
                        !FeatureFlags.ENABLE_BULK_ALL_APPS_ICON_LOADING.get());

                if (promiseAppInfo != null) {
                    iconRequestInfos.add(new IconRequestInfo<>(
                            promiseAppInfo,
                            /* launcherActivityInfo= */ null,
                            promiseAppInfo.usingLowResIcon()));
                }
            }
        }

        if (FeatureFlags.ENABLE_BULK_ALL_APPS_ICON_LOADING.get()) {
            Trace.beginSection("LoadAllAppsIconsInBulk");
            try {
                mIconCache.getTitlesAndIconsInBulk(iconRequestInfos);
                iconRequestInfos.forEach(iconRequestInfo ->
                        mBgAllAppsList.updateSectionName(iconRequestInfo.itemInfo));
            } finally {
                Trace.endSection();
            }
        }

        mBgAllAppsList.setFlags(FLAG_QUIET_MODE_ENABLED,
                mUserManagerState.isAnyProfileQuietModeEnabled());
        mBgAllAppsList.setFlags(FLAG_HAS_SHORTCUT_PERMISSION,
                hasShortcutsPermission(mApp.getContext()));
        mBgAllAppsList.setFlags(FLAG_QUIET_MODE_CHANGE_PERMISSION,
                mApp.getContext().checkSelfPermission("android.permission.MODIFY_QUIET_MODE")
                        == PackageManager.PERMISSION_GRANTED);
        mBgAllAppsList.getAndResetChangeFlag();
        return allActivityList;
    }

    private List<ShortcutInfo> loadDeepShortcuts() {
        List<ShortcutInfo> allShortcuts = new ArrayList<>();
        mBgDataModel.deepShortcutMap.clear();

        if (mBgAllAppsList.hasShortcutHostPermission()) {
            for (UserHandle user : mUserCache.getUserProfiles()) {
                if (mUserManager.isUserUnlocked(user)) {
                    List<ShortcutInfo> shortcuts = new ShortcutRequest(mApp.getContext(), user)
                            .query(ShortcutRequest.ALL);
                    allShortcuts.addAll(shortcuts);
                    mBgDataModel.updateDeepShortcutCounts(null, user, shortcuts);
                }
            }
        }
        return allShortcuts;
    }

    private void loadFolderNames() {
        FolderNameProvider provider = FolderNameProvider.newInstance(mApp.getContext(),
                mBgAllAppsList.data, mBgDataModel.folders);

        synchronized (mBgDataModel) {
            for (int i = 0; i < mBgDataModel.folders.size(); i++) {
                FolderNameInfos suggestionInfos = new FolderNameInfos();
                FolderInfo info = mBgDataModel.folders.valueAt(i);
                if (info.suggestedFolderNames == null) {
                    provider.getSuggestedFolderName(mApp.getContext(), info.contents,
                            suggestionInfos);
                    info.suggestedFolderNames = suggestionInfos;
                }
            }
        }
    }

    public static boolean isValidProvider(AppWidgetProviderInfo provider) {
        return (provider != null) && (provider.provider != null)
                && (provider.provider.getPackageName() != null);
    }

    @SuppressLint("NewApi") // Already added API check.
    private static void logWidgetInfo(InvariantDeviceProfile idp,
            LauncherAppWidgetProviderInfo widgetProviderInfo) {
        Point cellSize = new Point();
        for (DeviceProfile deviceProfile : idp.supportedProfiles) {
            deviceProfile.getCellSize(cellSize);
            FileLog.d(TAG, "DeviceProfile available width: " + deviceProfile.availableWidthPx
                    + ", available height: " + deviceProfile.availableHeightPx
                    + ", cellLayoutBorderSpacePx Horizontal: "
                    + deviceProfile.cellLayoutBorderSpacePx.x
                    + ", cellLayoutBorderSpacePx Vertical: "
                    + deviceProfile.cellLayoutBorderSpacePx.y
                    + ", cellSize: " + cellSize);
        }

        StringBuilder widgetDimension = new StringBuilder();
        widgetDimension.append("Widget dimensions:\n")
                .append("minResizeWidth: ")
                .append(widgetProviderInfo.minResizeWidth)
                .append("\n")
                .append("minResizeHeight: ")
                .append(widgetProviderInfo.minResizeHeight)
                .append("\n")
                .append("defaultWidth: ")
                .append(widgetProviderInfo.minWidth)
                .append("\n")
                .append("defaultHeight: ")
                .append(widgetProviderInfo.minHeight)
                .append("\n");
        if (Utilities.ATLEAST_S) {
            widgetDimension.append("targetCellWidth: ")
                    .append(widgetProviderInfo.targetCellWidth)
                    .append("\n")
                    .append("targetCellHeight: ")
                    .append(widgetProviderInfo.targetCellHeight)
                    .append("\n")
                    .append("maxResizeWidth: ")
                    .append(widgetProviderInfo.maxResizeWidth)
                    .append("\n")
                    .append("maxResizeHeight: ")
                    .append(widgetProviderInfo.maxResizeHeight)
                    .append("\n");
        }
        FileLog.d(TAG, widgetDimension.toString());
    }

    private static void logASplit(final TimingLogger logger, final String label) {
        logger.addSplit(label);
        if (DEBUG) {
            Log.d(TAG, label);
        }
    }


    public static Bitmap getTestBitmap(){
        // 定义图像的宽度和高度
        int width = 200;
        int height = 200;
        // 创建一个空的 Bitmap 对象
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // 创建一个 Canvas 对象，将 Bitmap 绑定到 Canvas 上
        Canvas canvas = new Canvas(bitmap);
        // 创建一个 Paint 对象，用于绘制图像
        Paint paint = new Paint();
        // 定义左边和右边的颜色
        int leftColor = Color.RED;
        int rightColor = Color.BLUE;
        // 绘制左边的圆形区域
        RectF leftRect = new RectF(0, 0, width / 2, height);
        paint.setColor(leftColor);
        canvas.drawArc(leftRect, 90, 180, true, paint);
        // 绘制右边的圆形区域
        RectF rightRect = new RectF(width / 2, 0, width, height);
        paint.setColor(rightColor);
        canvas.drawArc(rightRect, -90, 180, true, paint);

        return bitmap;
    }

    public Bitmap getIconBitmap(String packageName) {
        String folderPath = mApp.getContext().getFilesDir().getPath() + "/icons";
        String iconFileName = packageName + ".png"; // 假设图标文件的扩展名为png

        String iconFilePath = folderPath + "/" + iconFileName;

        Bitmap iconBitmap = null;

        try {
            // 尝试从文件路径中解码位图
            iconBitmap = BitmapFactory.decodeFile(iconFilePath);
        } catch (Exception e) {
            // 处理异常或文件不存在的情况
            Log.e(TAG, "getIconBitmap: dont have " + packageName);
            e.printStackTrace();
        }

        return iconBitmap;
    }

}
