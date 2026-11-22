package org.aprsisosm.mobile;
import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public class Smoke extends Instrumentation {
    Bundle args; WebView web;
    public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
    String js(String script)throws Exception{
        CompletableFuture<String> result=new CompletableFuture<>();
        runOnMainSync(()->web.evaluateJavascript(script,result::complete));
        return result.get(8,TimeUnit.SECONDS);
    }
    void await(String condition)throws Exception{
        for(int i=0;i<60;i++){if(js(condition).equals("true"))return;Thread.sleep(250);}
        throw new IOException("Failed "+condition+" status="+js("document.body.innerText.slice(0,190)"));
    }
    public void onStart(){Bundle report=new Bundle();
        try{
            Context c=getTargetContext();
            Main a=(Main)startActivitySync(new Intent(c,Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            web=(WebView)((android.view.ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
            await("typeof roads!=='undefined' && roads.length>0");
            String phase=args.getString("phase","");
            if(phase.equals("queue")){
                js("radioStore.call='N0ABC';el('call').value='N0ABC';el('peer').value='N0XYZ';el('message').value='restart check';queueMessage()");
                await("queueItems().some(m=>m.body==='restart check'&&m.state==='queued')");
            }else if(phase.equals("recover")){
                try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                    server.setSoTimeout(8000);
                    js("Radio.connect('127.0.0.1',"+server.getLocalPort()+",'N0ABC','1',42,-71)");
                    try(Socket s=server.accept()){
                        s.setSoTimeout(10000);
                        BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));PrintWriter w=new PrintWriter(s.getOutputStream(),true);
                        r.readLine();w.println("# logresp N0ABC verified, server local");
                        await("onlineAccount===\"N0ABC\"");String packet=r.readLine();if(!packet.contains("restart check{"))throw new IOException(packet);
                        w.println("N0XYZ>APRS::N0ABC    :ack"+packet.substring(packet.lastIndexOf('{')+1));
                        w.println("N0XYZ>APRS::N0ABC    :return message{92");w.println("N0XYZ>APRS:!4221.30N/07103.60W>");
                        if(!r.readLine().endsWith(":ack92"))throw new IOException("Missing reply ACK");
                        await("queueItems().some(m=>m.state==='acknowledged')&&stations.has('N0XYZ')&&JSON.parse(radioStore.chats).some(m=>m.id==='92')");
                    }
                }
            }else if(phase.equals("live")){
                js("Radio.connect('rotate.aprs2.net',14580,'KJ4DXK','-1',42.36,-71.06)");
                for(int i=0;i<60;i++){if(js("stations.size>0").equals("true"))break;Thread.sleep(1000);}
                await("stations.size>0");report.putString("stations",js("Array.from(stations.keys()).slice(0,4)"));
            }else if(phase.equals("navigation")){
                report.putString("route",js("(()=>{const a=[...driving.keys()].find(n=>driving.get(n).length);origin=a;places=[{label:'end',p:driving.get(a)[0]}];search();el('drive').click();return el('directions').textContent})()"));
                await("path.length>=2");js("Radio.track()");await("!!fix");report.putString("GPS",js("fix"));
            }
            report.putString("result","PASS "+phase);
        }catch(Throwable e){report.putString("failure",android.util.Log.getStackTraceString(e));try{report.putString("state",js("[onlineAccount,queueItems(),el('status').textContent,el('call').value]"));}catch(Exception ignored){}}
        finish(Activity.RESULT_OK,report);
    }
}


