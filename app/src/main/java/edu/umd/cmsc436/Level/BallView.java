package edu.umd.cmsc436.Level;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;


/**
 * We use this for drawing the ball (and, eventually, concentric circles) in the level Test.
 */
public class BallView extends View{

    private boolean debugFirstPaintDone = false;

    // The Paint instance used to draw the ball that the user moves around the screen
    private Paint ballPaint;
    // The Paint instance used to draw the concentric circles on the screen. The color
    // attribute of this will be changed to green for drawing the outermost concentric circle
    // in which the center of the ball lies; however, all other circles will be black.
    private Paint circlePaint;
    // The Paint instance used to draw the path output generated.
    private Paint pathPaint;
    // The Paint instance used to draw the heatmap output generated.
    private Paint heatmapGridPaint;

    private Canvas exportCanvas;

    // Colors used to shade in ScreenGridRegions on the heatmap, in decreasing intensity.
    private int HEATMAP_MAXFREQ_COLOR = Color.rgb(255, 20, 20);
    private int HEATMAP_75FREQ_COLOR = Color.rgb(255, 70, 70);
    private int HEATMAP_50FREQ_COLOR = Color.rgb(255, 120, 120);
    private int HEATMAP_25FREQ_COLOR = Color.rgb(255, 170, 170);
    private int HEATMAP_MINFREQ_COLOR = Color.rgb(255, 220, 220);

    // various dimension/etc. attributes of the ball, concentric circles, and ScreenGridRegions.
    // these are initialized in onLayout() so that they can be scaled depending on the observed
    // view size (which isn't obtainable until onLayout() is called by the system)
    private float BALL_SIZE, CIRCLE_RADIUS_DISTANCE, BALL_OFFSET_DISTANCE;
    private int GRID_REGION_SIZE;
    private double MAX_VISIBLE_CIRCLE_RADIUS;

    // The line thickness of the circle and path Paint instances.
    private final float CIRCLE_STROKE_WIDTH = 3;
    private final float PATH_STROKE_WIDTH = 20;

    // The x and y positions of the ball on the screen, initialized to sane values before
    // the ball is first drawn.
    private float ballX = -1;
    private float ballY = -1;
    // The acceleration of the ball measured along the x and y axes.
    private double ax = 0;
    private double ay = 0;

    // The width, height, and half-width and half-height properties of this view.
    // Will be set to their final values in onLayout().
    private int VIEW_WIDTH;
    private int VIEW_HEIGHT;
    private float HALF_VIEW_WIDTH;
    private float HALF_VIEW_HEIGHT;
    // The effective boundaries of the ball's positions (taking into account the size of the ball).
    // Will be set to their final values in onLayout().
    private float WIDTH_BOUND;
    private float HEIGHT_BOUND;

    // The "running mean" of the average displacement of the ball from the center of the screen.
    private double ballPositionMeasurementRunningMean;
    // The number of ball positions that have been sampled thus far.
    private int ballPositionMeasurementCount;
    // An ArrayList of the sampled ball positions thus far in the format [x1, y1, x2, y2, ...]
    private ArrayList<Float> ballPositions;

    // Various flag variables indicating what's happening with the display.
    public boolean displayingPath, displayingHeatmap, displayingNothingTemporarily, pathMade;
    private boolean countdownNotHappening;

    // The Path instance used to create the ball path.
    private Path ballPath;

    // An array used to track the time the ball spends in each circle.
    private long[] timeSpentInCircles;

    private int currCircle = -5, prevCircle = -5;

    long startTime, endTime, elapsedTime;

    private boolean debugCircleTime = true;

    public Bitmap pathBitmap = null;
    public Bitmap heatmapBitmap = null;

    private boolean finalTimeRecorded = false;

    // number of times countdown has been restarted
    private int attempts;

    // ArrayList storing old paths
    private ArrayList<Path> oldPaths;

    // Arraylist storing old ball positions
    private ArrayList<Float> oldPositions;

    // The total number of circles contained within the BallView.
    private int totalNumCircles;

    // The multiplier used for manipulating how fast the ball moves based on acceleration.
    private double ACCELERATION_MULTIPLIER;
    private int center;

    //metric data for total time the ball was in the center
    float timeInCenter;
    float lastTimeStamp;
    private boolean firstTimeInCenter = true;

    // Instance of the LevelActivity in which this BallView is contained. Used here for
    // triggering events within the LevelActivity (via LevelActivity.startCountdownTimer()
    // and LevelActivity.stopCountdownTimer()).
    private LevelActivity LevelActivity;

