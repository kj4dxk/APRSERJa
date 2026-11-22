package org.aprserja.mobile;
import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.*;
import java.net.*;
import java.io.*;
import java.util.HashSet;
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
            String phase=args.getString("phase","");
            if(phase.equals("queue")){
                c.getSharedPreferences("radio", Context.MODE_PRIVATE).edit()
                    .putString("chats","[{\"account\":\"N0ABC\",\"peer\":\"N0XYZ\",\"id\":\"91\",\"time\":"+System.currentTimeMillis()+",\"text\":\"N0XYZ · existing history\"}]")
                    .putString("outbox","[{\"account\":\"N0ABC\",\"peer\":\"N0XYZ\",\"id\":\"42\",\"body\":\"legacy outbox\",\"state\":\"queued\",\"attempts\":0}]")
                    .commit();
            }
            Main a=(Main)startActivitySync(new Intent(c,Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            web=(WebView)((android.view.ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
            await("typeof roads!=='undefined' && roads.length>0");
            if(phase.equals("queue")){
                js("Radio.save('call','N0ABC');el('call').value='N0ABC';el('peer').value='N0XYZ';el('message').value='restart check';queueMessage()");
                await("el('history').textContent.includes('existing history')&&el('history').textContent.includes('legacy outbox (queued)')&&el('history').textContent.includes('restart check (queued)')");
            }else if(phase.equals("recover")){
                js("el('peer').value='N0XYZ';history()");
                try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                    server.setSoTimeout(8000);
                    js("Radio.connect('127.0.0.1',"+server.getLocalPort()+",'N0ABC','1',42,-71)");
                    try(Socket s=server.accept()){
                        s.setSoTimeout(10000);
                        BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));PrintWriter w=new PrintWriter(s.getOutputStream(),true);
                        w.println("# local fixture");
                        String login=r.readLine();
                        if(login==null||!login.contains("vers APRSERJa "+BuildConfig.VERSION_NAME+" "))throw new IOException("Unexpected application version");
                        w.println("# logresp N0ABC verified, server local");
                        await("el('status').textContent==='Verified as N0ABC'");
                        HashSet<String> sent=new HashSet<>();
                        for(int i=0;i<2;i++){
                            String packet=r.readLine();
                            int idStart=packet.lastIndexOf('{')+1;
                            if(idStart==0)throw new IOException("Missing message ID");
                            if(packet.contains("legacy outbox{"))sent.add("legacy");
                            if(packet.contains("restart check{"))sent.add("new");
                            w.println("N0XYZ>APRS::N0ABC    :ack"+packet.substring(idStart));
                        }
                        if(sent.size()!=2)throw new IOException("Both persisted outbox messages must be delivered");
                        w.println("N0XYZ>APRS::N0ABC    :return message{92");w.println("N0XYZ>APRS:!4221.30N/07103.60W>");
                        if(!r.readLine().endsWith(":ack92"))throw new IOException("Missing reply ACK");
                        w.println("N0XYZ>APRS::N0ABC    :return message{92");
                        w.println("n0abc-1>APRS:=L9:K:YwNj# !G4L5EE LoRa");
                        if(!r.readLine().endsWith(":ack92"))throw new IOException("Missing retransmission ACK");
                        await("el('history').querySelectorAll('[data-status=ACKNOWLEDGED]').length===2&&stations.has('N0XYZ')&&stations.has('N0ABC-1')&&el('history').textContent.split('return message').length===2&&el('history').textContent.split('existing history').length===2");
                    }
                }
            }else if(phase.equals("receive-only")){
                js("el('call').value='N0ABC';el('peer').value='N0XYZ';el('message').value='receive only check';queueMessage()");
                await("el('history').textContent.includes('receive only check (queued)')");
                try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                    server.setSoTimeout(8000);
                    js("Radio.connect('127.0.0.1',"+server.getLocalPort()+",'N0ABC','-1',42,-71)");
                    try(Socket s=server.accept()){
                        s.setSoTimeout(3500);
                        BufferedReader r=new BufferedReader(new InputStreamReader(s.getInputStream()));
                        PrintWriter w=new PrintWriter(s.getOutputStream(),true);
                        w.println("# local fixture");
                        if(!r.readLine().contains("pass -1"))throw new IOException("Expected receive-only login");
                        w.println("# logresp N0ABC verified, server local");
                        w.println("N0XYZ>APRS::N0ABC    :receive only incoming{93");
                        w.println("N0XYZ>APRS:!4221.30N/07103.60W>");
                        await("el('status').textContent==='Receiving'&&stations.has('N0XYZ')&&el('history').textContent.includes('receive only incoming')");
                        try {
                            r.readLine();
                            throw new IOException("Receive-only connection unexpectedly wrote or disconnected");
                        } catch (SocketTimeoutException expected) {}
                    }
                }
            }else if(phase.equals("live")){
                String liveCall=args.getString("callsign","");
                if(!liveCall.matches("[A-Z0-9]{3,9}(-[0-9]{1,2})?"))throw new IOException("Supply your callsign for the receive-only live test");
                js("Radio.connect('rotate.aprs2.net',14580,'"+liveCall+"','-1',42.36,-71.06)");
                for(int i=0;i<60;i++){if(js("stations.size>0").equals("true"))break;Thread.sleep(1000);}
                await("stations.size>0");report.putString("stations",js("Array.from(stations.keys()).slice(0,4)"));
            }else if(phase.equals("navigation")){
                report.putString("route",js("(()=>{const a=[...driving.keys()].find(n=>driving.get(n).length);origin=a;places=[{label:'end',p:driving.get(a)[0]}];search();el('drive').click();return el('directions').textContent})()"));
                await("path.length>=2");js("Radio.track()");await("!!fix");report.putString("GPS",js("fix"));
            }
            report.putString("result","PASS "+phase);
        }catch(Throwable e){report.putString("failure",android.util.Log.getStackTraceString(e));try{report.putString("state",js("[el('history').textContent,el('chat-status').textContent,el('status').textContent,el('call').value]"));}catch(Exception ignored){}}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
}


