package edu.umd.cmsc436.Level;

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.CountDownTimer;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.TextView;

import edu.umd.cmsc436.sheets.Sheets;
import edu.umd.cmsc436.frontendhelper.TrialMode;

import android.graphics.Canvas;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Level;

/* NOTE that most of timeTask() and revealTask() were written by Ian for the Tapping Activity,
 * and have been repurposed here (don't give me any credit for those parts).
 * I guess we could consider working that functionality into a HasTimerActivity class or something,
 * but I think this approach is okay for now.
 */
public class LevelActivity extends AppCompatActivity implements SensorEventListener, Sheets.Host {

    private Handler timeHandler;
    private TextView textCountdown, textTimer;
    private int timerCount, secondsLeft;
    // The time at which the user starts moving the ball to the center circle.
    private long testStartTime;
    // The time taken to move the ball to the center circle.
    private double timeToMoveToCenter;
    private String timeLeft;
    private SensorEventListener thisThing;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private BallView ballView;
    private RadioButton heatmapRadioButton;
    private boolean testRunning, countdownStopped, listenerUnregisteredOnPause, activityHasFocus;
    private CountDownTimer countDownTimer;
    private int difficulty;
    private int actionType;
    private String trialModePatientID = null;
    private Sheets.TestType trialModeAppendage = null;
    private Integer trialModeDifficulty = null;

    //used for visually defining the center
    Canvas canvas;

    //metric variables
    float pathLength;
    float trialDuration;
    float metric;
    float timeSpentInCircle = 0;

    private static final String spreadsheetId = "1YvI3CjS4ZlZQDYi5PaiA7WGGcoCsZfLoSFM0IdvdbDU";
    private static final String privateSpreadsheetId = "1Do1_GR62ZCAn_qhXXVVafG9_3_3Q0c6yXPyQV07nWZQ";
    private Sheets sheet;
    public static final int LIB_ACCOUNT_NAME_REQUEST_CODE = 1001;
    public static final int LIB_AUTHORIZATION_REQUEST_CODE = 1002;
    public static final int LIB_PERMISSION_REQUEST_CODE = 1003;
    public static final int LIB_PLAY_SERVICES_REQUEST_CODE = 1004;
    public static final int LIB_CONNECTION_REQUEST_CODE = 1005;
    Date date;
    Boolean sentHeatmap;

