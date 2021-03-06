package ca.grocerygo.android.services.location;

import ca.grocerygo.android.settings.SettingsManager;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.SystemClock;

public class LocationServiceReceiver extends BroadcastReceiver {
	public static final String LOCATION_SERVICE_RECEIVER_ENABLE = "GOTG_ENABLE_NOTIFICATIONS";
	public static final String LOCATION_SERVICE_RECEIVER_DISABLE = "GOTG_DISABLE_NOTIFICATIONS";
	public static final String LOCATION_UPDATE_REQUESTED = "GOTG_UPDATE_LOCATION";
	
	@Override
	public void onReceive(Context context, Intent intent) {
		AlarmManager locationAlarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		Intent locationIntent = new Intent(context, LocationMonitor.class);
		locationIntent.putExtra(LocationMonitor.EXTRA_INTENT, new Intent(context, LocationReceiver.class));
		locationIntent.putExtra(LocationMonitor.EXTRA_PROVIDER, LocationManager.NETWORK_PROVIDER);
		PendingIntent locationPendingIntent = PendingIntent.getBroadcast(context, 0, locationIntent, 0);
		
		if (intent.getAction() == Intent.ACTION_BOOT_COMPLETED) {
			// If the intent comes from system startup
			if (SettingsManager.getNotificationsEnabled(context)) {
				// If notifications are enabled
				locationAlarm.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), SettingsManager.getNotificationFrequency(context), locationPendingIntent);
			}
		} else if (intent.getAction() == LocationServiceReceiver.LOCATION_SERVICE_RECEIVER_ENABLE) {
			// If notifications are enabled
			locationAlarm.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), SettingsManager.getNotificationFrequency(context), locationPendingIntent);
		} else if (intent.getAction() == LocationServiceReceiver.LOCATION_SERVICE_RECEIVER_DISABLE) {
			// If notifications are disabled, then cancel any alarms - in essence disabling the notification service
			locationAlarm.cancel(locationPendingIntent);
		} else if (intent.getAction() == LocationServiceReceiver.LOCATION_UPDATE_REQUESTED) {
			// A one-time update requested from location manager
			context.sendBroadcast(locationIntent);
		}
	}
}
