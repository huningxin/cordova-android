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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedList;

import org.apache.cordova.api.CordovaInterface;
import org.apache.cordova.api.PluginResult;
import org.json.JSONObject;

import android.os.Message;
import android.util.Log;
import android.webkit.WebView;

/**
 * Holds the list of messages to be sent to the WebView.
 */
public class NativeToJsMessageQueue {
    private static final String LOG_TAG = "JsMessageQueue";

    // This must match the default value in incubator-cordova-js/lib/android/exec.js
    private static final int DEFAULT_BRIDGE_MODE = 1;
    
    // Set this to true to force plugin results to be encoding as
    // JS instead of the custom format (useful for benchmarking).
    private static final boolean FORCE_ENCODE_USING_EVAL = false;
    
    /**
     * The index into registeredListeners to treat as active. 
     */
    private int activeListenerIndex;
    
    /**
     * When true, the active listener is not fired upon enqueue. When set to false,
     * the active listener will be fired if the queue is non-empty. 
     */
    private boolean paused;
    
    /**
     * The list of JavaScript statements to be sent to JavaScript.
     */
    private final LinkedList<JsMessage> queue = new LinkedList<JsMessage>();

    /**
     * The array of listeners that can be used to send messages to JS.
     */
    private final BridgeMode[] registeredListeners;    
    
    private final CordovaInterface cordova;
    private final CordovaWebView webView;
        
    public NativeToJsMessageQueue(CordovaWebView webView, CordovaInterface cordova) {
        this.cordova = cordova;
        this.webView = webView;
        registeredListeners = new BridgeMode[5];
        registeredListeners[0] = null;  // Polling. Requires no logic.
        registeredListeners[1] = new CallbackBridgeMode();
        registeredListeners[2] = new LoadUrlBridgeMode();
        registeredListeners[3] = new OnlineEventsBridgeMode();
        registeredListeners[4] = new PrivateApiBridgeMode();
        reset();
    }
    
    /**
     * Changes the bridge mode.
     */
    public void setBridgeMode(int value) {
        if (value < 0 || value >= registeredListeners.length) {
            Log.d(LOG_TAG, "Invalid NativeToJsBridgeMode: " + value);
        } else {
            if (value != activeListenerIndex) {
                Log.d(LOG_TAG, "Set native->JS mode to " + value);
                synchronized (this) {
                    activeListenerIndex = value;
                    BridgeMode activeListener = registeredListeners[value];
                    if (!paused && !queue.isEmpty() && activeListener != null) {
                        activeListener.onNativeToJsMessageAvailable();
                    }
                }
            }
        }
    }
    
    /**
     * Clears all messages and resets to the default bridge mode.
     */
    public void reset() {
        synchronized (this) {
            queue.clear();
            setBridgeMode(DEFAULT_BRIDGE_MODE);
        }
    }

    private int calculatePackedMessageLength(JsMessage message) {
        int messageLen = message.calculateEncodedLength();
        String messageLenStr = String.valueOf(messageLen);
        return messageLenStr.length() + messageLen + 1;        
    }
    
    private void packMessage(JsMessage message, StringBuilder sb) {
        sb.append(message.calculateEncodedLength())
          .append(' ');
        message.encodeAsMessage(sb);
    }
    
    public String popAndEncode() {
        synchronized (this) {
            if (queue.isEmpty()) {
                return null;
            }
            JsMessage message = queue.removeFirst();
            StringBuilder sb = new StringBuilder(calculatePackedMessageLength(message));
            packMessage(message, sb);
            return sb.toString();
        }        
    }
    
    /**
     * Combines and returns all messages combined into a single string.
     * Clears the queue.
     * Returns null if the queue is empty.
     */
    public String popAllAndEncode() {
        synchronized (this) {
            if (queue.isEmpty()) {
                return null;
            }
            int totalPayloadLen = 0;
            for (JsMessage message : queue) {
                totalPayloadLen += calculatePackedMessageLength(message);
            }

            StringBuilder sb = new StringBuilder(totalPayloadLen);
            for (JsMessage message : queue) {
                packMessage(message, sb);
            }
            queue.clear();
            return sb.toString();
        }
    }
    
    private String popAllAndEncodeAsJs() {
        synchronized (this) {
            int length = queue.size();
            if (length == 0) {
                return null;
            }
            int totalPayloadLen = 16 * length; // accounts for try & finally.
            for (JsMessage message : queue) {
                totalPayloadLen += message.calculateEncodedLength(); // overestimate.
            }
            StringBuilder sb = new StringBuilder(totalPayloadLen);
            // Wrap each statement in a try/finally so that if one throws it does 
            // not affect the next.
            int i = 0;
            for (JsMessage message : queue) {
                if (++i == length) {
                    message.encodeAsJsMessage(sb);
                } else {
                    sb.append("try{");
                    message.encodeAsJsMessage(sb);
                    sb.append("}finally{");
                }
            }
            for ( i = 1; i < length; ++i) {
                sb.append('}');
            }
            queue.clear();
            return sb.toString();
        }
    }   