    public BallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        ballPaint = new Paint();
        ballPaint.setColor(Color.RED);
        circlePaint = new Paint();
        circlePaint.setColor(Color.BLACK);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(CIRCLE_STROKE_WIDTH);
        pathPaint = new Paint();
        pathPaint.setColor(Color.CYAN);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeWidth(PATH_STROKE_WIDTH);
        heatmapGridPaint = new Paint();
        heatmapGridPaint.setStyle(Paint.Style.FILL);
        ballPositions = new ArrayList<>();
        ballPositionMeasurementRunningMean = 0;
        ballPositionMeasurementCount = 0;
        attempts = 0;
        oldPaths = new ArrayList<>();
        oldPositions = new ArrayList<>();
        pathMade = false;
        displayingPath = false;
        displayingHeatmap = false;
        // Used to indicate the temporary state between the test finishing and output being drawn
        displayingNothingTemporarily = false;
        ballPath = new Path();
        countdownNotHappening = true;
    }

    public void setDifficulty(int difficulty) {
        if (difficulty == 1) {
            ACCELERATION_MULTIPLIER = 1;
            center = 3;
        }else if (difficulty == 2) {
            ACCELERATION_MULTIPLIER = 4;
            center = 2;
        }else if (difficulty == 3) {
            ACCELERATION_MULTIPLIER = 7;
            center = 1;
        } else {
            // Ideally we'd throw an error here, but we're in this sort of weird liminal state where we
            // haven't been able to test the trial stuff very well yet (which is fine) so for now
            // we're going to fail gracefully by defaulting to the difficulty level 2 settings.
            ACCELERATION_MULTIPLIER = 4;
            center = 3;
        }

    }

    public void setParentActivity(LevelActivity activity) {
        this.LevelActivity = activity;
    }

    private static double getRandomAngle() {
        return Math.random() * (2 * Math.PI);
    }

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        // We delay these calculations to here so that width/height info is available
        super.onLayout(changed, left, top, right, bottom);
        // Assign important information for drawing in this view
        VIEW_WIDTH = getWidth();
        VIEW_HEIGHT = getHeight();
        HALF_VIEW_WIDTH = VIEW_WIDTH / 2;
        HALF_VIEW_HEIGHT = VIEW_HEIGHT / 2;
        BALL_SIZE = VIEW_WIDTH / 32;
        CIRCLE_RADIUS_DISTANCE = BALL_SIZE;
        BALL_OFFSET_DISTANCE = CIRCLE_RADIUS_DISTANCE * 11;
        GRID_REGION_SIZE = Math.round(BALL_SIZE);

        // ... information for drawing the circles
        /* An explanation of this formula:
         * Basically, we want to draw circles until we know that the circles will not be visible
         * on the screen. The farthest circle that would be visible on the screen has a radius
         * that extends exactly to one of the four corners of the screen (assuming the circles
         * are all centered at the screen center, which they are here).
         *
         * Therefore, we can use the Pythagorean Theorem to calculate this maximum radius,
         * and then we just draw circles that have radii less than or equal to that.
         *
         * (I drew this out on a whiteboard so maybe the picture would explain this better)
         */
        MAX_VISIBLE_CIRCLE_RADIUS = Math.sqrt(Math.pow(HALF_VIEW_WIDTH, 2) + Math.pow(HALF_VIEW_HEIGHT, 2));

        totalNumCircles = (int) Math.ceil(MAX_VISIBLE_CIRCLE_RADIUS / CIRCLE_RADIUS_DISTANCE);

        //variables to help calculate the time spent in the center
        timeInCenter = 0;
        firstTimeInCenter = true;
        lastTimeStamp = 0;

        // ... information for drawing the ball
        WIDTH_BOUND = VIEW_WIDTH - BALL_SIZE;
        HEIGHT_BOUND = VIEW_HEIGHT - BALL_SIZE;
        // The ball's initial position should be in the center of the screen.
        double angle = getRandomAngle();
