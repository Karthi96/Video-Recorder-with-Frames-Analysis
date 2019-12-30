/*
* Copyright 2007-2011 the original author or authors
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package nirman.io.detector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.os.AsyncTask;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClient;
import com.amazonaws.services.rekognition.model.DetectLabelsRequest;
import com.amazonaws.services.rekognition.model.DetectLabelsResult;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.Label;

import org.bytedeco.javacpp.opencv_core.Mat;
import org.bytedeco.javacv.AndroidFrameConverter;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_NV21;

public class CvCameraPreview extends SurfaceView implements SurfaceHolder.Callback, PreviewCallback {

    private final String LOG_TAG = "CameraPreview";

    private static final int STOPPED = 0;
    private static final int STARTED = 1;
    public static final int CAMERA_BACK = 99;
    public static final int CAMERA_FRONT = 98;
    public static final int SCALE_FIT = 1;
    public static final int SCALE_FULL = 2;
    private static final int MAGIC_TEXTURE_ID = 10;
    private int count=0;
    private final float ASPECT_RATIO_W = 4.0f;
    private final float ASPECT_RATIO_H = 3.0f;
    private final int PREVIEW_MAX_WIDTH = 640;
    private final int PICTURE_MAX_WIDTH = 1280;

    private byte[] previewBuffer;
    private SurfaceHolder surfaceHolder;
    private FFmpegFrameFilter filter;
    private int chainIdx = 0;
    private boolean stopThread = false;
    private boolean cameraFrameReady = false;
    protected boolean enabled = true;
    private boolean surfaceExist;
    private Thread thread;
    private CvCameraViewListener listener;
    private AndroidFrameConverter converterToBitmap = new AndroidFrameConverter();
    private OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();
    private Bitmap cacheBitmap;
    protected Frame[] cameraFrame;
    private int state = STOPPED;
    private final Object syncObject = new Object();
    private int cameraId = -1;
    private int cameraType = Camera.CameraInfo.CAMERA_FACING_BACK;
    private Camera cameraDevice;
    private SurfaceTexture surfaceTexture;
    private int frameWidth, frameHeight;
    private int scaleType = SCALE_FIT;

    private String output=new String();
    private Context cont;

    public AmazonRekognition rekognitionClient = new AmazonRekognitionClient(new BasicAWSCredentials(AwsConfig.accessKey, AwsConfig.secretKey));

    public CvCameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        cont=context;
        TypedArray array = getContext().obtainStyledAttributes(attrs, R.styleable.CvCameraPreview);
        int camType = array.getInt(R.styleable.CvCameraPreview_camera_type, CAMERA_BACK);
        int scaleType = array.getInt(R.styleable.CvCameraPreview_scale_type, SCALE_FIT);
        array.recycle();

        initializer(camType == CAMERA_BACK ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT, scaleType);

    }

    public CvCameraPreview(Context context, int camType, int scaleType) {
        super(context);

        initializer(camType == CAMERA_BACK ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT, scaleType);
    }

    private void initializer(int camType, int scaleType) {

        this.surfaceHolder = this.getHolder();
        this.surfaceHolder.addCallback(this);

        this.scaleType = scaleType;
        this.cameraType = camType;

        // deprecated setting, but required on Android versions prior to API 11
        if (Build.VERSION.SDK_INT < 11) {
            this.surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }
    }

    public void setCvCameraViewListener(CvCameraViewListener listener) {
        this.listener = listener;
    }

    public int getCameraId() {
        return cameraId;
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        if (this.surfaceHolder.getSurface() == null) {
            Log.e(LOG_TAG, "surfaceChanged(): surfaceHolder is null, nothing to do.");
            return;
        }

        synchronized (syncObject) {
            if (!surfaceExist) {
                surfaceExist = true;
                checkCurrentState();
            } else {

                surfaceExist = false;
                checkCurrentState();
                surfaceExist = true;
                checkCurrentState();
            }
        }
    }

    /**
     * Called when syncObject lock is held
     */
    private void checkCurrentState() {
        Log.d(LOG_TAG, "call checkCurrentState");
        int targetState;

        if (enabled && surfaceExist && getVisibility() == VISIBLE) {
            targetState = STARTED;
        } else {
            targetState = STOPPED;
        }

        if (targetState != state) {
            /* The state change detected. Need to exit the current state and enter target state */
            processExitState(state);
            state = targetState;
            processEnterState(state);
        }
    }

    private void processExitState(int state) {
        Log.d(LOG_TAG, "call processExitState: " + state);
        switch (state) {
            case STARTED:
                onExitStartedState();
                break;
            case STOPPED:
                onExitStoppedState();
                break;
        }
        ;
    }

    private void processEnterState(int state) {
        Log.d(LOG_TAG, "call processEnterState: " + state);
        switch (state) {
            case STARTED:
                onEnterStartedState();
                if (listener != null) {
                    listener.onCameraViewStarted(frameWidth, frameHeight);
                }
                break;
            case STOPPED:
                onEnterStoppedState();
                if (listener != null) {
                    listener.onCameraViewStopped();
                }
                break;
        }
    }

    private void onEnterStoppedState() {
        /* nothing to do */
    }

    private void onExitStoppedState() {
        /* nothing to do */
    }

    // NOTE: The order of bitmap constructor and camera connection is important for android 4.1.x
    // Bitmap must be constructed before surface
    private void onEnterStartedState() {
        Log.d(LOG_TAG, "call onEnterStartedState");
        /* Connect camera */
        if (!connectCamera()) {
            AlertDialog ad = new AlertDialog.Builder(getContext()).create();
            ad.setCancelable(false); // This blocks the 'BACK' button
            ad.setMessage("It seems that you device does not support camera (or it is locked). Application will be closed.");
            ad.setButton(DialogInterface.BUTTON_NEUTRAL, "OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    ((Activity) getContext()).finish();
                }
            });
            ad.show();

        }
    }

    private void onExitStartedState() {
        disconnectCamera();
        if (cacheBitmap != null) {
            //cacheBitmap.recycle();
        }
        if (filter != null) {
            try {
                filter.release();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(LOG_TAG, "surfaceDestroyed");
        synchronized (syncObject) {
            surfaceExist = false;
            checkCurrentState();
        }
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);

        // do some ultra high precision math...
        float expectedRatio = height > width ? ASPECT_RATIO_H / ASPECT_RATIO_W : ASPECT_RATIO_W / ASPECT_RATIO_H;
        float screenRatio = width * 1f / height;
        if (screenRatio > expectedRatio) {
            if (scaleType == SCALE_FULL) {
                height = (int) (width / expectedRatio + .5);
            } else {
                width = (int) (height * expectedRatio + .5);
            }
        } else {
            if (scaleType == SCALE_FULL) {
                width = (int) (height * expectedRatio + .5);
            } else {
                height = (int) (width / expectedRatio + .5);
            }
        }

        setMeasuredDimension(width, height);
        Log.i(LOG_TAG, "onMeasure(): set surface dimension to " + width + "x" + height);
    }

    private boolean connectCamera() {
        /* 1. We need to instantiate camera
         * 2. We need to start thread which will be getting frames
         */
        /* First step - initialize camera connection */
        Log.d(LOG_TAG, "Connecting to camera");
        if (!initializeCamera())
            return false;

        /* now we can start update thread */
        Log.d(LOG_TAG, "Starting processing thread");
        stopThread = false;
        thread = new Thread(new CameraWorker());
        thread.start();

        return true;
    }

    private void disconnectCamera() {
        /* 1. We need to stop thread which updating the frames
         * 2. Stop camera and release it
         */
        Log.d(LOG_TAG, "Disconnecting from camera");
        try {
            stopThread = true;
            Log.d(LOG_TAG, "Notify thread");
            synchronized (this) {
                this.notify();
            }
            Log.d(LOG_TAG, "Wating for thread");
            if (thread != null)
                thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            thread = null;
        }

        stopCameraPreview();

        /* Now release camera */
        releaseCamera();

        cameraFrameReady = false;
    }

    private boolean initializeCamera() {
        synchronized (this) {
            if (this.cameraDevice != null) {
                // do the job only if the camera is not already set
                Log.i(LOG_TAG, "initializeCamera(): camera is already set, nothing to do");
                return true;
            }

            // warning here! starting from API 9, we can retrieve one from the multiple
            // hardware cameras (ex. front/back)
            if (Build.VERSION.SDK_INT >= 9) {

                if (this.cameraId < 0) {
                    // at this point, it's the first time we request for a camera
                    Camera.CameraInfo camInfo = new Camera.CameraInfo();
                    for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                        Camera.getCameraInfo(i, camInfo);

                        if (camInfo.facing == cameraType) {
                            // in this example we'll request specifically the back camera
                            try {
                                this.cameraDevice = Camera.open(i);
                                this.cameraId = i; // assign to cameraId this camera's ID (O RLY?)
                                break;
                            } catch (RuntimeException e) {
                                // something bad happened! this camera could be locked by other apps
                                Log.e(LOG_TAG, "initializeCamera(): trying to open camera #" + i + " but it's locked", e);
                            }
                        }
                    }
                } else {
                    // at this point, a previous camera was set, we try to re-instantiate it
                    try {
                        this.cameraDevice = Camera.open(this.cameraId);
                    } catch (RuntimeException e) {
                        Log.e(LOG_TAG, "initializeCamera(): trying to re-open camera #" + this.cameraId + " but it's locked", e);
                    }
                }
            }

            // we could reach this point in two cases:
            // - the API is lower than 9
            // - previous code block failed
            // hence, we try the classic method, that doesn't ask for a particular camera
            if (this.cameraDevice == null) {
                try {
                    this.cameraDevice = Camera.open();
                    this.cameraId = 0;
                } catch (RuntimeException e) {
                    // this is REALLY bad, the camera is definitely locked by the system.
                    Log.e(LOG_TAG,
                            "initializeCamera(): trying to open default camera but it's locked. "
                                    + "The camera is not available for this app at the moment.", e
                    );
                    return false;
                }
            }

            // here, the open() went good and the camera is available
            Log.i(LOG_TAG, "initializeCamera(): successfully set camera #" + this.cameraId);

            setupCamera();

            updateCameraDisplayOrientation();

            initFilter(frameWidth, frameHeight);

            startCameraPreview(this.surfaceHolder);
        }

        return true;
    }


    private boolean setupCamera() {
        if (cameraDevice == null) {
            Log.e(LOG_TAG, "setupCamera(): warning, camera is null");
            return false;
        }
        try {
            Camera.Parameters parameters = cameraDevice.getParameters();
            List<Size> sizes = parameters.getSupportedPreviewSizes();
            if (sizes != null) {
                Size bestPreviewSize = getBestSize(sizes, PREVIEW_MAX_WIDTH);

                frameWidth = bestPreviewSize.width;
                frameHeight = bestPreviewSize.height;

                parameters.setPreviewSize(bestPreviewSize.width, bestPreviewSize.height);

                parameters.setPreviewFormat(ImageFormat.NV21); // NV21 is the most supported format for preview frames

                List<String> FocusModes = parameters.getSupportedFocusModes();
                if (FocusModes != null && FocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                }

                cameraDevice.setParameters(parameters); // save everything

                // print saved parameters
                int prevWidth = cameraDevice.getParameters().getPreviewSize().width;
                int prevHeight = cameraDevice.getParameters().getPreviewSize().height;
                int picWidth = cameraDevice.getParameters().getPictureSize().width;
                int picHeight = cameraDevice.getParameters().getPictureSize().height;

                Log.d(LOG_TAG, "setupCamera(): settings applied:\n\t"
                        + "preview size: " + prevWidth + "x" + prevHeight + "\n\t"
                        + "picture size: " + picWidth + "x" + picHeight
                );

                // here: previewBuffer initialization. It will host every frame that comes out
                // from the preview, so it must be big enough.
                // After that, it's linked to the camera with the setCameraCallback() method.
                try {
                    this.previewBuffer = new byte[prevWidth * prevHeight * ImageFormat.getBitsPerPixel(cameraDevice.getParameters().getPreviewFormat()) / 8];
                    setCameraCallback();
                } catch (IOException e) {
                    Log.e(LOG_TAG, "setupCamera(): error setting camera callback.", e);
                }

                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    protected void releaseCamera() {
        synchronized (this) {
            if (cameraDevice != null) {
                cameraDevice.release();
            }
            cameraDevice = null;
        }
    }


    private void setCameraCallback() throws IOException {
        Log.d(LOG_TAG, "setCameraCallback()");
        cameraDevice.addCallbackBuffer(this.previewBuffer);
        cameraDevice.setPreviewCallbackWithBuffer(this);
    }


    private Size getBestSize(List<Size> sizes, int widthThreshold) {
        Size bestSize = null;

        for (Size currentSize : sizes) {
            boolean isDesiredRatio = ((currentSize.width / ASPECT_RATIO_W) == (currentSize.height / ASPECT_RATIO_H));
            boolean isBetterSize = (bestSize == null || currentSize.width > bestSize.width);
            boolean isInBounds = currentSize.width <= widthThreshold;

            if (isDesiredRatio && isInBounds && isBetterSize) {
                bestSize = currentSize;
            }
        }

        if (bestSize == null) {
            bestSize = sizes.get(0);
            Log.e(LOG_TAG, "determineBestSize(): can't find a good size. Setting to the very first...");
        }

        Log.i(LOG_TAG, "determineBestSize(): bestSize is " + bestSize.width + "x" + bestSize.height);
        return bestSize;
    }


    private synchronized void startCameraPreview(SurfaceHolder holder) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                surfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
                cameraDevice.setPreviewTexture(surfaceTexture);
            } else
                cameraDevice.setPreviewDisplay(holder);
            cameraDevice.startPreview();
//            filter.start();
        } catch (Exception e) {
            Log.e(LOG_TAG, "startCameraPreview(): error starting camera preview", e);
        }
    }


    private synchronized void stopCameraPreview() {
        try {
            cameraDevice.stopPreview();
            cameraDevice.setPreviewCallback(null);
            //onExitStartedState();
           // filter.stop();
        } catch (Exception e) {
            // ignored: tried to stop a non-existent preview
            Log.i(LOG_TAG, "stopCameraPreview(): tried to stop a non-running preview, this is not an error");
        }
    }

    /**
     * Gets the current screen rotation in order to understand how much
     * the surface needs to be rotated
     */
    private void updateCameraDisplayOrientation() {
        if (cameraDevice == null) {
            Log.e(LOG_TAG, "updateCameraDisplayOrientation(): warning, camera is null");
            return;
        }

        int degree = getRotationDegree();

        cameraDevice.setDisplayOrientation(degree); // save settings
    }

    private int getRotationDegree() {
        int result = 0;
        Activity parentActivity = (Activity) this.getContext();

        int rotation = parentActivity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        if (Build.VERSION.SDK_INT >= 9) {
            // on >= API 9 we can proceed with the CameraInfo method
            // and also we have to keep in mind that the camera could be the front one
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);

            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;  // compensate the mirror
            } else {
                // back-facing
                result = (info.orientation - degrees + 360) % 360;
            }
        } else {
            // TODO: on the majority of API 8 devices, this trick works good
            // and doesn't produce an upside-down preview.
            // ... but there is a small amount of devices that don't like it!
            result = Math.abs(degrees - 90);
        }
        return result;
    }

    private void initFilter(int width, int height) {
        int degree = getRotationDegree();
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        boolean isFrontFaceCamera = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
        Log.i(LOG_TAG, "init filter with width = " + width + " and height = " + height + " and degree = "
                + degree + " and isFrontFaceCamera = " + isFrontFaceCamera);
        String transposeCode;
        String formatCode = "format=pix_fmts=rgba";
        /*
         0 = 90CounterCLockwise and Vertical Flip (default)
         1 = 90Clockwise
         2 = 90CounterClockwise
         3 = 90Clockwise and Vertical Flip
         */
        switch (degree) {
            case 0:
                transposeCode = isFrontFaceCamera ? "transpose=3,transpose=2" : "transpose=1,transpose=2";
                break;
            case 90:
                transposeCode = isFrontFaceCamera ? "transpose=3" : "transpose=1";
                break;
            case 180:
                transposeCode = isFrontFaceCamera ? "transpose=0,transpose=2" : "transpose=2,transpose=2";
                break;
            case 270:
                transposeCode = isFrontFaceCamera ? "transpose=0" : "transpose=2";
                break;
            default:
                transposeCode = isFrontFaceCamera ? "transpose=3,transpose=2" : "transpose=1,transpose=2";
        }

        if (cameraFrame == null) {
            cameraFrame = new Frame[2];
            cameraFrame[0] = new Frame(width, height, Frame.DEPTH_UBYTE, 2);
            cameraFrame[1] = new Frame(width, height, Frame.DEPTH_UBYTE, 2);
        }

        filter = new FFmpegFrameFilter(transposeCode + "," + formatCode, width, height);
        filter.setPixelFormat(AV_PIX_FMT_NV21);

        Log.i(LOG_TAG, "filter initialize success");
    }

    @Override
    public void onPreviewFrame(byte[] raw, Camera cam) {
        processFrame(previewBuffer, cam);
        // [IMPORTANT!] remember to reset the CallbackBuffer at the end of every onPreviewFrame event.
        // Seems weird, but it works
        cam.addCallbackBuffer(previewBuffer);
    }


    private void processFrame(byte[] raw, Camera cam) {

        if(MainActivity.recording){

            Log.d("Frmae","COming"+count++);
            if(count%AwsConfig.framesAnalysis==0){
                Camera.Parameters parameters = cam.getParameters();
                int width = parameters.getPreviewSize().width;
                int height = parameters.getPreviewSize().height;

                YuvImage yuv = new YuvImage(raw, parameters.getPreviewFormat(), width, height, null);

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yuv.compressToJpeg(new Rect(0, 0, width, height), 50, out);

                byte[] bytes = out.toByteArray();
                String findLabels=new String();

                AsyncTaskRunner runner = new AsyncTaskRunner();

                runner.execute(bytes);
                if(!output.isEmpty())
                {
                    Set<String> set = new HashSet<>(Arrays.asList(output.split(",")));
                    MainActivity.output_text.setText("Detected Labels="+set.size()+"; Label(s)="+set.toString());

                }

            }
        }

        if (cameraFrame != null) {
            synchronized (this) {
                ((ByteBuffer) cameraFrame[chainIdx].image[0].position(0)).put(raw);
                cameraFrameReady = true;
                this.notify();
            }
        }
    }

    private class CameraWorker implements Runnable {
        public void run() {
            do {
                boolean hasFrame = false;
                synchronized (CvCameraPreview.this) {
                    try {
                        while (!cameraFrameReady && !stopThread) {
                            CvCameraPreview.this.wait();
                        }
                    } catch (InterruptedException e) {
                        Log.e(LOG_TAG, "CameraWorker interrupted", e);
                    }
                    if (cameraFrameReady) {
                        chainIdx = 1 - chainIdx;
                        cameraFrameReady = false;
                        hasFrame = true;
                    }
                }

                if (!stopThread && hasFrame) {
                    if (cameraFrame[1 - chainIdx] != null) {
                        try {
                            Frame frame;
                            filter.start();
                            filter.push(cameraFrame[1 - chainIdx]);
                            while ((frame = filter.pull()) != null) {
                                deliverAndDrawFrame(frame);
                            }
                            filter.stop();
                        } catch (FrameFilter.Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            } while (!stopThread);
            Log.d(LOG_TAG, "Finish processing thread");
        }
    }


    protected void deliverAndDrawFrame(Frame frame) {
        Mat processedMat = null;

        if (listener != null) {
            Mat mat = converterToMat.convert(frame);
            processedMat = listener.onCameraFrame(mat);
            frame = converterToMat.convert(processedMat);
            if (mat != null) {
                mat.release();
            }
        }


        cacheBitmap = converterToBitmap.convert(frame);
        if (cacheBitmap != null) {
            int width, height;
            Canvas canvas = getHolder().lockCanvas();
            if (canvas != null) {
                width = canvas.getWidth();
                height = cacheBitmap.getHeight() * canvas.getWidth() / cacheBitmap.getWidth();
                canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
                canvas.drawBitmap(cacheBitmap,
                        new Rect(0, 0, cacheBitmap.getWidth(), cacheBitmap.getHeight()),
                        new Rect(0,
                                (canvas.getHeight() - height) / 2,
                                width,
                                (canvas.getHeight() - height) / 2 + height), null);
                getHolder().unlockCanvasAndPost(canvas);
            }
        }

        if (processedMat != null) {
            processedMat.release();
        }
    }

    public interface CvCameraViewListener {

        public void onCameraViewStarted(int width, int height);


        public void onCameraViewStopped();


        public Mat onCameraFrame(Mat mat);
    }


    private class AsyncTaskRunner extends AsyncTask<byte[], String, String> {

        private String resp;
        //ProgressDialog progressDialog;

        @Override
        protected String doInBackground(byte[]... params) {
            publishProgress("Sleeping..."); // Calls onProgressUpdate()

            try {


                Float similarityThreshold = 70F;

                ByteBuffer sourceImageBytes = null;

                 sourceImageBytes = ByteBuffer.wrap(params[0]);

                Image source = new Image()
                        .withBytes(sourceImageBytes);

                DetectLabelsRequest request = new DetectLabelsRequest()
                        .withImage(source)
                        .withMaxLabels(10).withMinConfidence(75F);
                // Call operation
                DetectLabelsResult detectLabelsResult = rekognitionClient.detectLabels(request);

                detectLabelsResult.getLabels();
                Log.d("TAG", "Detected"+detectLabelsResult.getLabels());
                List<Label> detected=detectLabelsResult.getLabels();
                String obj=new String();
                for(int z=0;z<detected.size();z++){
                    Label lb=detected.get(z);
                    obj=obj+lb.getName()+",";
                }
                Log.d("Output","Detected ones:"+obj);
                output=output+obj;
            }catch(Exception e) {
                e.printStackTrace();
                resp = e.getMessage();
            }
            return  resp;
        }


        @Override
        protected void onPostExecute(String result) {

        }


        @Override
        protected void onPreExecute() {

        }


        @Override
        protected void onProgressUpdate(String... text) {

        }
    }

}

