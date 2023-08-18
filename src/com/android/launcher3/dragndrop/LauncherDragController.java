/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.dragndrop;

import static com.android.launcher3.AbstractFloatingView.TYPE_DISCOVERY_BOUNCE;
import static com.android.launcher3.LauncherAnimUtils.SPRING_LOADED_EXIT_DELAY;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.accessibility.DragViewStateAnnouncer;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.testing.TestProtocol;

/**
 * Drag controller for Launcher activity
 */
public class LauncherDragController extends DragController<Launcher> {

    private static final boolean PROFILE_DRAWING_DURING_DRAG = false;

    private final FlingToDeleteHelper mFlingToDeleteHelper;

    public LauncherDragController(Launcher launcher) {
        super(launcher);
        mFlingToDeleteHelper = new FlingToDeleteHelper(launcher);
    }

    /**
     * 如果启用了调试跟踪，并且打印调试消息。
     * 隐藏键盘并关闭打开的浮动视图。
     * 设置拖动选项。
     * 如果存在模拟的拖放起始点，则更新最后触摸点为模拟起始点。
     * 计算注册点在拖动层上的位置。
     * 获取拖动区域的左边界和顶部边界。
     * 初始化拖动对象，并设置原始视图。
     * 如果在预拖动阶段（pre-drag），则设置比例尺寸。
     * 创建拖动视图（DragView），如果存在绘制图像（drawable），则使用图像创建，否则使用视图创建。
     * 设置拖动对象的信息，包括拖动项的信息。
     * 设置拖动对象的偏移量。
     * 创建拖动驱动器（DragDriver），并设置拖动对象和拖动源。
     * 如果不是无障碍拖动，则创建拖动视图状态的公告。
     * 设置拖动视图的可视化偏移和拖动区域。
     * 执行触觉反馈。
     * 显示拖动视图，并初始化距离滚动的距离。
     * 如果不是预拖动阶段，则调用callOnDragStart()方法。
     * 如果是预拖动阶段且存在预拖动条件，则调用预拖动条件的onPreDragStart()方法。
     * 处理初始移动事件。
     * 如果当前没有进行触摸操作且不存在模拟的拖放起始点，则立即取消拖动操作。
     * 返回拖动视图对象。
     */
    @Override
    protected DragView startDrag(
            @Nullable Drawable drawable,
            @Nullable View view,
            DraggableView originalView,
            int dragLayerX,
            int dragLayerY,
            DragSource source,
            ItemInfo dragInfo,
            Point dragOffset,
            Rect dragRegion,
            float initialDragViewScale,
            float dragViewScaleOnDrop,
            DragOptions options) {
        if (TestProtocol.sDebugTracing) {
            Log.d(TestProtocol.NO_DROP_TARGET, "5");
        }
        if (PROFILE_DRAWING_DURING_DRAG) {
            android.os.Debug.startMethodTracing("Launcher");
        }

        mActivity.hideKeyboard();
        AbstractFloatingView.closeOpenViews(mActivity, false, TYPE_DISCOVERY_BOUNCE);

        mOptions = options;
        if (mOptions.simulatedDndStartPoint != null) {
            mLastTouch.x = mMotionDown.x = mOptions.simulatedDndStartPoint.x;
            mLastTouch.y = mMotionDown.y = mOptions.simulatedDndStartPoint.y;
        }

        final int registrationX = mMotionDown.x - dragLayerX;
        final int registrationY = mMotionDown.y - dragLayerY;

        final int dragRegionLeft = dragRegion == null ? 0 : dragRegion.left;
        final int dragRegionTop = dragRegion == null ? 0 : dragRegion.top;

        mLastDropTarget = null;

        mDragObject = new DropTarget.DragObject(mActivity.getApplicationContext());
        mDragObject.originalView = originalView;

        mIsInPreDrag = mOptions.preDragCondition != null
                && !mOptions.preDragCondition.shouldStartDrag(0);

        final Resources res = mActivity.getResources();
        final float scaleDps = mIsInPreDrag
                ? res.getDimensionPixelSize(R.dimen.pre_drag_view_scale) : 0f;
        final DragView dragView = mDragObject.dragView = drawable != null
                ? new LauncherDragView(
                mActivity,
                drawable,
                registrationX,
                registrationY,
                initialDragViewScale,
                dragViewScaleOnDrop,
                scaleDps)
                : new LauncherDragView(
                        mActivity,
                        view,
                        view.getMeasuredWidth(),
                        view.getMeasuredHeight(),
                        registrationX,
                        registrationY,
                        initialDragViewScale,
                        dragViewScaleOnDrop,
                        scaleDps);
        dragView.setItemInfo(dragInfo);
        mDragObject.dragComplete = false;

        mDragObject.xOffset = mMotionDown.x - (dragLayerX + dragRegionLeft);
        mDragObject.yOffset = mMotionDown.y - (dragLayerY + dragRegionTop);

        mDragDriver = DragDriver.create(this, mOptions, mFlingToDeleteHelper::recordMotionEvent);
        if (!mOptions.isAccessibleDrag) {
            mDragObject.stateAnnouncer = DragViewStateAnnouncer.createFor(dragView);
        }

        mDragObject.dragSource = source;
        mDragObject.dragInfo = dragInfo;
        mDragObject.originalDragInfo = mDragObject.dragInfo.makeShallowCopy();

        if (dragOffset != null) {
            dragView.setDragVisualizeOffset(new Point(dragOffset));
        }
        if (dragRegion != null) {
            dragView.setDragRegion(new Rect(dragRegion));
        }

        mActivity.getDragLayer().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        dragView.show(mLastTouch.x, mLastTouch.y);
        mDistanceSinceScroll = 0;

        if (!mIsInPreDrag) {
            callOnDragStart();
        } else if (mOptions.preDragCondition != null) {
            mOptions.preDragCondition.onPreDragStart(mDragObject);
        }

        handleMoveEvent(mLastTouch.x, mLastTouch.y);

        if (!mActivity.isTouchInProgress() && options.simulatedDndStartPoint == null) {
            // If it is an internal drag and the touch is already complete, cancel immediately
            MAIN_EXECUTOR.submit(this::cancelDrag);
        }
        return dragView;
    }

    @Override
    protected void exitDrag() {
        mActivity.getStateManager().goToState(NORMAL, SPRING_LOADED_EXIT_DELAY);
    }

    @Override
    protected boolean endWithFlingAnimation() {
        Runnable flingAnimation = mFlingToDeleteHelper.getFlingAnimation(mDragObject, mOptions);
        if (flingAnimation != null) {
            drop(mFlingToDeleteHelper.getDropTarget(), flingAnimation);
            return true;
        }
        return super.endWithFlingAnimation();
    }

    @Override
    protected void endDrag() {
        super.endDrag();
        mFlingToDeleteHelper.releaseVelocityTracker();
    }

    @Override
    protected DropTarget getDefaultDropTarget(int[] dropCoordinates) {
        mActivity.getDragLayer().mapCoordInSelfToDescendant(mActivity.getWorkspace(),
                dropCoordinates);
        return mActivity.getWorkspace();
    }
}
