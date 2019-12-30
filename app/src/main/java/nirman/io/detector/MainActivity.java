package nirman.io.detector;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.bytedeco.javacpp.opencv_core;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_MPEG4;

public class MainActivity extends AppCompatActivity implements OnClickListener, CvCameraPreview.CvCameraViewListener{

    private final static String CLASS_LABEL = "MainActivity";
    private final static String LOG_TAG = CLASS_LABEL;
    private PowerManager.WakeLock wakeLock;
    public static boolean recording;
    private CvCameraPreview cameraView;
    private Button btnRecorderControl;
    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    private File savePath = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "NNT_Detector"+timestamp+".mp4");
    private FFmpegFrameRecorder recorder;
    private long startTime = 0;
    private OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();
    private final Object semaphore = new Object();
    public static TextView output_text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        cameraView = (CvCameraPreview) findViewById(R.id.camera_view);
        output_text=(TextView)findViewById(R.id.output_text);
        output_text.setMovementMethod(new ScrollingMovementMethod());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, CLASS_LABEL);
        wakeLock.acquire();

        initLayout();
    }

    //Side Menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    //Side Menu Onclick Event
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();
       if(id == R.id.action_contact_us){
            //Display Output
            AlertDialog alertDialog = new AlertDialog.Builder(
                    MainActivity.this).create();
            alertDialog.setTitle("Nggawe Nirman Technologies");
            alertDialog.setMessage("E-mail Us: contactus@nirman.io\n" +
                    "\n" +
                    "US Office:\n" +
                    "906 W Glenmere Dr.,\n" +
                    "Chandler, AZ 85225\n" +
                    "United States\n" +
                    "Phone: +1-480-399-2409\n" +
                    "\n" +
                    "India Office:\n" +
                    "RMZ Infinity, Cowrks Building,\n" +
                    "B Block, 5th Floor, Old Madras Road,\n" +
                    "Bengaluru, 560016, Karnataka, India\n" +
                    "Phone: +91-80-67431129\n");
            alertDialog.setIcon(R.drawable.logo);
            alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            alertDialog.show();
        }else if(id == R.id.action_about_us){
            Intent intent=new Intent(MainActivity.this,ScrollingActivity.class);
            startActivity(intent);
        }else if(id == R.id.action_about_app){
            Intent intent=new Intent(MainActivity.this,AboutAppActivity.class);
            startActivity(intent);
        }


        return super.onOptionsItemSelected(item);
    }


    @Override
    protected void onResume() {
        super.onResume();

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, CLASS_LABEL);
            wakeLock.acquire();
        }
    }


    @Override
    protected void onPause() {
        super.onPause();

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }

        if (recorder != null) {
            try {
                recorder.release();
            } catch (FrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (recording) {
                stopRecording();
            }

            finish();

            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    //INitial Listeners
    private void initLayout() {
        btnRecorderControl = (Button) findViewById(R.id.recorder_control);
        btnRecorderControl.setText("Start");
        btnRecorderControl.setOnClickListener(this);

        cameraView.setCvCameraViewListener(this);
    }


    private void initRecorder(int width, int height) {
        int degree = getRotationDegree();
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraView.getCameraId(), info);
        boolean isFrontFaceCamera = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
        Log.i(LOG_TAG, "init recorder with width = " + width + " and height = " + height + " and degree = "
                + degree + " and isFrontFaceCamera = " + isFrontFaceCamera);
        int frameWidth, frameHeight;
        /*
         0 = 90CounterCLockwise and Vertical Flip (default)
         1 = 90Clockwise
         2 = 90CounterClockwise
         3 = 90Clockwise and Vertical Flip
         */
        switch (degree) {
            case 0:
                frameWidth = width;
                frameHeight = height;
                break;
            case 90:
                frameWidth = height;
                frameHeight = width;
                break;
            case 180:
                frameWidth = width;
                frameHeight = height;
                break;
            case 270:
                frameWidth = height;
                frameHeight = width;
                break;
            default:
                frameWidth = width;
                frameHeight = height;
        }

        Log.i(LOG_TAG, "saved file path: " + savePath.getAbsolutePath());
        recorder = new FFmpegFrameRecorder(savePath, frameWidth, frameHeight, 0);
        recorder.setFormat("mp4");
        recorder.setVideoCodec(AV_CODEC_ID_MPEG4);
        recorder.setVideoQuality(1);
        // Set in the surface changed method
        recorder.setFrameRate(16);

        Log.i(LOG_TAG, "recorder initialize success");
    }

    private int getRotationDegree() {
        int result;

        int rotation = getWindowManager().getDefaultDisplay().getRotation();
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
            Camera.getCameraInfo(cameraView.getCameraId(), info);

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

    public void startRecording() {
        try {
            synchronized (semaphore) {
                recorder.start();
            }
            startTime = System.currentTimeMillis();
            recording = true;
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    public void stopRecording() {
        if (recorder != null && recording) {
            recording = false;
            Log.v(LOG_TAG, "Finishing recording, calling stop and release on recorder");
            try {
                synchronized (semaphore) {
                    recorder.stop();
                    recorder.release();
                }
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            recorder = null;
        }
    }

    @Override
    public void onClick(View v) {
        if (!recording) {
            startRecording();
            recording = true;
            Log.w(LOG_TAG, "Start Button Pushed");
            btnRecorderControl.setText("Stop");
            btnRecorderControl.setBackgroundResource(R.drawable.bg_red_circle_button);
        } else {
            // This will trigger the audio recording loop to stop and then set isRecorderStart = false;
            stopRecording();
            recording = false;
            Log.w(LOG_TAG, "Stop Button Pushed");
//            btnRecorderControl.setText("Start");
            btnRecorderControl.setVisibility(View.GONE);
          //  btnRecorderControl.setText("Start");
            //btnRecorderControl.setBackgroundResource(R.drawable.bg_green_circle_button);

            //Display Output
            AlertDialog alertDialog = new AlertDialog.Builder(
                    MainActivity.this).create();
            alertDialog.setTitle("Output");
            alertDialog.setMessage(output_text.getText()+"\n\n"+"Video file was saved to " + savePath +" location.");
            alertDialog.setIcon(R.drawable.tick);
            alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                }
            });
            alertDialog.show();
        }
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        initRecorder(width, height);
    }


    @Override
    public void onCameraViewStopped() {
        stopRecording();
    }

    @Override
    public opencv_core.Mat onCameraFrame(opencv_core.Mat mat) {
        if (recording && mat != null) {
            synchronized (semaphore) {
                try {
                    Frame frame = converterToMat.convert(mat);
                    long t = 1000 * (System.currentTimeMillis() - startTime);
                    if (t > recorder.getTimestamp()) {
                        recorder.setTimestamp(t);
                    }
                    recorder.record(frame);
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.v(LOG_TAG, e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        return mat;
    }
}
