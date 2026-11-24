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
    private RadioApplication session;
    private final java.util.function.BiConsumer<String,String> screen=this::event;
    @JavascriptInterface public void track(){runOnUiThread(()->{
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION},9);return;}
        TrackingService.command(this,TrackingService.GPS_ON);
        askForTrackingNotifications();
    });}
    private void askForTrackingNotifications() {
        SharedPreferences settings=getSharedPreferences("radio",MODE_PRIVATE);
        if(android.os.Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !settings.getBoolean("asked-tracking-notifications",false)) {
            settings.edit().putBoolean("asked-tracking-notifications",true).apply();
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},11);
        }
    }
    @JavascriptInterface public void stopTracking(){runOnUiThread(()->TrackingService.command(this,TrackingService.GPS_OFF));}
    public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);if(code==9&&checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)track();
        if (code == BLUETOOTH_TNC_PERMISSION && pendingTncDiscovery != null) {
            String kind = pendingTncDiscovery;
            pendingTncDiscovery = null;
            if (tncPermissions(kind).length == 0) aprs.listTncDevices(kind);
            else tncDeviceError(kind, "Grant Android permission to find Bluetooth TNCs");
        }
    }
    private static final int BLUETOOTH_TNC_PERMISSION = 10;

    private String pendingTncDiscovery;

    private String[] tncPermissions(String kind) {
        java.util.List<String> permissions = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT);
            if (kind.equals("ble")) permissions.add(android.Manifest.permission.BLUETOOTH_SCAN);
        } else if (kind.equals("ble")) {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions.stream()
            .filter(permission -> checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            .toArray(String[]::new);
    }

    private void tncDeviceError(String kind, String message) {
        event("tncDeviceError", "{\"kind\":" + JSONObject.quote(kind) + ",\"message\":" + JSONObject.quote(message) + "}");
    }

    @JavascriptInterface public void listTncDevices(String kind) {
        runOnUiThread(() -> {
            if (kind.equals("bluetooth") || kind.equals("ble")) {
                String[] missing = tncPermissions(kind);
                if (missing.length != 0) {
                    pendingTncDiscovery = kind;
                    if (android.os.Build.VERSION.SDK_INT < 31) {
                        missing = new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            android.Manifest.permission.ACCESS_FINE_LOCATION};
                    }
                    requestPermissions(missing, BLUETOOTH_TNC_PERMISSION);
                    return;
                }
            }
            aprs.listTncDevices(kind);
        });
    }

    @JavascriptInterface public void connectTnc(String kind, String protocol, String device, String host, int port,
            int baud, int channel, String call, String path, boolean transmit) {
        runOnUiThread(() -> {
            TrackingService.command(this,TrackingService.RADIO_ON);
            aprs.connectTnc(kind,protocol,device,host,port,baud,channel,call,path,transmit);
            askForTrackingNotifications();
        });
    }

    @JavascriptInterface public void speak(String words){runOnUiThread(()->{if(voiceReady)tts.speak(words,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"navigation");});}
    private MobileAprs aprs;
    private void event(String type,String data){
        runOnUiThread(()->{if(!isDestroyed())view.evaluateJavascript("receive("+JSONObject.quote(type)+","+JSONObject.quote(data)+")",null);});
    }
    public void onCreate(Bundle state){
        super.onCreate(state);session=(RadioApplication)getApplication();tts=new android.speech.tts.TextToSpeech(this,status->{if(status==0){for(android.speech.tts.Voice voice:tts.getVoices()==null?java.util.Collections.<android.speech.tts.Voice>emptySet():tts.getVoices()){if(!voice.isNetworkConnectionRequired()&&voice.getLocale().getLanguage().equals("en")){voiceReady=tts.setVoice(voice)==0;break;}}}});
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0); view=new WebView(this);
        view.setPadding(0,0,0,0);
        view.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.setWebViewClient(new WebViewClient(){
            public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            public void onPageFinished(WebView v,String url){if(!isDestroyed()) session.attach(screen);}
            public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request) {
                if(!REGION_URL.equals(request.getUrl().toString())) return null;
                java.util.Map<String,String> headers=java.util.Collections.singletonMap("Access-Control-Allow-Origin","*");
                try {
                    return new WebResourceResponse("application/xml","UTF-8",200,"OK",headers,
                        new FileInputStream(new File(getFilesDir(),"region.osm")));
                } catch(FileNotFoundException missing) {
                    return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",headers,null);
                }
            }
        });
        aprs=session.aprs;
        view.addJavascriptInterface(this,"Radio");
        setContentView(view);
        view.loadUrl("file:///android_asset/index.html");
    }
    private static final String REGION_URL="https://maps.aprserja.invalid/region.osm";
    @JavascriptInterface public boolean hasImportedRegion(){return new File(getFilesDir(),"region.osm").isFile();}
    @JavascriptInterface public void region(){runOnUiThread(()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),1));}
    protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(req==2){aprs.exportGpx(this,data.getData());return;}
        if(req!=1)return;
        final android.net.Uri source=data.getData();
        new Thread(()->{
            File temporary=null;
            try {
                temporary=File.createTempFile("osm-",".tmp",getFilesDir());
                try(InputStream in=getContentResolver().openInputStream(source);
                    OutputStream out=new FileOutputStream(temporary)) {
                    if(in==null) throw new IOException("Cannot open the selected region");
                    byte[] buffer=new byte[8192];
                    int size=0,n;
                    while((n=in.read(buffer))!=-1) {
                        size+=n;
                        if(size>20_000_000) throw new IOException("Choose an OSM XML extract under 20 MB");
                        out.write(buffer,0,n);
                    }
                }
                try(InputStream in=new FileInputStream(temporary)){
                    org.xmlpull.v1.XmlPullParser parser=android.util.Xml.newPullParser();
                    parser.setInput(in,"UTF-8");
                    if(parser.nextTag()!=org.xmlpull.v1.XmlPullParser.START_TAG || !"osm".equals(parser.getName()))
                        throw new IOException("Choose an OpenStreetMap XML extract");
                    while(parser.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {}
                }
                java.nio.file.Files.move(temporary.toPath(),new File(getFilesDir(),"region.osm").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                session.publish("regionReady","");
            } catch(Exception e) {event("diagnostic","Map import failed: "+e.getMessage());}
            finally {if(temporary!=null) temporary.delete();}
        },"osm-import").start();
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
    @JavascriptInterface public void exportGpx(){runOnUiThread(()->startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/gpx+xml")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"APRSERJa.gpx"),2));}
    @JavascriptInterface public void querySetup(){aprs.querySetup();}
    @JavascriptInterface public void discoverNetwork(){runOnUiThread(()->{
        android.net.nsd.NsdManager manager=getSystemService(android.net.nsd.NsdManager.class);
        android.net.nsd.NsdManager.DiscoveryListener listener=new android.net.nsd.NsdManager.DiscoveryListener(){
            public void onServiceFound(android.net.nsd.NsdServiceInfo info){manager.resolveService(info,new android.net.nsd.NsdManager.ResolveListener(){
                public void onResolveFailed(android.net.nsd.NsdServiceInfo i,int error){event("diagnostic","Discovery resolution failed: "+error);}
                public void onServiceResolved(android.net.nsd.NsdServiceInfo i){event("networkTnc",new org.json.JSONArray().put(i.getServiceName()).put(i.getHost().getHostAddress()).put(i.getPort()).toString());}
            });}
            public void onServiceLost(android.net.nsd.NsdServiceInfo i){}
            public void onDiscoveryStarted(String t){}
            public void onDiscoveryStopped(String t){}
            public void onStartDiscoveryFailed(String t,int error){event("diagnostic","Network discovery failed: "+error);}
            public void onStopDiscoveryFailed(String t,int error){}
        };
        manager.discoverServices(org.aprsfoundation.core.MobileRadio.kissService,android.net.nsd.NsdManager.PROTOCOL_DNS_SD,listener);
        new android.os.Handler(getMainLooper()).postDelayed(()->{try{manager.stopServiceDiscovery(listener);}catch(IllegalArgumentException ignored){}},5000);
    });}
    @JavascriptInterface public void disconnect(){aprs.disconnectInternet();}
    @JavascriptInterface public void disconnectTnc(){runOnUiThread(()->{
        aprs.disconnectRadio();
        TrackingService.command(this,TrackingService.RADIO_OFF);
    });}
    @JavascriptInterface public void connect(String host,int port,String call,String pass,double lat,double lon){aprs.connect(host,port,call,pass,lat,lon);}
    protected void onDestroy(){session.detach(screen);tts.shutdown();super.onDestroy();}
}
