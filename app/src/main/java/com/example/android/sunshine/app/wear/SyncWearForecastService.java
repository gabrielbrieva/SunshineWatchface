package com.example.android.sunshine.app.wear;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.concurrent.TimeUnit;

public class SyncWearForecastService extends IntentService implements
        GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {

    private static final String LOG_TAG = SyncWearForecastService.class.getSimpleName();

    public static final String SYNC_WEAR_FORECAST_ACTION = "com.example.android.sunshine.app.syncwearforecast";

    private static final String FORECAST_PATH = "/forecast";
    private static final String FORECAST_HIGH_KEY = "FORECAST_HIGH_KEY";
    private static final String FORECAST_LOW_KEY = "FORECAST_LOW_KEY";
    private static final String FORECAST_ICON_KEY = "FORECAST_ICON_KEY";
    private static final String FORECAST_DATE_KEY = "FORECAST_DATE_KEY";

    private static final int SEND_TIMEOUT = 500;

    private GoogleApiClient mGoogleApiCLient;


    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    public SyncWearForecastService() {
        super("SyncWearForecastService");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (mGoogleApiCLient == null) {
            mGoogleApiCLient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API)
                    .addOnConnectionFailedListener(this)
                    .addConnectionCallbacks(this)
                    .build();
        }

        Log.d(LOG_TAG, "Connecting Google Api Client");
        mGoogleApiCLient.connect();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null && SYNC_WEAR_FORECAST_ACTION.equals(intent.getAction())) {

            String location = Utility.getPreferredLocation(this);

            Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(location, System.currentTimeMillis());
            Cursor data = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null,
                    null, WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");

            if (data == null)
                return;

            if (!data.moveToFirst()) {
                data.close();
                return;
            }

            // Extract the weather data from the Cursor
            int weatherId = data.getInt(INDEX_WEATHER_ID);
            double maxTemp = data.getDouble(INDEX_MAX_TEMP);
            double minTemp = data.getDouble(INDEX_MIN_TEMP);
            data.close();

            sendForecast(getApplicationContext(), mGoogleApiCLient, weatherId, maxTemp, minTemp);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mGoogleApiCLient != null && mGoogleApiCLient.isConnected())
            mGoogleApiCLient.disconnect();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(LOG_TAG, "Google API Client connected ...");
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.d(LOG_TAG, "Google API Client suspended ...");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.d(LOG_TAG, "Google API Client connection failed ...");
    }

    public static void sendForecast(Context context, GoogleApiClient googleApiClient, int weatherId, double high, double low) {
        // creating data map to send to wear
        PutDataMapRequest dataMap = PutDataMapRequest.create(FORECAST_PATH).setUrgent();

        // formating values
        final String highTemp = Utility.formatTemperature(context, high);
        final String lowTemp = Utility.formatTemperature(context, low);

        dataMap.getDataMap().putString(FORECAST_HIGH_KEY, highTemp);
        dataMap.getDataMap().putString(FORECAST_LOW_KEY, lowTemp);

        // use date to know if wear has up to date forecast data
        dataMap.getDataMap().putLong(FORECAST_DATE_KEY, System.currentTimeMillis());

        // getting forecast icon Asset for wearable
        int forecastIconResource = Utility.getArtResourceForWeatherCondition(weatherId);

        if (forecastIconResource != -1) {
            Asset forecastIcon = Utility.createAsset(forecastIconResource, context);
            dataMap.getDataMap().putAsset(FORECAST_ICON_KEY, forecastIcon);
        }

        PutDataRequest req = dataMap.asPutDataRequest();

        DataApi.DataItemResult dataItemResult = Wearable.DataApi.putDataItem(googleApiClient, req).await(SEND_TIMEOUT, TimeUnit.MILLISECONDS);

        if (dataItemResult != null && !dataItemResult.getStatus().isSuccess())
            Log.e(LOG_TAG, "ERROR: " + dataItemResult.getStatus().getStatusMessage());
        else
            Log.d(LOG_TAG, "Forecast Sent to Wear: " + lowTemp + " " + highTemp);

    }
}
