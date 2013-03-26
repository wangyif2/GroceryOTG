package com.groceryotg.android;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.database.Cursor;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.ProgressBar;
import com.groceryotg.android.database.contentprovider.GroceryotgProvider;
import com.groceryotg.android.services.NetworkHandler;
import com.groceryotg.android.services.ServerURL;
import com.groceryotg.android.services.location.LocationMonitor;
import com.groceryotg.android.services.location.LocationReceiver;

public class SplashScreen extends Activity {
	private static final String SETTINGS_IS_DB_POPULATED = "isDBPopulated";
    // used to know if the back button was pressed in the splash screen activity
    // and avoid opening the next activity
    private boolean mIsBackButtonPressed;
    private static final int SPLASH_DURATION = 10; // 10 milliseconds

    private RefreshStatusReceiver mRefreshStatusReceiver;
    private static final int PROGRESS_MAX = 100;
    
    public static ProgressBar mProgressBar = null;
    
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.splash_screen);
        
        // Load the default preferences
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Initialize the database tables if they aren't created
        Cursor c = getContentResolver().query(GroceryotgProvider.CONTENT_URI_CAT, null, null, null, null);

        init();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mRefreshStatusReceiver = new RefreshStatusReceiver();
        IntentFilter mStatusIntentFilter = new IntentFilter(NetworkHandler.REFRESH_COMPLETED_ACTION);
        mStatusIntentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        LocalBroadcastManager.getInstance(this).registerReceiver(mRefreshStatusReceiver, mStatusIntentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRefreshStatusReceiver);
    }

    private void init() {
    	configProgressBar();

        configDatabase();

        configLocationPoll();
    }
    
    private void configProgressBar() {
    	// creates a progress bar from 0-100
    	mProgressBar = (ProgressBar)findViewById(R.id.loading_progress_bar);
    	mProgressBar.setProgress(0);
    	mProgressBar.setMax(PROGRESS_MAX);
    }

    private void configDatabase() {
    	SharedPreferences settings = getPreferences(0);
        boolean isDBPopulated = settings.getBoolean(SETTINGS_IS_DB_POPULATED, false);
        
        if (ServerURL.checkNetworkStatus(getBaseContext()) && !isDBPopulated) {
            populateCategory();
            populateGrocery();
            populateStoreParent();
            populateStore();
            populateFlyer();
        } else {
            configHandler();
        }
    }

    private void populateCategory() {
        Intent intent = new Intent(getBaseContext(), NetworkHandler.class);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.CAT);
        startService(intent);
    }

    private void populateGrocery() {
        Intent intent = new Intent(getBaseContext(), NetworkHandler.class);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.GRO);
        startService(intent);
    }

    private void populateStoreParent() {
        Intent intent = new Intent(getBaseContext(), NetworkHandler.class);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.STO_PAR);
        startService(intent);
    }

    private void populateStore() {
        Intent intent = new Intent(getBaseContext(), NetworkHandler.class);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.STO);
        startService(intent);
    }

    private void populateFlyer() {
        Intent intent = new Intent(getBaseContext(), NetworkHandler.class);
        intent.putExtra(NetworkHandler.REFRESH_CONTENT, NetworkHandler.FLY);
        startService(intent);
    }

    private void configHandler() {
    	mProgressBar.setProgress(PROGRESS_MAX);
        Handler handler = new Handler();
        // wait a bit, then start the home screen
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // make sure we close the splash screen so the user won't come
                // back when it presses back key
                finish();
                if (!mIsBackButtonPressed) {
                    // start the home screen if the back button wasn't pressed
                    // already
                    Intent intent = new Intent(SplashScreen.this, GroceryFragmentActivity.class);
                    SplashScreen.this.startActivity(intent);
                }
            }
        }, SPLASH_DURATION);
    }

    @Override
    public void onBackPressed() {
        // set the flag to true so the next activity won't start up
        mIsBackButtonPressed = true;
        super.onBackPressed();
    }

    private void configLocationPoll() {
        AlarmManager locationAlarm = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent locationIntent = new Intent(this, LocationMonitor.class);
        locationIntent.putExtra(LocationMonitor.EXTRA_INTENT, new Intent(this, LocationReceiver.class));
        locationIntent.putExtra(LocationMonitor.EXTRA_PROVIDER, LocationManager.NETWORK_PROVIDER);
        PendingIntent locationPendingIntent = PendingIntent.getBroadcast(this, 0, locationIntent, 0);
        locationAlarm.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), LocationReceiver.pollingPeriod, locationPendingIntent);
        
        mProgressBar.incrementProgressBy(10);
    }

    private class RefreshStatusReceiver extends BroadcastReceiver {
        private RefreshStatusReceiver() {

        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int requestType = intent.getBundleExtra("bundle").getInt(NetworkHandler.REQUEST_TYPE);

            // Network handler services are processed in the order they are called in
            if (requestType == NetworkHandler.FLY) {
            	SharedPreferences settings = getPreferences(0);
            	SharedPreferences.Editor settingsEditor = settings.edit();
            	settingsEditor.putBoolean(SETTINGS_IS_DB_POPULATED, true);
            	settingsEditor.commit();
                
                configHandler();
            }
        }
    }
    
    public static void incrementProgressBar(int inc) {    	
    	if (inc > 0 && mProgressBar != null)
    		mProgressBar.incrementProgressBy(inc);
    }

}