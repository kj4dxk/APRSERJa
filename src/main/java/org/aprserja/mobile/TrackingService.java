// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;

public final class TrackingService extends Service {
    static final String GPS_ON="gps-on", GPS_OFF="gps-off", RADIO_ON="radio-on", RADIO_OFF="radio-off";
    private static final String STOP="stop", CHANNEL="mobile-radio";
    private boolean gpsActive, radioActive;
    private LocationManager gps;
    private RadioApplication app;
    private final LocationListener location=new LocationListener() {
        public void onLocationChanged(android.location.Location fix) {
            app.aprs.position(fix);
            app.publish("gps","{\"lat\":"+fix.getLatitude()+",\"lon\":"+fix.getLongitude()+"}");
        }
        public void onProviderDisabled(String provider) {}
        public void onProviderEnabled(String provider) {}
        public void onStatusChanged(String provider,int status,android.os.Bundle extras) {}
    };

    static void command(Context context,String action) {
        Intent intent=new Intent(context,TrackingService.class).setAction(action);
        if(GPS_OFF.equals(action) || RADIO_OFF.equals(action)) context.startService(intent);
        else context.startForegroundService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        app=(RadioApplication)getApplication();
        gps=getSystemService(LocationManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL,"Mobile radio and GPS",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?STOP:intent.getAction();
        if(STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if(RADIO_ON.equals(action)) radioActive=true;
        if(RADIO_OFF.equals(action)) radioActive=false;
        if(GPS_OFF.equals(action)) stopGps();
        if(GPS_ON.equals(action) && !gpsActive) {
            try {
                gpsActive=true;
                foreground();
                gps.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,1,location);
                app.publish("tracking","on");
            } catch (SecurityException | IllegalArgumentException failure) {
                stopGps();
                app.publish("diagnostic","GPS unavailable. Check location permissions and settings.");
            }
        }
        if(gpsActive || radioActive) foreground();
        else stopSelf();
        return START_NOT_STICKY;
    }

    private void foreground() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,Main.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TrackingService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        String detail=gpsActive && radioActive ? "GPS and TNC active" : gpsActive ? "GPS tracking active" : "TNC session active";
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("APRSERJa").setContentText(detail).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"Stop mobile session",stop).build()).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29) {
            int types=(gpsActive ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0)
                | (radioActive ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0);
            startForeground(1,notification,types);
        } else startForeground(1,notification);
    }

    private void stopGps() {
        if(gpsActive) gps.removeUpdates(location);
        gpsActive=false;
        app.publish("tracking","off");
    }

    @Override public void onDestroy() {
        stopGps();
        if(radioActive) app.aprs.disconnectRadio();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {return null;}
}