    /**
     * Add a JavaScript statement to the list.
     */
    public void addJavaScript(String statement) {
        enqueueMessage(new JsMessage(statement));
    }

    /**
     * Add a JavaScript statement to the list.
     */
    public void addPluginResult(PluginResult result, String callbackId) {
        // Don't send anything if there is no result and there is no need to
        // clear the callbacks.
        boolean noResult = result.getStatus() == PluginResult.Status.NO_RESULT.ordinal();
        boolean keepCallback = result.getKeepCallback();
        if (noResult && keepCallback) {
            return;
        }
        JsMessage message = new JsMessage(result, callbackId);
        if (FORCE_ENCODE_USING_EVAL) {
            StringBuilder sb = new StringBuilder(message.calculateEncodedLength() + 50);
            message.encodeAsJsMessage(sb);
            message = new JsMessage(sb.toString());
        }

        enqueueMessage(message);
    }
    
    private void enqueueMessage(JsMessage message) {
        synchronized (this) {
            queue.add(message);
            if (!paused && registeredListeners[activeListenerIndex] != null) {
                registeredListeners[activeListenerIndex].onNativeToJsMessageAvailable();
            }
        }        
    }
    
    public void setPaused(boolean value) {
        if (paused && value) {
            // This should never happen. If a use-case for it comes up, we should
            // change pause to be a counter.
            Log.e(LOG_TAG, "nested call to setPaused detected.", new Throwable());
        }
        paused = value;
        if (!value) {
            synchronized (this) {
                if (!queue.isEmpty() && registeredListeners[activeListenerIndex] != null) {
                    registeredListeners[activeListenerIndex].onNativeToJsMessageAvailable();
                }
            }   
        }
    }
    
    public boolean getPaused() {
        return paused;
    }

    private interface BridgeMode {
        void onNativeToJsMessageAvailable();
    }
    
    /** Uses a local server to send messages to JS via an XHR */
    private class CallbackBridgeMode implements BridgeMode {
        public void onNativeToJsMessageAvailable() {
            if (webView.callbackServer != null) {
                webView.callbackServer.onNativeToJsMessageAvailable(NativeToJsMessageQueue.this);
            }
        }
    }
    
    /** Uses webView.loadUrl("javascript:") to execute messages. */
    private class LoadUrlBridgeMode implements BridgeMode {
        final Runnable runnable = new Runnable() {
            public void run() {
                String js = popAllAndEncodeAsJs();
                if (js != null) {
                    webView.loadUrlNow("javascript:" + js);
                }
            }
        };
        
        public void onNativeToJsMessageAvailable() {
            cordova.getActivity().runOnUiThread(runnable);
        }
    }