    //boolean for testing if statements
    boolean testing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_level);

        textCountdown = (TextView) findViewById(R.id.levelTestTextCountdown);
        heatmapRadioButton = (RadioButton) findViewById(R.id.heatmapRadioButton);

        // Sheets stuff
        sheet = new Sheets(this, this, getString(R.string.app_name), spreadsheetId, privateSpreadsheetId);

        // instance of "this" used for changing registration of this activity as a
        // SensorEventListener from within Runnables
        thisThing = this;

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // We make the assumption that a user who has navigated to this activity
        // has a device that supports an accelerometer. (See the MainActivity for reference.)
        ballView = (BallView) findViewById(R.id.ballViewContainer);
        ballView.setParentActivity(this);
        // boolean that records whether or not the user is in the middle of the actual test
        testRunning = false;
        // boolean that records whether or not the user has stopped a partial countdown from finishing
        countdownStopped = false;
        // boolean that records whether or not we've unregistered the listener from onPause()
        // used to avoid unregistering the listener twice (if the test ends while the app is paused)
        listenerUnregisteredOnPause = false;
        timeLeft = getString(R.string.timeLeft);

        Intent incomingIntent = getIntent();
        String action = incomingIntent.getAction();
        if (action != null){
            switch (action) {
                case "edu.umd.cmsc436.level.action.TRIAL":
                    actionType = 3;
                    trialModePatientID = TrialMode.getPatientId(incomingIntent);
                    trialModeAppendage = TrialMode.getAppendage(incomingIntent);
                    trialModeDifficulty = TrialMode.getDifficulty(incomingIntent);
                    break;
                case "edu.umd.cmsc436.level.action.PRACTICE":
                    actionType = 2;
                    break;
                case "edu.umd.cmsc436.level.action.HELP":
                    actionType = 1;
                    break;
                case "edu.umd.cmsc436.level.action.HISTORY":
                    actionType = 0;
                    break;
            }
        }

        setOutputListener();
    }

    private void setOutputListener() {
        findViewById(R.id.levelOutputButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // This button will only be available to be pressed after the test has been completed.
                // However, we check this anyway to be safe.
                if (!testRunning) {
                    // If the user mashes the "Show output" button multiple times, don't bother
                    // redrawing the same thing multiple times
                    boolean heatmapRequested = heatmapRadioButton.isChecked();
                    boolean redundantRequest =
                            ((heatmapRequested && ballView.displayingHeatmap) ||
                                    ((!heatmapRequested) && ballView.displayingPath));
                    if (!redundantRequest) {
                        ballView.drawOutput(heatmapRadioButton.isChecked());
                    }
                }
            }
        });
    }

    private String getHand(Sheets.TestType testType) {
        if (testType == Sheets.TestType.LH_LEVEL) {
            return "left hand";
        }else{
            return "right hand";
        }
    }

    public void showInstructions(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(LevelActivity.this);
        builder.setTitle(R.string.directionsHeader);
        builder.setMessage(String.format(getResources().getString(R.string.levelDirections),
                getHand(trialModeAppendage)));

        builder.setPositiveButton("Okay", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                // if this button is clicked, close current activity
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        TextView textView = (TextView) alertDialog.findViewById(android.R.id.message);
        textView.setTextSize(30);
    }

    private void setDifficulty() {
        if (actionType == 3) {
            // Use the difficulty received from the frontend's intent
            ballView.setDifficulty(trialModeDifficulty);
        }
        else {
            // Not a trial, use default difficulty
            difficulty = 1;
            ballView.setDifficulty(difficulty);
        }
    }

    /* Starts a countdown and, when that's done, starts the level test. */
    public void startlevelTest(View view) {
        ballView.setVisibility(View.GONE); // just to be safe
        findViewById(R.id.startlevelTestButton).setVisibility(View.GONE);
        findViewById(R.id.helpButton).setVisibility(View.GONE);
        timeHandler = new Handler();
        timerCount = 3;

        timeHandler.removeCallbacks(timeTask);
        textCountdown.setText(getString(R.string.ready));
        timeHandler.postDelayed(timeTask, 1000);
        timeHandler.postDelayed(timeTask, 2000);
        timeHandler.postDelayed(timeTask, 3000);
        timeHandler.postDelayed(revealTask, 4000);
        textCountdown.setVisibility(View.VISIBLE);
    }

    private Runnable timeTask = new Runnable() {
        @Override
        public void run() {
            //Used to count down before the level activity starts.
            textCountdown.setText(String.format(Locale.US, "%d", timerCount));
            timerCount--;
        }
    };


    private Runnable revealTask = new Runnable() {
        @Override
        public void run() {
            textCountdown.setVisibility(View.GONE);
            textTimer = (TextView) findViewById(R.id.levelTimer);
            ballView.setVisibility(View.VISIBLE);
            textTimer.setVisibility(View.VISIBLE);
            setDifficulty();
            // We use SENSOR_DELAY_FASTEST because it affords us the highest granularity in
            // detecting motion. To my knowledge, there isn't really any other way to increase
            // this rate
            sensorManager.registerListener(thisThing, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
            testRunning = true;
//            Log.i("hi", "Registered listener on task reveal");
            textTimer.setText(getString(R.string.moveToCenter));
            testStartTime = System.currentTimeMillis();
        }
    };

    public void stopCountdownTimer() {
        secondsLeft = 0;
        countDownTimer.cancel();
        textTimer.setText(getString(R.string.holdInCenter));
        countdownStopped = true;
    }

    public void startCountdownTimer() {
        if (!countdownStopped) {
            timeToMoveToCenter = 0.001 * (System.currentTimeMillis() - testStartTime);
        }
        secondsLeft = 0;
        countDownTimer = new CountDownTimer(5500, 100) {

            public void onTick(long millisUntilFinished) {
                // Update "Time Left" field
                if(Math.round((float)millisUntilFinished / 1000.0f) != secondsLeft){
                    secondsLeft = Math.round((float)millisUntilFinished / 1000.0f);
                    String updateTime = String.format(Locale.US, "%s %d", timeLeft, secondsLeft);
                    textTimer.setText(updateTime);
                }
            }

            public void onFinish() {
                // Finish test: includes unregistering accelerometer sensor listener
                testRunning = false;
                trialDuration = (System.currentTimeMillis() - testStartTime) / (float) 1000.00;
                String timeFinished = String.format(Locale.US, "%s %d", timeLeft, 0);
                textTimer.setText(timeFinished);
                if (activityHasFocus) {
                    // Used to reduce "jarring" effect of switching from level to results
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                textTimer.setVisibility(View.GONE);
                // Enable output display controls
                ballView.drawFinishedView();
                findViewById(R.id.levelOutputRadioGroup).setVisibility(View.VISIBLE);
                findViewById(R.id.levelOutputButton).setVisibility(View.VISIBLE);
                Button done_button = (Button)findViewById(R.id.done_button);
//                Toast.makeText(LevelActivity.this, Double.valueOf(ballView.getAveragePathLengths()).toString(), Toast.LENGTH_SHORT).show();
                done_button.setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if(actionType == 3 || testing) {
                                pathLength = (float) ballView.getTotalPathLength();
                                    metric = pathLength +
                                            timeSpentInCircle +
                                            trialDuration;

                                    //sends intent back to front end
                                    Intent intent = new Intent();
                                    intent.putExtra("score", metric);
                                    setResult(RESULT_OK, intent);

                                    //Toast.makeText(LevelActivity.this, "new apk2", Toast.LENGTH_SHORT).show();
                                    sendToSheets();
                                }

                                //finish();
                            }
                        });
                done_button.setVisibility(View.VISIBLE);
                if (!listenerUnregisteredOnPause) {
                    sensorManager.unregisterListener(thisThing);
//                    Log.i("hi", "Unregistered listener on test completion");
                }
            }
        };
        countDownTimer.start();
    }

    protected void restartTest(){
        this.recreate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityHasFocus = true;
        // Registering the listener in this case works because, if testRunning is true and
        // onResume() is called, then onPause() must have already been called. (The first time
        // onResume() is called, it's before testRunning could possibly be true.)
        if (testRunning) {
            // due to the clinical nature of this application I'm opting to use SENSOR_DELAY_FASTEST
            // for now. the only downside to that is battery usage, and this test shouldn't take a ton
            // of time.
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
            listenerUnregisteredOnPause = false;
//            Log.i("hi", "Registered listener on resume");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityHasFocus = false;
        if (testRunning) {
            sensorManager.unregisterListener(this);
            listenerUnregisteredOnPause = true;
//            Log.i("hi", "Unregistered listener on pause");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        // To avoid blocking the onSensorChanged method, we should offload most of our
        // work (in moving the ball, etc) in the future to threads/etc
        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Get the x, y, and z acceleration values from the accelerometer
            double ax = sensorEvent.values[0];
            double ay = sensorEvent.values[1];
            double az = sensorEvent.values[2];
            // Send these values to the ball view
            ballView.updatePosition(ax, ay);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        /* I don't think we need anything here for now, although reductions in accuracy may
         * be worth noting from a clinical perspective (that might invalidate the data?)
         *
         * Like, if we get to SensorManager.SENSOR_STATUS_UNRELIABLE or
         * SensorManager.SENSOR_STATUS_NO_CONTACT then it might be worth just letting the
         * user know something's gone wrong and not saving data/etc accordingly.
         */
        // TODO: ask about this, come to a consensus on what to do
        // (for what it's worth, I don't know how frequently accuracy loss occurs specific to
        // the accelerometer sensor)
    }

     @Override
     public void onBackPressed() {
         //updated per front end specification
         Intent intent = getIntent();
         setResult(RESULT_CANCELED, intent);
         finish();
     }

    // Sends raw data + images to local sheets/drive
    private void sendToSheets() {
        float[] trial = {timeSpentInCircle, pathLength, trialDuration, metric};
        date = new Date();
        String path = date.toString() + ": Path, " + getHand(trialModeAppendage);
        String heatmap = date.toString() + ": Heatmap, " + getHand(trialModeAppendage);

        if (testing) {
            trialModeAppendage = Sheets.TestType.LH_LEVEL;
            trialModePatientID = "test";
        }

        // TODO send heatmap image when drive errors stop being a thing
        sheet.writeTrials(trialModeAppendage, trialModePatientID, trial);
        sheet.uploadToDrive(getString(R.string.imageFolder), path, ballView.pathBitmap);
        //sheet.uploadToDrive(getString(R.string.imageFolder), heatmap, ballView.heatmapBitmap);
    }

    @Override
    public int getRequestCode(Sheets.Action action) {
        switch (action) {
            case REQUEST_ACCOUNT_NAME:
                return LIB_ACCOUNT_NAME_REQUEST_CODE;
            case REQUEST_AUTHORIZATION:
                return LIB_AUTHORIZATION_REQUEST_CODE;
            case REQUEST_PERMISSIONS:
                return LIB_PERMISSION_REQUEST_CODE;
            case REQUEST_PLAY_SERVICES:
                return LIB_PLAY_SERVICES_REQUEST_CODE;
            case REQUEST_CONNECTION_RESOLUTION:
                return LIB_CONNECTION_REQUEST_CODE;
            default:
                return -1;
        }
    }

    @Override
    public void notifyFinished(Exception e) {
        if (e != null) {
            throw new RuntimeException(e);
        }

        Log.i(getClass().getSimpleName(), "Done");
        finish();
    }

    @Override
    public void onRequestPermissionsResult (int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
      this.sheet.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
      super.onActivityResult(requestCode, resultCode, data);
      this.sheet.onActivityResult(requestCode, resultCode, data);
    }
}
