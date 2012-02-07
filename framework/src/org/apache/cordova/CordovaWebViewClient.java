/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
*/


package org.apache.cordova;

import java.util.Hashtable;

import org.apache.cordova.api.LOG;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.view.View;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;


/**
 * The webview client receives notifications about appView
 */
public class CordovaWebViewClient extends WebViewClient {

    Activity ctx;
    CordovaWebView appView;
    String TAG = "CordovaClient";
    private boolean firstRunComplete = false;
    private String lastUrl;
    
    /** The authorization tokens. */
    private Hashtable<String, AuthenticationToken> authenticationTokens = new Hashtable<String, AuthenticationToken>();
    
    
   /**
     * Constructor.
     * 
     * @param ctx
     */
    public CordovaWebViewClient(Activity ctx, CordovaWebView appCode) {
        this.ctx = ctx;
        this.appView = appCode;
        appCode.setWebViewClient(this);
    }
    
    /**
     * Sets the authentication token.
     * 
     * @param authenticationToken
     *            the authentication token
     * @param host
     *            the host
     * @param realm
     *            the realm
     */
    public void setAuthenticationToken(AuthenticationToken authenticationToken, String host, String realm) {
        
        if(host == null) {
            host = "";
        }
        
        if(realm == null) {
            realm = "";
        }
        
        authenticationTokens.put(host.concat(realm), authenticationToken);
    }
    
    /**
     * Removes the authentication token.
     * 
     * @param host
     *            the host
     * @param realm
     *            the realm
     * @return the authentication token or null if did not exist
     */
    public AuthenticationToken removeAuthenticationToken(String host, String realm) {
        return authenticationTokens.remove(host.concat(realm));
    }
    
    /**
     * Gets the authentication token.
     * 
     * In order it tries:
     * 1- host + realm
     * 2- host
     * 3- realm
     * 4- no host, no realm
     * 
     * @param host
     *            the host
     * @param realm
     *            the realm
     * @return the authentication token
     */
    public AuthenticationToken getAuthenticationToken(String host, String realm) {
        AuthenticationToken token = null;
        
        token = authenticationTokens.get(host.concat(realm));
        
        if(token == null) {
            // try with just the host
            token = authenticationTokens.get(host);
            
            // Try the realm
            if(token == null) {
                token = authenticationTokens.get(realm);
            }
            
            // if no host found, just query for default
            if(token == null) {      
                token = authenticationTokens.get("");
            }
        }
        
        return token;
    }
    
    /**
     * Clear all authentication tokens.
     */
    public void clearAuthenticationTokens() {
        authenticationTokens.clear();
    }
    
    
    
    /*
     * Utility methods for WebDriver
     */
    
    public CordovaWebViewClient(Activity testActivity) {
        this.ctx = testActivity;
    }
    
    public void setCordovaView(CordovaWebView view)
    {
        this.appView = view;
    }

