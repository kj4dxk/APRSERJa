const el=id=>document.getElementById(id), map=el("map"), ctx=map.getContext("2d");
let nodes,roads,places,graph,origin,path=[],center,scale,stations=new Map(),drag;
const km=(a,b)=>111.2*Math.hypot(a.lat-b.lat,(a.lon-b.lon)*Math.cos((a.lat+b.lat)*Math.PI/360));
let driving,streetNames,driveWays,restrictions,fix,cues=[],destination=null,deviations=0,lastReroute=0;function loadRegion(xml){
 const doc=new DOMParser().parseFromString(xml,"application/xml");
 const cars=new Map(),names=new Map(),wayIds=new Map(),turns=new Map();const points=new Map(),ways=[],entries=[],edges=new Map();
 const tags=e=>Object.fromEntries([...e.children].filter(x=>x.tagName==="tag").map(x=>[x.getAttribute("k"),x.getAttribute("v")]));
 function address(t,p){const label=[t["addr:housenumber"],t["addr:street"]].filter(Boolean).join(" ")||t.name;if(label)entries.push({label,p});}
 for(const n of doc.querySelectorAll("node")){const p={id:n.id||n.getAttribute("id"),lat:+n.getAttribute("lat"),lon:+n.getAttribute("lon")};points.set(p.id,p);address(tags(n),p);}
 for(const w of doc.querySelectorAll("way")){
  const t=tags(w),refs=[...w.querySelectorAll("nd")].map(n=>points.get(n.getAttribute("ref")));
  const first=refs.find(Boolean);if(!first)continue;address(t,first);
  if(!t.highway)continue;
  const walk=!["motorway","motorway_link","trunk","trunk_link","construction","proposed","raceway"].includes(t.highway)&&!["no","private"].includes(t.foot||t.access);
  for(let i=1;i<refs.length;i++){const a=refs[i-1],b=refs[i];if(!a||!b)continue;ways.push([a,b]);
   const permitted=/^(motorway|trunk|primary|secondary|tertiary)(_link)?$|^(residential|living_street|service|unclassified)$/.test(t.highway)&&!["no","private"].includes(t.motorcar||t.motor_vehicle||t.vehicle||t.access);
   if(permitted){const direction=t.oneway||(t.junction==="roundabout"||t.highway==="motorway"?"yes":"no");for(const [from,to] of [[a,b],[b,a]]){if(from===a&&direction==="-1"||from===b&&["yes","true","1"].includes(direction))continue;if(!cars.has(from))cars.set(from,[]);if(!cars.has(to))cars.set(to,[]);cars.get(from).push(to);names.set(from.id+"/"+to.id,t.name||"unnamed road");wayIds.set(from.id+"/"+to.id,w.id||w.getAttribute("id"));}}
if(walk){for(const [s,d] of [[a,b],[b,a]]){if(!edges.has(s))edges.set(s,[]);edges.get(s).push(d);}}}
 }
 if(!ways.length||!edges.size)throw Error("Region needs mapped streets and walking paths");
 for(const relation of doc.querySelectorAll("relation")){
  const t=tags(relation);
  const rule=t["restriction:motorcar"]||t["restriction:motor_vehicle"]||t.restriction;
  if(t.type!=="restriction"||!rule||!/^no_|^only_/.test(rule)||/(^|;)(motorcar|motor_vehicle|vehicle)(;|$)/.test(t.except||""))continue;
  const members=[...relation.children].filter(e=>e.tagName==="member");
  const via=members.filter(e=>e.getAttribute("role")==="via");
  if(via.length!==1||via[0].getAttribute("type")!=="node")continue;
  const from=members.find(e=>e.getAttribute("role")==="from")?.getAttribute("ref");
  const to=members.find(e=>e.getAttribute("role")==="to")?.getAttribute("ref");
  if(!from||!to)continue;
  const node=via[0].getAttribute("ref");
  if(!turns.has(node))turns.set(node,[]);
  turns.get(node).push({from,to,only:rule.startsWith("only_"),uTurn:rule.endsWith("_u_turn")});
 }
 driving=cars;streetNames=names;driveWays=wayIds;restrictions=turns;cues=[];destination=null;nodes=points;roads=ways;places=entries;graph=edges;origin=null;path=[];
 const bounds=doc.querySelector("bounds");
 center=bounds?{lat:(+bounds.getAttribute("minlat")+ +bounds.getAttribute("maxlat"))/2,lon:(+bounds.getAttribute("minlon")+ +bounds.getAttribute("maxlon"))/2}:ways[0][0];
 scale=bounds?Math.min(120000,0.9*map.clientWidth/Math.max(.001,+bounds.getAttribute("maxlon")- +bounds.getAttribute("minlon"))):80000;
 el("directions").textContent="Choose a starting address.";search();draw();
}
function search(){let q=el("query").value.toLowerCase().trim();el("places").replaceChildren();for(let i=0;i<places.length&&el("places").length<80;i++)if(places[i].label.toLowerCase().includes(q))el("places").add(new Option(places[i].label,i));}
function selected(){return places[el("places").value]?.p;}
function snap(p,network=graph){let best=null,dist=.25;for(const n of network.keys()){const d=km(n,p);if(d<dist){best=n;dist=d;}}return best;}
function route(a,b,network=graph){
 const byTurn=network===driving, key=(node,previous)=>byTurn&&restrictions.has(node.id)?node.id+"/"+(previous?.id||""):node.id;
 const first={node:a,previous:null,cost:0,id:key(a,null),parent:null};
 const queue=[first],distance=new Map([[first.id,0]]);
 function push(entry){
  let i=queue.length;queue.push(entry);
  while(i>0){const parent=(i-1)>>1;if(queue[parent].cost<=entry.cost)break;queue[i]=queue[parent];i=parent;}
  queue[i]=entry;
 }
 function pop(){
  const root=queue[0],end=queue.pop();
  if(!queue.length)return root;
  let i=0;
  while(i*2+1<queue.length){
   let child=i*2+1;
   if(child+1<queue.length&&queue[child+1].cost<queue[child].cost)child++;
   if(end.cost<=queue[child].cost)break;
   queue[i]=queue[child];i=child;
  }
  queue[i]=end;return root;
 }
 while(queue.length){
  const entry=pop(),n=entry.node;
  if(entry.cost!==distance.get(entry.id))continue;
  if(n===b){const points=[];for(let step=entry;step;step=step.parent)points.push(step.node);return [points.reverse(),entry.cost];}
  const rules=byTurn?restrictions.get(n.id):null;
  const from=rules&&entry.previous?driveWays.get(entry.previous.id+"/"+n.id):null;
  for(const next of network.get(n)||[]){
   if(from){
    const to=driveWays.get(n.id+"/"+next.id);
    if(rules.some(rule=>rule.from===from&&(rule.only?(to!==rule.to || (rule.uTurn&&next!==entry.previous)):
      (to===rule.to&&(!rule.uTurn||next===entry.previous)))))continue;
   }
   const cost=entry.cost+km(n,next),id=key(next,n);
   if(cost<(distance.get(id)??Infinity)){
    distance.set(id,cost);push({node:next,previous:n,cost,id,parent:entry});
   }
  }
 }
 return [[],0];
}
function xy(p){return [map.width/2+(p.lon-center.lon)*scale,map.height/2-(p.lat-center.lat)*scale/Math.cos(center.lat*Math.PI/180)];}
function draw(){if(!roads)return;map.width=map.clientWidth;map.height=map.clientHeight;function line(a,b){ctx.moveTo(...xy(a));ctx.lineTo(...xy(b));}ctx.beginPath();for(const [a,b] of roads)line(a,b);ctx.strokeStyle="#adb8a8";ctx.lineWidth=3;ctx.stroke();ctx.beginPath();for(let i=1;i<path.length;i++)line(path[i-1],path[i]);ctx.strokeStyle="#176ca5";ctx.lineWidth=5;ctx.stroke();if(fix){const [x,y]=xy(fix);ctx.fillStyle="#005db8";ctx.beginPath();ctx.arc(x,y,6,0,Math.PI*2);ctx.fill();ctx.fillText("GPS",x+8,y);}for(const [name,p] of stations){const [x,y]=xy(p);ctx.fillStyle="#ab402a";ctx.fillRect(x-3,y-3,7,7);ctx.fillText(name,x+6,y);}}
function callsign(id){return el(id).value.trim().toUpperCase();}
let selectedChat=null;
function history(){
 const account=callsign("call"),peer=callsign("peer");
 if(selectedChat?.account===account&&selectedChat.peer===peer)return;
 selectedChat={account,peer};
 el("history").replaceChildren();
 Radio.selectChat(account,peer);
}
function queueMessage(){
 history();
 Radio.queueChat(callsign("call"),callsign("peer"),el("message").value);
}
function receive(type,data){
 if(type==="setupReading"){el("setupReading").textContent+=" · "+data;return;}
 if(type==="networkTnc"){const [name,host,port]=JSON.parse(data),b=document.createElement("button");b.textContent=name+" · "+host+" · "+port;b.onclick=()=>{el("tnc-kind").value="tcp";el("tnc-protocol").value="KISS";showTncFields();el("tnc-host").value=host;el("tnc-port").value=port;};el("found").append(b);return;}
 if(type==="tncStatus"){el("tnc-status").textContent=data;return;}
 if(type==="tracking"){el("gps").textContent=data==="on"?"Stop GPS":"Start GPS";return;}
 if(type==="diagnostic"){el("diagnostic").textContent=data;return;}
 if(type==="tncDevices"){
  const {kind,devices}=JSON.parse(data);
  if(kind!==el("tnc-kind").value)return;
  const selected=el("tnc-device").value || Radio.read("tnc-device-"+kind);
  el("tnc-device").replaceChildren(...devices.map(({name,id})=>new Option(name,id)));
  if(devices.some(device=>device.id===selected))el("tnc-device").value=selected;
  el("tnc-devices-status").textContent=devices.length?"":kind==="usb"?"Attach a supported USB serial TNC.":kind==="ble"?"No nearby KISS BLE TNC found.":"Pair your Bluetooth Classic TNC in Android Settings.";
  return;
 }
 if(type==="tncDeviceError"){const {kind,message}=JSON.parse(data);if(kind===el("tnc-kind").value)el("tnc-devices-status").textContent=message;return;}
 if(type==="status"||type==="chatError"){el(type==="status"?"status":"chat-status").textContent=data;return;}
 if(type==="queued"){
  const entry=JSON.parse(data);
  if(callsign("call")===entry.account&&callsign("peer")===entry.peer&&el("message").value===entry.body){
   el("message").value="";
   el("chat-status").textContent="";
  }
  return;
 }
 if(type==="history"){
  const {account,peer,rows}=JSON.parse(data);
  if(account!==callsign("call")||peer!==callsign("peer"))return;
  const entries=rows.map(row=>{
   const p=document.createElement("p");
   p.dataset.status=row.status;
   p.textContent=row.outgoing?"You · "+row.body+" ("+row.status.toLowerCase().replaceAll("_"," ")+")":peer+" · "+row.body;
   return p;
  });
  el("history").replaceChildren(...entries);
  return;
 }
 if(type==="gps"){fix=JSON.parse(data);if(el("follow").checked)center=fix;checkRouteProgress();if(cues.length&&km(fix,cues[0].point)<.055)Radio.speak(cues.shift().text);draw();return;}
 if(type==="regionReady"){loadImportedRegion();return;}
 if(type==="stations"){
  const picker=el("stations"),previous=picker.value;
  stations=new Map(JSON.parse(data).map(point=>[point.name,point]));
  picker.replaceChildren(...Array.from(stations.keys(),name=>new Option(name)));
  if(stations.has(previous))picker.value=previous;
  draw();
 }
}
el("start").onclick=()=>{origin=selected()&&snap(selected());el("directions").textContent=origin?"Choose a destination.":"No nearby walking path";};
el("route").onclick=()=>{destination=null;cues=[];const dest=selected()&&snap(selected());if(!origin||!dest){el("directions").textContent="Choose endpoints near a walking path";return;}const result=route(origin,dest);path=result[0];el("directions").textContent=path.length?result[1].toFixed(2)+" km walking":"No connected walking route";draw();};
el("query").oninput=search;el("importer").onclick=()=>Radio.region();el("zin").onclick=()=>{scale*=1.5;draw();};el("zout").onclick=()=>{scale/=1.5;draw();};
map.onpointerdown=e=>{drag=[e.clientX,e.clientY];map.setPointerCapture(e.pointerId);};map.onpointermove=e=>{if(!drag)return;center={lon:center.lon-(e.clientX-drag[0])/scale,lat:center.lat+(e.clientY-drag[1])*Math.cos(center.lat*Math.PI/180)/scale};drag=[e.clientX,e.clientY];draw();};map.onpointerup=map.onpointercancel=()=>drag=null;
el("connect").onclick=()=>{const account=callsign("call");Radio.save("call",account);el("call").value=account;history();Radio.connect(el("host").value,+el("port").value,account,el("pass").value,center.lat,center.lon);};el("stop").onclick=()=>Radio.disconnect();
el("locate").onclick=()=>{const id=el("stations").value;if(!stations.has(id))return;center=stations.get(id);el("peer").value=id;draw();history();};
el("send").onclick=queueMessage;el("peer").oninput=history;el("call").oninput=history;el("call").value=Radio.read("call")||localStorage.call||"";Radio.importChats(localStorage.chats||"",localStorage.outbox||"");history();window.onresize=draw;loadRegion(localStorage.region||Radio.sample());
let openingRegion=false;
async function loadImportedRegion(){
 if(openingRegion)return;
 openingRegion=true;
 try{
  const response=await fetch("https://maps.aprserja.invalid/region.osm",{cache:"no-store"});
  if(!response.ok)throw Error("Unable to open imported map");
  loadRegion(await response.text());
  localStorage.removeItem("region");
 }catch(error){el("directions").textContent=error.message;}
 finally{openingRegion=false;}
}
if(Radio.hasImportedRegion())loadImportedRegion();