    /** Uses online/offline events to tell the JS when to poll for messages. */
    private class OnlineEventsBridgeMode implements BridgeMode {
        boolean online = true;
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (!queue.isEmpty()) {
                    online = !online;
                    webView.setNetworkAvailable(online);
                }
            }                
        };
        OnlineEventsBridgeMode() {
            webView.setNetworkAvailable(true);
        }
        public void onNativeToJsMessageAvailable() {
            // TODO(agrieve): consider running this *not* on the main thread, since it just
            // sends a message under-the-hood anyways.
            cordova.getActivity().runOnUiThread(runnable);
        }
    }
    
    /**
     * Uses Java reflection to access an API that lets us eval JS.
     * Requires Android 3.2.4 or above. 
     */
    private class PrivateApiBridgeMode implements BridgeMode {
    	// Message added in commit:
    	// http://omapzoom.org/?p=platform/frameworks/base.git;a=commitdiff;h=9497c5f8c4bc7c47789e5ccde01179abc31ffeb2
    	// Which first appeared in 3.2.4ish.
    	private static final int EXECUTE_JS = 194;
    	
    	Method sendMessageMethod;
    	Object webViewCore;
    	boolean initFailed;

    	@SuppressWarnings("rawtypes")
    	private void initReflection() {
        	Object webViewObject = webView;
    		Class webViewClass = WebView.class;
        	try {
    			Field f = webViewClass.getDeclaredField("mProvider");
    			f.setAccessible(true);
    			webViewObject = f.get(webView);
    			webViewClass = webViewObject.getClass();
        	} catch (Throwable e) {
        		// mProvider is only required on newer Android releases.
    		}
        	
        	try {
    			Field f = webViewClass.getDeclaredField("mWebViewCore");
                f.setAccessible(true);
    			webViewCore = f.get(webViewObject);
    			
    			if (webViewCore != null) {
    				sendMessageMethod = webViewCore.getClass().getDeclaredMethod("sendMessage", Message.class);
	    			sendMessageMethod.setAccessible(true);	    			
    			}
    		} catch (Throwable e) {
    			initFailed = true;
				Log.e(LOG_TAG, "PrivateApiBridgeMode failed to find the expected APIs.", e);
    		}
    	}
    	
        public void onNativeToJsMessageAvailable() {
        	if (sendMessageMethod == null && !initFailed) {
        		initReflection();
        	}
        	// webViewCore is lazily initialized, and so may not be available right away.
        	if (sendMessageMethod != null) {
	        	String js = popAllAndEncodeAsJs();
	        	Message execJsMessage = Message.obtain(null, EXECUTE_JS, js);
				try {
				    sendMessageMethod.invoke(webViewCore, execJsMessage);
				} catch (Throwable e) {
					Log.e(LOG_TAG, "Reflection message bridge failed.", e);
				}
        	}
        }
    }    
    private static class JsMessage {
        final String jsPayloadOrCallbackId;
        final PluginResult pluginResult;
        JsMessage(String js) {
            jsPayloadOrCallbackId = js;
            pluginResult = null;
        }
        JsMessage(PluginResult pluginResult, String callbackId) {
            jsPayloadOrCallbackId = callbackId;
            this.pluginResult = pluginResult;
        }
        
        int calculateEncodedLength() {
            if (pluginResult == null) {
                return jsPayloadOrCallbackId.length() + 1;
            }
            int statusLen = String.valueOf(pluginResult.getStatus()).length();
            int ret = 2 + statusLen + 1 + jsPayloadOrCallbackId.length() + 1;
            switch (pluginResult.getMessageType()) {
                case PluginResult.MESSAGE_TYPE_BOOLEAN:
                    ret += 1;
                    break;
                case PluginResult.MESSAGE_TYPE_NUMBER: // n
                    ret += 1 + pluginResult.getMessage().length();
                    break;
                case PluginResult.MESSAGE_TYPE_STRING: // s
                    ret += 1 + pluginResult.getStrMessage().length();
                    break;
                case PluginResult.MESSAGE_TYPE_JSON:
                default:
                    ret += pluginResult.getMessage().length();
            }
            return ret;
        }
        
        void encodeAsMessage(StringBuilder sb) {
            if (pluginResult == null) {
                sb.append('J')
                  .append(jsPayloadOrCallbackId);
                return;
            }
            int status = pluginResult.getStatus();
            boolean noResult = status == PluginResult.Status.NO_RESULT.ordinal();
            boolean resultOk = status == PluginResult.Status.OK.ordinal();
            boolean keepCallback = pluginResult.getKeepCallback();

            sb.append((noResult || resultOk) ? 'S' : 'F')
              .append(keepCallback ? '1' : '0')
              .append(status)
              .append(' ')
              .append(jsPayloadOrCallbackId)
              .append(' ');
            switch (pluginResult.getMessageType()) {
                case PluginResult.MESSAGE_TYPE_BOOLEAN:
                    sb.append(pluginResult.getMessage().charAt(0)); // t or f.
                    break;
                case PluginResult.MESSAGE_TYPE_NUMBER: // n
                    sb.append('n')
                      .append(pluginResult.getMessage());
                    break;
                case PluginResult.MESSAGE_TYPE_STRING: // s
                    sb.append('s')
                      .append(pluginResult.getStrMessage());
                    break;
                case PluginResult.MESSAGE_TYPE_JSON:
                default:
                    sb.append(pluginResult.getMessage()); // [ or {
            }
        }
        
        void encodeAsJsMessage(StringBuilder sb) {
            if (pluginResult == null) {
                sb.append(jsPayloadOrCallbackId);
            } else {
                int status = pluginResult.getStatus();
                boolean success = (status == PluginResult.Status.OK.ordinal()) || (status == PluginResult.Status.NO_RESULT.ordinal());
                sb.append("cordova.callbackFromNative('")
                  .append(jsPayloadOrCallbackId)
                  .append("',")
                  .append(success)
                  .append(",")
                  .append(status)
                  .append(",")
                  .append(pluginResult.getMessage())
                  .append(",")
                  .append(pluginResult.getKeepCallback())
                  .append(");");
            }
        }
    }
}
