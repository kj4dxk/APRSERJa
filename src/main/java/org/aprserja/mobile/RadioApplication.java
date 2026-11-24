// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.BiConsumer;

public final class RadioApplication extends Application {
    private static final Set<String> STATE=new java.util.HashSet<>(java.util.Arrays.asList("status","tncStatus","stations","gps","tracking","diagnostic","regionReady","history"));
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String,String> latest=new LinkedHashMap<>();
    private BiConsumer<String,String> screen;
    MobileAprs aprs;

    @Override public void onCreate() {
        super.onCreate();
        aprs=new MobileAprs(this,(type,data)->{publish(type,data);return kotlin.Unit.INSTANCE;});
    }

    void publish(String type,String data) {
        Runnable deliver=()->{
            if(STATE.contains(type)) latest.put(type,data);
            if(screen!=null) screen.accept(type,data);
        };
        if(Looper.myLooper()==Looper.getMainLooper()) deliver.run();
        else main.post(deliver);
    }

    void attach(BiConsumer<String,String> receiver) {
        screen=receiver;
        latest.forEach(receiver);
    }

    void detach(BiConsumer<String,String> receiver) {
        if(screen==receiver) screen=null;
    }
}
