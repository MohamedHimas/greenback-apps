package com.greenbackterminal.app;

import android.app.Application;

/** Starts Firebase early, so a notification that wakes the app can be shown. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Push.channel(this);
        Push.init(this);
    }
}
