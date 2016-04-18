package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.ColorRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {

    private static final String LOG_TAG = "SunshineWatchFaceSrv";

    private static final String FORECAST_REQUEST_PATH = "/forecastRequest";
    private static final String FORECAST_PATH = "/forecast";
    private static final String FORECAST_HIGH_KEY = "FORECAST_HIGH_KEY";
    private static final String FORECAST_LOW_KEY = "FORECAST_LOW_KEY";
    private static final String FORECAST_ICON_KEY = "FORECAST_ICON_KEY";
    private static final String FORECAST_DATE_KEY = "FORECAST_DATE_KEY";
    private static final String FORECAST_CAPABILITY_NAME = "sync_forecast_data";


    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener, GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, CapabilityApi.CapabilityListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        private String mForecastNodeId = null;
        private String mForecastHigh;
        private String mForecastLow;
        private Bitmap mForecastIcon;
        private Time mForecastTime;
        private boolean mForecastRequestingData = false;

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mForecastPaint;

        float mForecastIconSize;

        boolean mAmbient;

        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        private final int drawMargin = 10;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setHotwordIndicatorGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setStatusBarGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    .setShowSystemUiTime(false)
                    //.setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR | WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .build());

            Resources resources = SunshineWatchFaceService.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.bg_interactive_mode));

            mTimePaint = createTextPaint(R.color.text);
            mDatePaint = createTextPaint(R.color.text_gray);
            mForecastPaint = createTextPaint(R.color.text_gray);

            mForecastIconSize = resources.getDimension(R.dimen.forecast_icon_size);

            mTime = new Time();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(@ColorRes int textColor) {
            Paint paint = new Paint();
            paint.setColor(getResources().getColor(textColor));
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();

                // register change capability listener to find best node to get forecast data
                Wearable.CapabilityApi.addCapabilityListener(
                        mGoogleApiClient,
                        this,
                        FORECAST_CAPABILITY_NAME);

                // ask for nodes with "sync_forecast_data" capabilities
                Wearable.CapabilityApi.getCapability(mGoogleApiClient,
                        FORECAST_CAPABILITY_NAME, CapabilityApi.FILTER_REACHABLE)
                        .setResultCallback(new ResultCallback<CapabilityApi.GetCapabilityResult>() {
                            @Override
                            public void onResult(@NonNull CapabilityApi.GetCapabilityResult getCapabilityResult) {
                                if (getCapabilityResult.getStatus().isSuccess())
                                    updateForecastCapability(getCapabilityResult.getCapability());
                            }
                });

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {

                    // remove listeners
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    Wearable.CapabilityApi.removeCapabilityListener(mGoogleApiClient, this, FORECAST_CAPABILITY_NAME);

                    mGoogleApiClient.disconnect();

                    Log.d(LOG_TAG, "GoogleAPIClient disconnected...");
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mTimePaint.setTextSize(resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size));

            mDatePaint.setTextSize(resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size));

            mForecastPaint.setTextSize(resources.getDimension(isRound
                    ? R.dimen.forecast_text_size_round : R.dimen.forecast_text_size));

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mForecastPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw HH:MM.
            mTime.setToNow();
            String text = String.format("%02d:%02d", mTime.hour, mTime.minute);
            Rect timeBounds = new Rect();
            mTimePaint.getTextBounds(text, 0, text.length(), timeBounds);
            int timeYOffset = timeBounds.height() / 2;
            canvas.drawText(text, bounds.centerX() - (timeBounds.width() / 2),
                    bounds.centerY() + timeYOffset, mTimePaint);

            text = mTime.format("%a, %b %d %G").replace(".", "");
            Rect dateBounds = new Rect();
            mDatePaint.getTextBounds(text, 0, text.length(), dateBounds);
            int dateYOffset = (dateBounds.height() / 2) + drawMargin;
            canvas.drawText(text, bounds.centerX() - (dateBounds.width() / 2),
                    bounds.centerY() - dateYOffset - timeYOffset, mDatePaint);

            // if the last forecast sent by Sunshine App is delayed by more than one day
            // we request forecast again
            if (mTime != null && mForecastTime != null && mTime.yearDay != mForecastTime.yearDay) {
                requestForecast(null);
            }

            if (mForecastLow != null && mForecastHigh != null) {
                text = mForecastHigh + " " + mForecastLow;
                Rect forecastBounds = new Rect();
                mForecastPaint.getTextBounds(text, 0, text.length(), forecastBounds);
                int forecastYOffset = forecastBounds.height() + drawMargin;
                canvas.drawText(text, bounds.centerX() - (forecastBounds.width() / 2),
                        bounds.centerY() + forecastYOffset + timeYOffset, mForecastPaint);


                if (mForecastIcon != null && !mAmbient) {
                    canvas.drawBitmap(mForecastIcon, bounds.centerX() - (mForecastIconSize / 2),
                            bounds.centerY() + drawMargin + forecastYOffset + timeYOffset, mForecastPaint);
                }

            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected: " + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended: " + i);
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(LOG_TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged executed");
            for (DataEvent event : dataEventBuffer) {
                DataItem item = event.getDataItem();
                if (FORECAST_PATH.equals(item.getUri().getPath())) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                    mForecastHigh = dataMap.getString(FORECAST_HIGH_KEY);
                    mForecastLow = dataMap.getString(FORECAST_LOW_KEY);

                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(dataMap.getLong(FORECAST_DATE_KEY));

                    Time t = new Time(mTime.timezone);

                    Asset forecastIcon = dataMap.getAsset(FORECAST_ICON_KEY);

                    if (forecastIcon != null)
                        new LoadBitmapAsyncTask().execute(forecastIcon);

                    Log.d(LOG_TAG, "High: " + mForecastHigh + ", Low: " + mForecastLow);
                }
            }
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if (params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(LOG_TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(LOG_TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if (bitmap != null) {
                    mForecastIcon = Bitmap.createScaledBitmap(
                            bitmap,
                            getResources().getDimensionPixelSize(R.dimen.forecast_icon_size),
                            getResources().getDimensionPixelSize(R.dimen.forecast_icon_size),
                            true);
                }
            }
        }

        @Override
        public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
            updateForecastCapability(capabilityInfo);
        }

        private void updateForecastCapability(CapabilityInfo capabilityInfo) {
            Set<Node> connectedNodes = capabilityInfo.getNodes();
            mForecastNodeId = pickBestNodeId(connectedNodes);

            requestForecast(null);
        }

        private String pickBestNodeId(Set<Node> nodes) {
            String bestNodeId = null;
            // Find a nearby node or pick one arbitrarily
            for (Node node : nodes) {
                if (node.isNearby()) {
                    return node.getId();
                }
                bestNodeId = node.getId();
            }
            return bestNodeId;
        }

        private void requestForecast(byte[] data) {
            if (mForecastNodeId != null && !mForecastRequestingData) {

                mForecastRequestingData = true;

                Wearable.MessageApi.sendMessage(mGoogleApiClient, mForecastNodeId, FORECAST_REQUEST_PATH, data)
                        .setResultCallback( new ResultCallback<MessageApi.SendMessageResult>() {
                                @Override
                                public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                    if (!sendMessageResult.getStatus().isSuccess()) {
                                        Log.d(LOG_TAG, "message not sent :(");
                                    } else {
                                        Log.d(LOG_TAG, "message sent :)");
                                    }

                                    mForecastRequestingData = false;
                                }
                            }
                        );
            } else {
                //Log.d(LOG_TAG, "Unable to retrieve node with sync forecast data capability :(");
            }
        }
    }
}
