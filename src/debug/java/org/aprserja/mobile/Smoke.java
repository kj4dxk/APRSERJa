package org.aprserja.mobile;
import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.*;
import java.net.*;
import java.io.*;
import java.util.HashSet;
import java.util.concurrent.*;
import org.aprsfoundation.core.AprsTnc;
import org.aprsfoundation.core.RadioPacket;

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
    void tnc(String phase) throws Exception {
        boolean transmit = !phase.equals("tnc-rx");
        String protocol = phase.equals("tnc-agw") ? "AGW" : phase.equals("tnc-text") ? "TNC2" : "KISS";
        String call = phase.equals("tnc-agw") ? "N0AGW" : phase.equals("tnc-text") ? "N0TXT" : transmit ? "N0TNC" : "N0RX";
        AprsTnc fixture = new AprsTnc(AprsTnc.Protocol.valueOf(protocol), 0);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(8000);
            js("el('call').value='" + call + "';el('peer').value='N0XYZ';history();" +
                "el('tnc-kind').value='tcp';showTncFields();el('tnc-protocol').value='" + protocol +
                "';showTncProtocol();el('tnc-host').value='127.0.0.1';el('tnc-port').value=" + server.getLocalPort() +
                ";el('tnc-path').value='WIDE1-1';el('tnc-tx').checked=" + transmit + ";el('tnc-connect').click()");
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(8000);
                await("el('tnc-status').textContent.includes('" + (transmit ? "RF transmit enabled" : "receive only") + "')");
                byte[] command = fixture.openCommand();
                if (command != null && !java.util.Arrays.equals(command, readExactly(socket.getInputStream(), command.length)))
                    throw new IOException("Missing AGW raw frame registration");
                var output = socket.getOutputStream();
                byte[] position = fixture.encode(new RadioPacket("N0XYZ", "APRS", java.util.Collections.emptyList(),
                    "!4221.30N/07103.60W>"));
                output.write(position, 0, 7);
                output.flush();
                output.write(position, 7, position.length - 7);
                output.write(fixture.encode(AprsTnc.Companion.message("N0XYZ", call, "fixture inbound", "77",
                    java.util.Collections.emptyList())));
                output.flush();
                await("stations.has('N0XYZ') && el('history').textContent.includes('fixture inbound')");
                if (transmit) {
                    RadioPacket ack = readPacket(socket.getInputStream(), fixture);
                    if (!ack.getPayload().endsWith(":ack77")) throw new IOException("Missing RF acknowledgment: " + ack);
                    js("el('message').value='fixture outbound';el('send').click()");
                    RadioPacket message = readPacket(socket.getInputStream(), fixture);
                    if (!message.getSource().equals(call) || !message.getPath().equals(java.util.Collections.singletonList("WIDE1-1")) ||
                        !message.getPayload().contains("fixture outbound{")) throw new IOException("Invalid RF message: " + message);
                    String id = message.getPayload().substring(message.getPayload().lastIndexOf('{') + 1);
                    output.write(fixture.encode(AprsTnc.Companion.acknowledge("N0XYZ", call, id, java.util.Collections.emptyList())));
                    output.flush();
                    await("el('history').textContent.includes('fixture outbound (acknowledged)')");
                } else {
                    socket.setSoTimeout(1500);
                    try {
                        socket.getInputStream().read();
                        throw new IOException("Receive-only TNC wrote data or disconnected");
                    } catch (SocketTimeoutException expected) {}
                }
                js("el('tnc-stop').click()");
                await("el('tnc-status').textContent==='Disconnected'");
                if (socket.getInputStream().read() != -1) throw new IOException("TNC socket was not closed");
            }
        }
    }

    private byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        new DataInputStream(input).readFully(data);
        return data;
    }

    private RadioPacket readPacket(InputStream input, AprsTnc fixture) throws IOException {
        while (true) {
            int value = input.read();
            if (value < 0) throw new EOFException("TNC socket closed");
            var packets = fixture.receive(new byte[]{(byte)value});
            if (!packets.isEmpty()) {
                if (fixture.getProtocol() == AprsTnc.Protocol.TNC2 && input.read() != '\n')
                    throw new IOException("TNC2 output is missing its line feed");
                return packets.get(0);
            }
        }
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
            if(phase.equals("large-region")) {
                try(InputStream input=c.getAssets().open("boston.osm"); var output=new FileOutputStream(new File(c.getFilesDir(),"region.osm"))){
                    String raw=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    int end=raw.lastIndexOf("</osm>");
                    output.write(raw.substring(0,end).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.write("<node id=\"999999999999\" lat=\"42.355\" lon=\"-71.06\"><tag k=\"name\" v=\"Large region QA\"/></node><!--".getBytes());
                    byte[] padding=new byte[1024*1024];java.util.Arrays.fill(padding,(byte)' ');
                    for(int n=0;n<5;n++)output.write(padding);
                    output.write("--></osm>".getBytes());
                }
            }
            Main a=(Main)startActivitySync(new Intent(c,Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            web=(WebView)((android.view.ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);
            await("typeof roads!=='undefined' && roads.length>0");
            if(phase.equals("large-region")){
                await("places.some(place=>place.label==='Large region QA')");
                await("""
                    (() => {
                     const saved=[driving,driveWays,restrictions];
                     try {
                      const a={id:'a',lat:0,lon:0},b={id:'b',lat:1,lon:0},
                        c={id:'c',lat:2,lon:0},d={id:'d',lat:1,lon:1};
                      driving=new Map([[a,[b]],[b,[c,d]],[d,[c]],[c,[]]]);
                      driveWays=new Map([['a/b','one'],['b/c','two'],['b/d','three'],['d/c','four']]);
                      restrictions=new Map([['b',[{from:'one',to:'two',only:false,uTurn:false}]]]);
                      return route(a,c,driving)[0].map(point=>point.id).join('')==='abdc';
                     } finally { [driving,driveWays,restrictions]=saved; }
                    })()
                    """);
            }else if(phase.equals("mixed-radio")){
                var fixture=new AprsTnc(AprsTnc.Protocol.KISS,0);
                try(ServerSocket internet=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
                    ServerSocket radio=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                    internet.setSoTimeout(10000);radio.setSoTimeout(10000);
                    js("el('call').value='N0MIX';el('peer').value='N2RF';history();Radio.connect('127.0.0.1',"+internet.getLocalPort()+",'N0MIX','1',42,-71)");
                    try(Socket aprsis=internet.accept()){
                        aprsis.setSoTimeout(5000);
                        var in=new BufferedReader(new InputStreamReader(aprsis.getInputStream()));
                        var out=new PrintWriter(aprsis.getOutputStream(),true);
                        out.println("# local fixture");
                        if(!in.readLine().contains("user N0MIX"))throw new IOException("Missing APRS-IS login");
                        out.println("# logresp N0MIX verified, server local");
                        out.println("N1IS>APRS:!4221.30N/07103.60W>APRS-IS");
                        js("el('tnc-kind').value='tcp';el('tnc-protocol').value='KISS';showTncFields();el('tnc-host').value='127.0.0.1';el('tnc-port').value="+radio.getLocalPort()+";el('tnc-tx').checked=true;el('tnc-connect').click()");
                        try(Socket rf=radio.accept()){
                            rf.setSoTimeout(8000);
                            await("el('status').textContent==='Verified as N0MIX' && el('tnc-status').textContent.includes('transmit enabled')");
                            rf.getOutputStream().write(fixture.encode(new RadioPacket("N2RF","APRS",java.util.Collections.emptyList(),"!4221.30N/07103.60W>RF")));
                            await("stations.has('N1IS') && stations.has('N2RF')");
                            try(var ignored=getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME")) {
                                Thread.sleep(750);
                            }
                            rf.getOutputStream().write(fixture.encode(AprsTnc.Companion.message("N2RF","N0MIX","background fixture","73",java.util.Collections.emptyList())));
                            if(!readPacket(rf.getInputStream(),fixture).getPayload().endsWith(":ack73"))
                                throw new IOException("TNC did not acknowledge in the background");
                            try(var launch=getUiAutomation().executeShellCommand("am start -W -n "+c.getPackageName()+"/org.aprserja.mobile.Main");
                                var response=new android.os.ParcelFileDescriptor.AutoCloseInputStream(launch)) {
                                response.readAllBytes();
                            }
                            await("el('status').textContent==='Verified as N0MIX' && el('tnc-status').textContent.includes('transmit enabled')");
                            js("el('tnc-stop').click()");
                            await("el('tnc-status').textContent==='Disconnected' && el('status').textContent==='Verified as N0MIX'");
                            if(rf.getInputStream().read()!=-1) throw new IOException("RF disconnect did not close its socket");
                        }
                    }
                }
            }else if(phase.equals("mobile-radio")){
                try(ServerSocket server=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))){
                    server.setSoTimeout(12000);
                    js("el('call').value='N0TST';el('tnc-kind').value='tcp';el('tnc-protocol').value='KISS';showTncFields();el('tnc-host').value='127.0.0.1';el('tnc-port').value="+server.getLocalPort()+";el('tnc-tx').checked=true;el('tnc-connect').click()");
                    try(Socket first=server.accept()){await("el('tnc-status').textContent.includes('transmit enabled')");}
                    try(Socket second=server.accept()){
                        await("el('tnc-status').textContent.includes('transmit enabled')");
                        js("el('beacon').value='genius';el('beacon').onchange();Radio.track()");
                        second.setSoTimeout(20000);
                        RadioPacket beacon=readPacket(second.getInputStream(),new AprsTnc(AprsTnc.Protocol.KISS,0));
                        if(!beacon.getPayload().startsWith("@") || !beacon.getSource().equals("N0TST"))throw new IOException("Missing GPS RF beacon");
                        await("!!fix && el('gpx').checked && el('diagnostic').textContent.includes('Beacon')");
                        java.io.File[] tracks=c.getExternalFilesDir(null).listFiles((dir,name)->name.endsWith(".gpx"));
                        if(tracks==null || tracks.length==0)throw new IOException("Missing GPX");
                        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(tracks[tracks.length-1]);
                        js("el('tnc-stop').click()");
                    }
                }
            }else if(phase.startsWith("tnc-")){
                tnc(phase);
            }else if(phase.equals("queue")){
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
        }catch(Throwable e){report.putString("failure",android.util.Log.getStackTraceString(e));try{report.putString("state",js("[el('history').textContent,el('chat-status').textContent,el('status').textContent,el('tnc-status').textContent,el('call').value]"));}catch(Exception ignored){}}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
}
