package io.github.dltech21.zxingcamera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ImageUtils;
import com.blankj.utilcode.util.ScreenUtils;
import com.dl.sdk.zcamera.camera.AmbientLightManager;
import com.dl.sdk.zcamera.camera.CameraManager;
import com.dl.sdk.zcamera.camera.InactivityTimer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Created by Donal on 2017/8/15.
 */

public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    private CameraManager cameraManager;
    private boolean hasSurface;
    private InactivityTimer inactivityTimer;
    private AmbientLightManager ambientLightManager;

    private ImageView imgPre;

    CameraManager getCameraManager() {
        return cameraManager;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);

        hasSurface = false;
        inactivityTimer = new InactivityTimer(this);
        ambientLightManager = new AmbientLightManager(this);

        initView();
    }

    public void initView() {
        imgPre = (ImageView) findViewById(R.id.img_pre);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
        // want to open the camera driver and measure the screen size if we're going to show the help on
        // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
        // off screen.
        cameraManager = new CameraManager(getApplication());

        ambientLightManager.start(cameraManager);

        inactivityTimer.onResume();


        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
        SurfaceHolder surfaceHolder = surfaceView.getHolder();
        if (hasSurface) {
            // The activity was paused but not stopped, so the surface still exists. Therefore
            // surfaceCreated() won't be called, so init the camera here.
            initCamera(surfaceHolder);
        } else {
            // Install the callback and wait for surfaceCreated() to init the camera.
            surfaceHolder.addCallback(this);
        }
    }

    private int getCurrentOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_90:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            }
        } else {
            switch (rotation) {
                case Surface.ROTATION_0:
                case Surface.ROTATION_270:
                    return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            }
        }
    }

    @Override
    protected void onPause() {
        cameraManager.stopPreview();
        inactivityTimer.onPause();
        ambientLightManager.stop();
        cameraManager.closeDriver();
        if (!hasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();

    }

    @Override
    protected void onDestroy() {
        inactivityTimer.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // Use volume up/down to turn on light
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                cameraManager.setTorch(false);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                cameraManager.setTorch(true);
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            System.out.println("*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!hasSurface) {
            hasSurface = true;
            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (cameraManager.isOpen()) {
            System.out.println("initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            cameraManager.openDriver(surfaceHolder);
            cameraManager.startPreview();
            cameraManager.setPictureCallback(pictureCallback);
        } catch (IOException ioe) {
            System.out.println(ioe);
        } catch (RuntimeException e) {
            System.out.println("Unexpected error initializing camera" + e);
        }
    }


    private final Camera.PictureCallback pictureCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(final byte[] data, Camera camera) {
            new SavePicTask(data, camera).start();
        }
    };

    public void takePicture(View view) {
        cameraManager.takePicture();
    }


    private class SavePicTask extends Thread {
        private byte[] data;
        private Camera camera;

        SavePicTask(byte[] data, Camera camera) {
            this.data = data;
            this.camera = camera;
        }

        @Override
        public void run() {
            super.run();
            Message msg = handler.obtainMessage();
            if (saveToSDCard(data)) {
                msg.obj = camera;
                msg.what = 1;
            } else {
                msg.what = 0;
            }
            handler.sendMessage(msg);
        }

        private boolean saveToSDCard(byte[] data) {
            String dir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/zxingcamera";
            FileUtils.createOrExistsDir(dir);
            String photoPath = dir + File.separator + System.currentTimeMillis() + ".png";
            saveOriginal(data, photoPath);
            Bitmap bitmap = ImageUtils.getBitmap(photoPath);
            bitmap = rotateBitmap(bitmap);
            ImageUtils.save(bitmap, photoPath, Bitmap.CompressFormat.PNG);
            return true;
        }

        private void saveOriginal(byte[] data, String path) {
            File file = new File(path);
            OutputStream os = null;
            try {
                os = new FileOutputStream(file);
                os.write(data);
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    public Bitmap rotateBitmap(Bitmap bitmap) {
        System.gc();
        if (null == bitmap) {
            return bitmap;
        }
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        Bitmap newb = Bitmap.createBitmap(ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(newb);
        Matrix matrix = new Matrix();
        matrix.postRotate(90);
        Bitmap new2 = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true);
        canvas.drawBitmap(new2, new Rect(0, 0, new2.getWidth(), new2.getHeight()), new Rect(0, 0, ScreenUtils.getScreenWidth(), ScreenUtils.getScreenHeight()), null);
        if (null != bitmap) {
            bitmap.recycle();
        }

        return newb;
    }

    private Handler handler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == 1) {
                Camera camera = (Camera) msg.obj;
                if (camera != null) {
                    camera.startPreview();
                    if (cameraManager.getAutoFocusManager() != null) {
                        cameraManager.getAutoFocusManager().start();
                    }
                }

            }
        }
    };


}