function bearing(a,b){return Math.atan2((b.lon-a.lon)*Math.cos(a.lat*Math.PI/180),b.lat-a.lat)*180/Math.PI;}
el("gps").onclick=()=>el("gps").textContent==="Stop GPS"?Radio.stopTracking():Radio.track();
el("drive").onclick=()=>{
 const start=fix||origin,end=selected();if(!start||!end){el("directions").textContent="Set a start or enable GPS, then choose a destination.";return;}
 destination=end;deviations=0;lastReroute=Date.now();driveFrom(start,end);
};
function distanceToSegment(point,a,b){
 const f=Math.cos(point.lat*Math.PI/180);
 const x=(a.lon-point.lon)*f,y=a.lat-point.lat;
 const dx=(b.lon-a.lon)*f,dy=b.lat-a.lat;
 const length=dx*dx+dy*dy;
 const t=length?Math.max(0,Math.min(1,-(x*dx+y*dy)/length)):0;
 return 111.2*Math.hypot(x+t*dx,y+t*dy);
}
function checkRouteProgress(){
 if(!destination||!fix||path.length<2)return;
 if(path.slice(1).some((point,index)=>distanceToSegment(fix,path[index],point)<.080)){deviations=0;return;}
 if(++deviations<3||Date.now()-lastReroute<15000)return;
 deviations=0;lastReroute=Date.now();driveFrom(fix,destination);
}
function driveFrom(start,end){
 const a=snap(start,driving),b=snap(end,driving);
 const [points,length]=a&&b?route(a,b,driving):[[],0];path=points;cues=[];draw();
 if(points.length<2){el("directions").textContent=points.length?"Already at destination":"No connected driving route in this region";return;}
 let last="";
 for(let i=0;i<points.length-1;i++){const p=points[i],q=points[i+1],name=streetNames.get(p.id+"/"+q.id);const angle=i?(bearing(p,q)-bearing(points[i-1],p)+540)%360-180:0;
 if(!i||name!==last||Math.abs(angle)>55)cues.push({point:p,text:(!i?"Start on ":angle>30?"Turn right onto ":angle< -30?"Turn left onto ":"Continue onto ")+name});last=name;}
 cues.push({point:b,text:"Arrive at destination"});el("directions").textContent=length.toFixed(2)+" km · "+cues.map(c=>c.text).join(". ");Radio.speak(cues.shift().text);
}



