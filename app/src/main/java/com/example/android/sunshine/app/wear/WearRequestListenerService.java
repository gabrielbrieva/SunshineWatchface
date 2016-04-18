package com.example.android.sunshine.app.wear;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

public class WearRequestListenerService extends WearableListenerService {

    private static final String LOG_TAG = WearRequestListenerService.class.getSimpleName();

    private static final String FORECAST_REQUEST_PATH = "/forecastRequest";

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        super.onMessageReceived(messageEvent);

        Log.d(LOG_TAG, "message received from wear :)");

        Intent intent = new Intent(getApplicationContext(), SyncWearForecastService.class);
        intent.setAction(SyncWearForecastService.SYNC_WEAR_FORECAST_ACTION);
        getApplication().startService(intent);
    }
}
