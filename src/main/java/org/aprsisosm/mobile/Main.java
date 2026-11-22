package org.aprsisosm.mobile;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
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
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=0){requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION},9);return;}
        gps.requestLocationUpdates("gps",1000,1,listener);
    });}
    public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==9&&checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==0)track();}
    @JavascriptInterface public void speak(String words){runOnUiThread(()->{if(voiceReady)tts.speak(words,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"navigation");});}
    private volatile Socket active;
    private volatile PrintWriter output;
    private volatile String identity="";
    private volatile boolean verified;
    private String peerPattern="[A-Z0-9]{1,6}(-[0-9]{1,2})?";
    private final java.util.concurrent.ExecutorService writes=java.util.concurrent.Executors.newSingleThreadExecutor();
    private void event(String type,String data){
        runOnUiThread(()->view.evaluateJavascript("receive("+JSONObject.quote(type)+","+JSONObject.quote(data)+")",null));
    }
    public void onCreate(Bundle state){
        super.onCreate(state);gps=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);tts=new android.speech.tts.TextToSpeech(this,status->{if(status==0){for(android.speech.tts.Voice voice:tts.getVoices()==null?java.util.Collections.<android.speech.tts.Voice>emptySet():tts.getVoices()){if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals("en")){voiceReady=tts.setVoice(voice)==0;break;}}}});
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0); view=new WebView(this);
        view.setPadding(0,0,0,0);
        view.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}});
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
    @JavascriptInterface public synchronized void disconnect(){Socket s=active;active=null;verified=false;output=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
    @JavascriptInterface public synchronized void connect(String host,int port,String call,String pass,double lat,double lon){
        disconnect();
        if(!call.matches(peerPattern)||!pass.matches("-?[0-9]{1,6}")||port<1||port>65535){event("status","Check callsign, port and passcode");return;}
        Socket s=new Socket();active=s;identity=call;
        new Thread(()->{try{
            s.connect(new InetSocketAddress(host,port),12000);
            PrintWriter w=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.US_ASCII),true);
            synchronized(this){if(active!=s)return;output=w;}
            w.print("user "+call+" pass "+pass+" vers APRSISOSM 0.2.0 filter r/"+lat+"/"+lon+"/100\r\n");w.flush();
            BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));String line;
            while((line=r.readLine())!=null && active==s){
                if(line.startsWith("# logresp ")){synchronized(this){if(active!=s)return;verified=line.startsWith("# logresp "+call+" verified,");}event("status",line);}
                else if(!line.startsWith("#")) event("packet",line);
            }
        }catch(Exception e){if(active==s)event("status",e.toString());}finally{synchronized(this){if(active==s){disconnect();event("status","Disconnected");}}}}).start();
    }
    @JavascriptInterface public void send(String peer,String body){
        Socket session=active;
        writes.execute(()->{synchronized(this){
            if(session==null||session!=active||!verified||output==null||!peer.matches(peerPattern)||!body.matches("[ -~]{1,67}")){event("status","Message requires a verified session, valid callsign and 1–67 ASCII characters");return;}
            String packet=identity+">APRS,TCPIP*::"+String.format(java.util.Locale.ROOT,"%-9s",peer)+":"+body;
            output.print(packet+"\r\n");output.flush();if(output.checkError())event("status","Send failed");else event("sent",packet);
        }});
    }
    protected void onDestroy(){gps.removeUpdates(listener);tts.shutdown();disconnect();writes.shutdownNow();super.onDestroy();}
}