//        Log.i("initial offset angle", String.format("%.3f", angle));
        ballX = HALF_VIEW_WIDTH + (BALL_OFFSET_DISTANCE * (float) Math.cos(angle));
        ballY = HALF_VIEW_HEIGHT + (BALL_OFFSET_DISTANCE * (float) Math.sin(angle));
    }

    public double getBallPositionMeasurementMean() {
        return ballPositionMeasurementRunningMean;
    }

    /* Multiplies acceleration by the multiplier, defined by the difficulty level,
     * to amplify fidgeting, etc. */
    public void updatePosition(double xAccel, double yAccel) {
        ax = xAccel * ACCELERATION_MULTIPLIER;
        ay = yAccel * ACCELERATION_MULTIPLIER;
        invalidate();
    }

    public void resetCountdown() {
        countdownNotHappening = true;
        storePath();
        oldPositions.addAll(ballPositions);
        ballPositions.clear();
        //ballPositionMeasurementRunningMean = 0;
        //ballPositionMeasurementCount = 0;
    }

    /* Records the ball's position. Should be called at a regular interval so as to ensure a
     * consistent sampling of the ball's position. */
    public void sampleBallPosition() {
        ballPositions.add(ballX);
        ballPositions.add(ballY);
    }

    public void drawOutput(boolean displayHeatmap) {
        this.displayingPath = !displayHeatmap;
        this.displayingHeatmap = displayHeatmap;
        this.displayingNothingTemporarily = false;
        invalidate();
    }

    public void drawFinishedView() {
        pathBitmap = Bitmap.createBitmap(exportCanvas.getWidth(), exportCanvas.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas pathBitmapCanvas = new Canvas(pathBitmap);
        createPathBitmap(pathBitmapCanvas);

        heatmapBitmap = Bitmap.createBitmap(exportCanvas.getWidth(), exportCanvas.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas heatmapBitmapCanvas = new Canvas(heatmapBitmap);
        createHeatmapBitmap(heatmapBitmapCanvas);

        this.displayingNothingTemporarily = true;
        invalidate();
    }

    private void makeBallPath() {
        // converts recorded positions of the ball into a path object.
        if (pathMade) {
            return;
        }

        ballPath.moveTo(ballPositions.get(0), ballPositions.get(1));
        for (int i = 2; i < ballPositions.size(); i++) {
            if (i % 2 == 0) {
                ballPath.lineTo(ballPositions.get(i), ballPositions.get(i + 1));
            }
        }
        pathMade = true;
    }

    private void clearBallPath(){
        ballPath.reset();
        pathMade = false;
    }

    private void storePath(){
        makeBallPath();
        oldPaths.add(new Path(ballPath));
        attempts++;
        clearBallPath();
    }

    public float getPathLength(){
        // returns final successful path length
        return getPathLength(ballPath);
    }

    public float getPathLength(Path path){
        // returns the length of the path in mm, accounts for different displays.
        if(!pathMade) {
            makeBallPath();
        }

        PathMeasure measure = new PathMeasure(path, false);
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        return measure.getLength()/ TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 1, dm);
    }

    public double getTotalPathLength(){
        // combines unsuccessful and successful path lengths
        double totalLength = 0;
        for(Path path : oldPaths){
            totalLength += getPathLength(path);
        }

        totalLength += getPathLength();
        return totalLength;
    }

    public double getAveragePathLengths(){
        return getTotalPathLength()/(attempts + 1);
    }

    public void createPathBitmap(Canvas canvas) {
        if (!pathMade) {
            makeBallPath();
        }

        canvas.drawRGB(195, 195, 195);
        drawBitmapHelp(canvas);

        //draw path
        pathPaint.setColor(Color.RED);
        canvas.drawPath(ballPath, pathPaint);
        for (Path path : oldPaths) {
            canvas.drawPath(path, pathPaint);
        }
    }

    /**
     * Draws a "heatmap" of the ball's center positions on the screen.
     * <p>
     * Our general algorithm:
     * Divide the view into a set of square regions and color those accordingly based on
     * how many sampled ball center positions overlapped with those regions.
     */
    private void createHeatmapBitmap(Canvas canvas) {
        class ScreenGridRegion {
            // Class defining a region of the screen
            private int ballPositionFrequency;
            private float leftBound, topBound;

            private ScreenGridRegion(float leftBound, float topBound) {
                this.ballPositionFrequency = 0;
                this.leftBound = leftBound;
                this.topBound = topBound;
            }

            private void incrementPositionFrequency() {
                ballPositionFrequency++;
//                Log.i("hi", String.format("%.3f, %.3f --> %d", leftBound, topBound, ballPositionFrequency));
            }
        }

        // Define a mapping of top-left bounds of a screen grid region to the ScreenGridRegion
        // object representing that region. This is easier to use and more efficient than just
        // storing regions in an unsorted ArrayList or something, as is discussed below.
        HashMap<ArrayList<Integer>, ScreenGridRegion> topLeftPos2Region = new HashMap<>();
        for (int x = 0; x < VIEW_WIDTH; x += GRID_REGION_SIZE) {
            for (int y = 0; y < VIEW_HEIGHT; y += GRID_REGION_SIZE) {
                ArrayList<Integer> topLeft = new ArrayList<>();
                topLeft.add(x);
                topLeft.add(y);
                topLeftPos2Region.put(topLeft, new ScreenGridRegion(x, y));
            }
        }
        // Go through all sampled ball positions (represented as an ArrayList of the form
        // [x1 y1 x2 y2 ... xp yp] for p positions) and increment the frequency of the corresponding
        // screen region accordingly.
        float px, py;
        oldPositions.addAll(ballPositions);
        for (int i = 0; i < oldPositions.size(); i += 2) {
            /* Use the position of the ball and some math to generate an ArrayList<Integer> key for
             * the topLeftPos2Region mapping onto screen grid regions. This is much faster than
             * performing a search through all screen grid regions for every ball position --
             * the new approach used here is roughly O(c*|positions|) (where c = general cost of
             * accessing the hash map), while the old approach is O(|regions|*|positions|). */
            px = oldPositions.get(i);
            py = oldPositions.get(i + 1);
            int leftIndexedGridCount = (int) Math.floor(px / GRID_REGION_SIZE);
            int topIndexedGridCount = (int) Math.floor(py / GRID_REGION_SIZE);
            ArrayList<Integer> topLeftPosition = new ArrayList<>();
            topLeftPosition.add(leftIndexedGridCount * GRID_REGION_SIZE);
            topLeftPosition.add(topIndexedGridCount * GRID_REGION_SIZE);
            topLeftPos2Region.get(topLeftPosition).incrementPositionFrequency();
        }
        /* At this point, we know the position frequencies for every region.
         * We can go through all the visited regions and color them accordingly.
         * (First, we fill the screen with the "minimum" color, though: we use white as the
         * "minimum" color instead of gray because a white background makes it a bit easier to
         * design sensible heatmap colors.) */
        canvas.drawRGB(195, 195, 195);
        drawBitmapHelp(canvas);
        for (ScreenGridRegion reg : topLeftPos2Region.values()) {
            /* These values are arbitrary and mostly based on me playing around with my phone. We
             * may want to adjust in the future.
             * (Scaling colorings relative to the maximum position frequency mostly resulted in
             * hard-to-understand results, since the maximum frequency region tends to be an
             * outlier. However, it may be worth revisiting that approach in the future.) */
            int freq = reg.ballPositionFrequency;
            if (freq > 0) {
                if (freq > 40)
                    heatmapGridPaint.setColor(HEATMAP_MAXFREQ_COLOR);
                else if (freq > 30)
                    heatmapGridPaint.setColor(HEATMAP_75FREQ_COLOR);
                else if (freq > 20)
                    heatmapGridPaint.setColor(HEATMAP_50FREQ_COLOR);
                else if (freq > 10)
                    heatmapGridPaint.setColor(HEATMAP_25FREQ_COLOR);
                else
                    heatmapGridPaint.setColor(HEATMAP_MINFREQ_COLOR);

                canvas.drawRect(reg.leftBound, reg.topBound, reg.leftBound + GRID_REGION_SIZE,
                        reg.topBound + GRID_REGION_SIZE, heatmapGridPaint);
            }
        }

    }

    private void drawBitmapHelp(Canvas canvas) {
        //draw green center circle
        int radius = 0;
        Paint centerPaint = new Paint();
        centerPaint.setColor(Color.GREEN);
        centerPaint.setAlpha(80);
        canvas.drawCircle(HALF_VIEW_WIDTH,
                HALF_VIEW_HEIGHT,
                CIRCLE_RADIUS_DISTANCE * center,
                centerPaint);

        //draw concentric circles
        while (radius <= MAX_VISIBLE_CIRCLE_RADIUS) {
            radius += CIRCLE_RADIUS_DISTANCE;

            circlePaint.setColor(Color.BLACK);
            canvas.drawCircle(HALF_VIEW_WIDTH, HALF_VIEW_HEIGHT, radius, circlePaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        /* Two broad classes of drawing we can do: either redrawing the ball based on its motion,
         * or just drawing the path of the ball to show the user (which should only happen once) */
        super.onDraw(canvas);
        exportCanvas = canvas;

        int radius = 0;
        boolean coloringNotDone = true;
        boolean coloringJustDone = false;
        double ballDistanceFromCenter = 0;
        boolean showingOutput = displayingPath || displayingHeatmap || displayingNothingTemporarily;
        if (!showingOutput) {
            // the directionality of x/y acceleration is dependent upon the rotation of the screen.
            // A TODO here is to detect screen rotation and adjust directionality accordingly.
            // Also TODO (?): scale ball and circle sizes to screen, so that same amount of circles appears
            // on any device. will have to account for different aspect ratios.
            ballX -= ax;
            ballY += ay;
            if (ballX < BALL_SIZE) ballX = BALL_SIZE;
            if (ballY < BALL_SIZE) ballY = BALL_SIZE;
            if (ballX > WIDTH_BOUND) ballX = WIDTH_BOUND;
            if (ballY > HEIGHT_BOUND) ballY = HEIGHT_BOUND;

            radius = 0;
            coloringNotDone = true;
            coloringJustDone = false;
            ballDistanceFromCenter = Math.sqrt(
                    Math.pow(ballX - HALF_VIEW_WIDTH, 2) + Math.pow(ballY - HALF_VIEW_HEIGHT, 2)
            );
            if (countdownNotHappening && ballDistanceFromCenter < (CIRCLE_RADIUS_DISTANCE*center)) {
                /* This set of conditions is true when
                 * 1) we haven't started the countdown timer yet (because when it starts, the
                 *    LevelActivity calls BallView.setParentActivity(null);)
                 * 2) The ball is in the center "circle," and we haven't started the countdown
                 *    timer yet (so this is the first time we have observed the ball being in the center
                 *    of the screen) */
                storePath();
                oldPositions.addAll(ballPositions);
                ballPositions.clear();

                countdownNotHappening = false;
                if (firstTimeInCenter) {
                    lastTimeStamp = System.currentTimeMillis();
                    firstTimeInCenter = false;
                }
                timeInCenter+= (System.currentTimeMillis() - lastTimeStamp);
            }
            //else if (!countdownNotHappening && ballDistanceFromCenter >= (CIRCLE_RADIUS_DISTANCE*center)) {
                //LevelActivity.stopCountdownTimer();
                //resetCountdown();
            //}

            /* We just sample every time we redraw the ball. This is generally reasonable -- "quantity"
             * of position data is really just a means to an end to plot the ball's position, so the
             * more data the better.
             * ...ALSO: we only sample the data after the countdown begins (i.e. after the user successfully
             * navigates the ball to the center circle initially). This prevents the user from ostensibly
             * missing the center circle forever, which could cause lots of unnecessary data to be
             * gathered + really complicated paths to be drawn. */
            sampleBallPosition();
            ballPositionMeasurementCount++;
            ballPositionMeasurementRunningMean =
                    ((ballPositionMeasurementRunningMean * (ballPositionMeasurementCount - 1))
                            + ballDistanceFromCenter) / (ballPositionMeasurementCount);
        }

        if (displayingPath) {
            Rect source = new Rect(0, 0, pathBitmap.getWidth(), pathBitmap.getHeight());
            canvas.drawBitmap(pathBitmap,null , source, null);
        } else if (displayingHeatmap) {
            Rect source = new Rect(0, 0, heatmapBitmap.getWidth(), heatmapBitmap.getHeight());
            canvas.drawBitmap(heatmapBitmap,null , source, null);
        }

        while (radius <= MAX_VISIBLE_CIRCLE_RADIUS) {
            radius += CIRCLE_RADIUS_DISTANCE;

            if (!showingOutput && coloringNotDone && ballDistanceFromCenter < radius) {
                circlePaint.setColor(Color.GREEN);
                coloringJustDone = true;
                coloringNotDone = false;
                int currBallCircle = (int) Math.floor(radius / CIRCLE_RADIUS_DISTANCE);
            }

            canvas.drawCircle(HALF_VIEW_WIDTH, HALF_VIEW_HEIGHT, radius, circlePaint);
            if (coloringJustDone) {
                Paint centerPaint = new Paint();
                centerPaint.setColor(Color.GREEN);
                centerPaint.setAlpha(60);
                canvas.drawCircle(HALF_VIEW_WIDTH,
                        HALF_VIEW_HEIGHT,
                        CIRCLE_RADIUS_DISTANCE * center,
                        centerPaint);

                circlePaint.setColor(Color.BLACK);
                coloringJustDone = false;
            }
        }
        if (!showingOutput) {
            canvas.drawCircle(ballX, ballY, BALL_SIZE, ballPaint);
        }
    }
}