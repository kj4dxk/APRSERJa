package org.aprserja.mobile;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.*;
import java.io.*;
import org.json.JSONObject;

public class Main extends Activity {
    private WebView view;
    private android.speech.tts.TextToSpeech tts;
    private boolean voiceReady;
    private android.location.LocationManager gps;
    private final android.location.LocationListener listener = new android.location.LocationListener(){
        public void onLocationChanged(android.location.Location l){event("gps","{\"lat\":"+l.getLatitude()+",\"lon\":"+l.getLongitude()+"}");}
        public void onProviderDisabled(String p){}
        public void onProviderEnabled(String p){}
        public void onStatusChanged(String p,int status,Bundle extras){}
    };
    @JavascriptInterface public void track(){runOnUiThread(()->{
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION},9);return;}
        gps.requestLocationUpdates("gps",1000,1,listener);
    });}
    public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==9&&checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)track();}
    @JavascriptInterface public void speak(String words){runOnUiThread(()->{if(voiceReady)tts.speak(words,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"navigation");});}
    private MobileAprs aprs;
    private void event(String type,String data){
        runOnUiThread(()->{if(!isDestroyed())view.evaluateJavascript("receive("+JSONObject.quote(type)+","+JSONObject.quote(data)+")",null);});
    }
    public void onCreate(Bundle state){
        super.onCreate(state);gps=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);tts=new android.speech.tts.TextToSpeech(this,status->{if(status==0){for(android.speech.tts.Voice voice:tts.getVoices()==null?java.util.Collections.<android.speech.tts.Voice>emptySet():tts.getVoices()){if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals("en")){voiceReady=tts.setVoice(voice)==0;break;}}}});
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0); view=new WebView(this);
        view.setPadding(0,0,0,0);
        view.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}});
        aprs=new MobileAprs(this,(type,data)->{event(type,data);return kotlin.Unit.INSTANCE;});
        view.addJavascriptInterface(this,"Radio");
        setContentView(view);
        view.loadUrl("file:///android_asset/index.html");
    }
    @JavascriptInterface public void region(){runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),1));}
    protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req!=1||result!=RESULT_OK||data==null)return;
        new Thread(()->{try(InputStream in=getContentResolver().openInputStream(data.getData())){
            event("region",readRegion(in));
        }catch(Exception e){event("status",e.toString());}}).start();
    }
    private String readRegion(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
        while((n=in.read(buf))!=-1){if(out.size()+n>4000000)throw new IOException("Choose an OSM XML extract under 4 MB");out.write(buf,0,n);}
        return out.toString("UTF-8");
    }
    @JavascriptInterface public String read(String key){return getSharedPreferences("radio",0).getString(key,"");}
    @JavascriptInterface public boolean save(String key,String value){return getSharedPreferences("radio",0).edit().putString(key,value).commit();}
    @JavascriptInterface public String sample(){try(InputStream in=getAssets().open("boston.osm")){return readRegion(in);}catch(Exception e){return "";}}
    @JavascriptInterface public void importChats(String chats,String outbox){aprs.importLegacy(chats,outbox);}
    @JavascriptInterface public void selectChat(String call,String peer){aprs.select(call,peer);}
    @JavascriptInterface public void queueChat(String call,String peer,String body){aprs.enqueue(call,peer,body);}
    @JavascriptInterface public void disconnect(){aprs.disconnect();}
    @JavascriptInterface public void connect(String host,int port,String call,String pass,double lat,double lon){aprs.connect(host,port,call,pass,lat,lon);}
    protected void onDestroy(){gps.removeUpdates(listener);tts.shutdown();aprs.close();super.onDestroy();}
}
