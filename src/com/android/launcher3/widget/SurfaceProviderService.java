package com.android.launcher3.widget;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

public class SurfaceProviderService extends Service {
    private Surface surface;
    private final ISurfaceProvider.Stub mBinder = new ISurfaceProvider.Stub() {

        @Override
        public Surface getSurface() throws RemoteException {
            if (surface != null) {
                return surface;
            }
            return null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // 初始化 SurfaceView
//        View view = LayoutInflater.from(this).inflate(R.layout.test, null, false);
//        mSurfaceView = view.findViewById(R.id.surface_view);
        // 添加其他必要的 SurfaceView 设置和渲染逻辑
        // ...
    }

    public void setmSurfaceView(Surface surface) {
        this.surface = surface;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null) {
            Surface surface = intent.getParcelableExtra("surface");
            if (surface != null) {
                this.surface = surface;
            }
        }
        return mBinder;
    }
}