   /**
    * Give the host application a chance to take over the control when a new url 
     * is about to be loaded in the current WebView.
     * 
     * @param view          The WebView that is initiating the callback.
     * @param url           The url to be loaded.
     * @return              true to override, false for default behavior
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        
        // First give any plugins the chance to handle the url themselves
        if (appView.appCode.pluginManager.onOverrideUrlLoading(url)) {
        }
        
        // If dialing phone (tel:5551212)
        else if (url.startsWith(WebView.SCHEME_TEL)) {
            try {
                Intent intent = new Intent(Intent.ACTION_DIAL);
                intent.setData(Uri.parse(url));
                ctx.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
               LOG.e(TAG, "Error dialing "+url+": "+ e.toString());
            }
        }

        // If displaying map (geo:0,0?q=address)
        else if (url.startsWith("geo:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                ctx.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
               LOG.e(TAG, "Error showing map "+url+": "+ e.toString());
            }
        }

        // If sending email (mailto:abc@corp.com)
        else if (url.startsWith(WebView.SCHEME_MAILTO)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(url));
                ctx.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                LOG.e(TAG, "Error sending email "+url+": "+ e.toString());
            }
        }

        // If sms:5551212?body=This is the message
        else if (url.startsWith("sms:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);

                // Get address
                String address = null;
                int parmIndex = url.indexOf('?');
                if (parmIndex == -1) {
                    address = url.substring(4);
                }
                else {
                    address = url.substring(4, parmIndex);

                    // If body, then set sms body
                    Uri uri = Uri.parse(url);
                    String query = uri.getQuery();
                    if (query != null) {
                        if (query.startsWith("body=")) {
                            intent.putExtra("sms_body", query.substring(5));
                        }
                    }
                }
                intent.setData(Uri.parse("sms:"+address));
                intent.putExtra("address", address);
                intent.setType("vnd.android-dir/mms-sms");
                ctx.startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
               LOG.e(TAG, "Error sending sms "+url+":"+ e.toString());
            }
        }

        // All else
        else {

            // If our app or file:, then load into a new Cordova webview container by starting a new instance of our activity.
            // Our app continues to run.  When BACK is pressed, our app is redisplayed.
            if (url.startsWith("file://")) {
                appView.appCode.loadUrl(url);
            }
            /*
            if (url.startsWith("file://") || url.indexOf(this.ctx.baseUrl) == 0 || ctx.isUrlWhiteListed(url)) {
                this.ctx.loadUrl(url);
            }
            */
            // If not our application, let default viewer handle
            else {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(url));
                    ctx.startActivity(intent);
                } catch (android.content.ActivityNotFoundException e) {
                    LOG.e(TAG, "Error loading url "+url, e);
                }
            }
        }
        return true;
    }
    
   /**
     * On received http auth request.
     * The method reacts on all registered authentication tokens. There is one and only one authentication token for any host + realm combination 
     * 
     * @param view
     *            the view
     * @param handler
     *            the handler
     * @param host
     *            the host
     * @param realm
     *            the realm
     */
    @Override
    public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host,
            String realm) {
       
        // get the authentication token
        AuthenticationToken token = getAuthenticationToken(host,realm);
       
        if(token != null) {
            handler.proceed(token.getUserName(), token.getPassword());
        }
    }

    
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        Log.d("CordovaWebViewClient", "I got a page started for = " + url);
        Log.d("CordovaWebViewClient", "can go back " + view.canGoBack());

        // Clear history so history.back() doesn't do anything.  
        // So we can reinit() native side CallbackServer & PluginManager.
        //view.clearHistory(); 
    }
    
    /**
     * Notify the host application that a page has finished loading.
     * 
     * @param view          The webview initiating the callback.
     * @param url           The url of the page.
     */
    @Override
    public void onPageFinished(WebView view, String url) {
        Log.d("CordovaWebViewClient", "I got a page finished for = " + url);
        super.onPageFinished(view, url);
        
        String baseUrl = url.split("#")[0];
        if(firstRunComplete && !lastUrl.equals(baseUrl))
        {
            this.appView.reinit(url);
        }
        firstRunComplete  = true;
        lastUrl = baseUrl;
        
        // Clear timeout flag
        this.appView.loadUrlTimeout++;

        // Try firing the onNativeReady event in JS. If it fails because the JS is
        // not loaded yet then just set a flag so that the onNativeReady can be fired
        // from the JS side when the JS gets to that code.
        if (!url.equals("about:blank")) {
            ctx.appView.loadUrl("javascript:try{ require('cordova/channel').onNativeReady.fire();}catch(e){_nativeReady = true;}");
        }

        // Make app visible after 2 sec in case there was a JS error and PhoneGap JS never initialized correctly
        if (appView.getVisibility() == View.INVISIBLE) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        Thread.sleep(2000);
                        ctx.runOnUiThread(new Runnable() {
                            public void run() {
                                appView.setVisibility(View.VISIBLE);
                           }
                       });
                    } catch (InterruptedException e) {
                    }
                }
            });
            t.start();
        }


        // Shutdown if blank loaded
        if (url.equals("about:blank")) {
            if (appView.appCode.callbackServer != null) {
                appView.appCode.callbackServer.destroy();
            }
            //((Object) ctx).endActivity();
            ctx.finish();
       }
   }
    
    /**
     * Report an error to the host application. These errors are unrecoverable (i.e. the main resource is unavailable). 
     * The errorCode parameter corresponds to one of the ERROR_* constants.
     *
     * @param view          The WebView that is initiating the callback.
     * @param errorCode     The error code corresponding to an ERROR_* value.
     * @param description   A String describing the error.
     * @param failingUrl    The url that failed to load. 
     */
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        LOG.d(TAG, "DroidGap: GapViewClient.onReceivedError: Error code=%s Description=%s URL=%s", errorCode, description, failingUrl);

        // Clear timeout flag
        appView.loadUrlTimeout++;

        // Stop "app loading" spinner if showing
        //this.ctx.spinnerStop();

        // Handle error
        //((Object) ctx).onReceivedError(errorCode, description, failingUrl);
   }
   
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        
        final String packageName = this.ctx.getPackageName();
        final PackageManager pm = this.ctx.getPackageManager();
        ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            if ((appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // debug = true
                handler.proceed();
                return;
            } else {
                // debug = false
               super.onReceivedSslError(view, handler, error);    
           }
        } catch (NameNotFoundException e) {
            // When it doubt, lock it out!
            super.onReceivedSslError(view, handler, error);
        }
    }
}