const tncSettings=["tnc-kind","tnc-protocol","tnc-baud","tnc-host","tnc-port","tnc-channel","tnc-path"];
for(const id of tncSettings){
 const saved=Radio.read(id);
 if(saved)el(id).value=saved;
}
function showTncProtocol(){
 const kind=el("tnc-kind").value, protocol=el("tnc-protocol");
 for(const option of protocol.options){
  option.disabled=(option.value==="AGW"&&kind!=="tcp")||(kind==="ble"&&option.value!=="KISS");
 }
 if(protocol.selectedOptions[0]?.disabled)protocol.value="KISS";
 el("tnc-channel-label").hidden=protocol.value==="TNC2";
 el("tnc-channel").max=protocol.value==="AGW"?255:15;
}
function showTncFields(){
 showTncProtocol();
 const kind=el("tnc-kind").value;
 el("tnc-device-fields").hidden=kind==="tcp";
 el("tnc-usb-fields").hidden=kind!=="usb";
 el("tnc-tcp-fields").hidden=kind!=="tcp";
 el("tnc-device").replaceChildren();
 el("tnc-devices-status").textContent=kind==="tcp"?"":"Refresh to list TNC devices.";
 if(kind==="usb")Radio.listTncDevices(kind);
}
el("tnc-kind").onchange=showTncFields;
el("tnc-protocol").onchange=()=>{
 const port=el("tnc-port"), protocol=el("tnc-protocol").value;
 if(protocol==="AGW"&&port.value==="8001")port.value="8000";
 if(protocol==="KISS"&&port.value==="8000")port.value="8001";
 showTncProtocol();
};
el("tnc-refresh").onclick=()=>{
 const kind=el("tnc-kind").value;
 el("tnc-devices-status").textContent=kind==="ble"?"Scanning for nearby KISS TNCs…":"Refreshing devices…";
 Radio.listTncDevices(kind);
};
el("tnc-stop").onclick=()=>Radio.disconnectTnc();
el("tnc-connect").onclick=()=>{
 const call=callsign("call"),kind=el("tnc-kind").value,device=el("tnc-device").value;
 const channel=el("tnc-channel"),port=el("tnc-port"),protocol=el("tnc-protocol").value;
 if((protocol!=="TNC2"&&(!channel.value||!channel.reportValidity()))||(kind==="tcp"&&(!port.value||!port.reportValidity())))return;
 if(kind!=="tcp"&&!device){el("tnc-devices-status").textContent="Refresh and select a TNC device.";return;}
 Radio.save("call",call);
 for(const id of tncSettings)Radio.save(id,el(id).value);
 if(kind==="bluetooth")Radio.save("tnc-device-"+kind,device);
 el("call").value=call;
 history();
 Radio.connectTnc(kind,protocol,device,el("tnc-host").value,+port.value,+el("tnc-baud").value,protocol==="TNC2"?0:+channel.value,call,el("tnc-path").value,el("tnc-tx").checked);
};
showTncFields();

el("discover").onclick=()=>{el("found").replaceChildren();Radio.discoverNetwork();};
el("beacon").value=Radio.read("beacon")||"off";
el("beacon").onchange=()=>Radio.save("beacon",el("beacon").value);
el("gpx").checked=Radio.read("gpx")!=="off";
el("gpx").onchange=()=>Radio.save("gpx",el("gpx").checked?"on":"off");

el("setupQuery").onclick=()=>{el("setupReading").textContent="Mobilinkd query";Radio.querySetup();};

el("exportGpx").onclick=()=>Radio.exportGpx();
