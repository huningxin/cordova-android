package com.phonegap.test;

import com.phonegap.CordovaWebView;

import android.app.Activity;
import android.os.Bundle;

public class PhoneGapSplash extends Activity {
    CordovaWebView phoneGap;
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        phoneGap = (CordovaWebView) findViewById(R.id.phoneGapView);
        phoneGap.init();
        phoneGap.loadUrl("file:///android_asset/index.html", 5000);
    }
    
    public void onDestroy()
    {
        super.onDestroy();
        phoneGap.onDestroy();
    }
}
