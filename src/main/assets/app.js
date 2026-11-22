const el=id=>document.getElementById(id), map=el("map"), ctx=map.getContext("2d");
let nodes,roads,places,graph,origin,path=[],center,scale,stations=new Map(),drag;
const km=(a,b)=>111.2*Math.hypot(a.lat-b.lat,(a.lon-b.lon)*Math.cos((a.lat+b.lat)*Math.PI/360));
let driving,streetNames,fix,cues=[];function loadRegion(xml){
 const doc=new DOMParser().parseFromString(xml,"application/xml");
 const cars=new Map(),names=new Map();const points=new Map(),ways=[],entries=[],edges=new Map();
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
   if(permitted){const direction=t.oneway||(t.junction==="roundabout"||t.highway==="motorway"?"yes":"no");for(const [from,to] of [[a,b],[b,a]]){if(from===a&&direction==="-1"||from===b&&["yes","true","1"].includes(direction))continue;if(!cars.has(from))cars.set(from,[]);if(!cars.has(to))cars.set(to,[]);cars.get(from).push(to);names.set(from.id+"/"+to.id,t.name||"unnamed road");}}
if(walk){for(const [s,d] of [[a,b],[b,a]]){if(!edges.has(s))edges.set(s,[]);edges.get(s).push(d);}}}
 }
 if(!ways.length||!edges.size)throw Error("Region needs mapped streets and walking paths");
 driving=cars;streetNames=names;cues=[];nodes=points;roads=ways;places=entries;graph=edges;origin=null;path=[];
 const bounds=doc.querySelector("bounds");
 center=bounds?{lat:(+bounds.getAttribute("minlat")+ +bounds.getAttribute("maxlat"))/2,lon:(+bounds.getAttribute("minlon")+ +bounds.getAttribute("maxlon"))/2}:ways[0][0];
 scale=bounds?Math.min(120000,0.9*map.clientWidth/Math.max(.001,+bounds.getAttribute("maxlon")- +bounds.getAttribute("minlon"))):80000;
 el("directions").textContent="Choose a starting address.";search();draw();
}
function search(){let q=el("query").value.toLowerCase().trim();el("places").replaceChildren();for(let i=0;i<places.length&&el("places").length<80;i++)if(places[i].label.toLowerCase().includes(q))el("places").add(new Option(places[i].label,i));}
function selected(){return places[el("places").value]?.p;}
function snap(p,network=graph){let best=null,dist=.25;for(const n of network.keys()){const d=km(n,p);if(d<dist){best=n;dist=d;}}return best;}
function route(a,b,network=graph){let queue=[[0,a]],dist=new Map([[a,0]]),prev=new Map();while(queue.length){queue.sort((x,y)=>y[0]-x[0]);const [cost,n]=queue.pop();if(n===b){const out=[b];while(prev.has(out[0]))out.unshift(prev.get(out[0]));return [out,cost];}if(cost!==dist.get(n))continue;for(const next of network.get(n)||[]){const d=cost+km(n,next);if(d<(dist.get(next)??Infinity)){dist.set(next,d);prev.set(next,n);queue.push([d,next]);}}}return [[],0];}
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
 if(type==="gps"){fix=JSON.parse(data);if(el("follow").checked)center=fix;if(cues.length&&km(fix,cues[0].point)<.055)Radio.speak(cues.shift().text);draw();return;}
 if(type==="region"){try{loadRegion(data);localStorage.region=data;}catch(e){el("status").textContent=e.message;}return;}
 if(type==="stations"){
  const picker=el("stations"),previous=picker.value;
  stations=new Map(JSON.parse(data).map(point=>[point.name,point]));
  picker.replaceChildren(...Array.from(stations.keys(),name=>new Option(name)));
  if(stations.has(previous))picker.value=previous;
  draw();
 }
}
el("start").onclick=()=>{origin=selected()&&snap(selected());el("directions").textContent=origin?"Choose a destination.":"No nearby walking path";};
el("route").onclick=()=>{const dest=selected()&&snap(selected());if(!origin||!dest){el("directions").textContent="Choose endpoints near a walking path";return;}const result=route(origin,dest);path=result[0];el("directions").textContent=path.length?result[1].toFixed(2)+" km walking":"No connected walking route";draw();};
el("query").oninput=search;el("importer").onclick=()=>Radio.region();el("zin").onclick=()=>{scale*=1.5;draw();};el("zout").onclick=()=>{scale/=1.5;draw();};
map.onpointerdown=e=>{drag=[e.clientX,e.clientY];map.setPointerCapture(e.pointerId);};map.onpointermove=e=>{if(!drag)return;center={lon:center.lon-(e.clientX-drag[0])/scale,lat:center.lat+(e.clientY-drag[1])*Math.cos(center.lat*Math.PI/180)/scale};drag=[e.clientX,e.clientY];draw();};map.onpointerup=map.onpointercancel=()=>drag=null;
el("connect").onclick=()=>{const account=callsign("call");Radio.save("call",account);el("call").value=account;history();Radio.connect(el("host").value,+el("port").value,account,el("pass").value,center.lat,center.lon);};el("stop").onclick=()=>Radio.disconnect();
el("locate").onclick=()=>{const id=el("stations").value;if(!stations.has(id))return;center=stations.get(id);el("peer").value=id;draw();history();};
el("send").onclick=queueMessage;el("peer").oninput=history;el("call").oninput=history;el("call").value=Radio.read("call")||localStorage.call||"";Radio.importChats(localStorage.chats||"",localStorage.outbox||"");history();window.onresize=draw;loadRegion(localStorage.region||Radio.sample());


function bearing(a,b){return Math.atan2((b.lon-a.lon)*Math.cos(a.lat*Math.PI/180),b.lat-a.lat)*180/Math.PI;}
el("gps").onclick=()=>Radio.track();
el("drive").onclick=()=>{
 const start=fix||origin,end=selected();if(!start||!end){el("directions").textContent="Set a start or enable GPS, then choose a destination.";return;}
 const a=snap(start,driving),b=snap(end,driving);
 const [points,length]=a&&b?route(a,b,driving):[[],0];path=points;cues=[];draw();
 if(points.length<2){el("directions").textContent=points.length?"Already at destination":"No connected driving route in this region";return;}
 let last="";
 for(let i=0;i<points.length-1;i++){const p=points[i],q=points[i+1],name=streetNames.get(p.id+"/"+q.id);const angle=i?(bearing(p,q)-bearing(points[i-1],p)+540)%360-180:0;
 if(!i||name!==last||Math.abs(angle)>55)cues.push({point:p,text:(!i?"Start on ":angle>30?"Turn right onto ":angle< -30?"Turn left onto ":"Continue onto ")+name});last=name;}
 cues.push({point:b,text:"Arrive at destination"});el("directions").textContent=length.toFixed(2)+" km · "+cues.map(c=>c.text).join(". ");Radio.speak(cues.shift().text);
};

