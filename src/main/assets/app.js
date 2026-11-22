const el=id=>document.getElementById(id), map=el("map"), ctx=map.getContext("2d");
let nodes,roads,places,graph,origin,path=[],center,scale,stations=new Map(),drag;
const km=(a,b)=>111.2*Math.hypot(a.lat-b.lat,(a.lon-b.lon)*Math.cos((a.lat+b.lat)*Math.PI/360));
function loadRegion(xml){
 const doc=new DOMParser().parseFromString(xml,"application/xml");
 const points=new Map(),ways=[],entries=[],edges=new Map();
 const tags=e=>Object.fromEntries([...e.children].filter(x=>x.tagName==="tag").map(x=>[x.getAttribute("k"),x.getAttribute("v")]));
 function address(t,p){const label=[t["addr:housenumber"],t["addr:street"]].filter(Boolean).join(" ")||t.name;if(label)entries.push({label,p});}
 for(const n of doc.querySelectorAll("node")){const p={id:n.id||n.getAttribute("id"),lat:+n.getAttribute("lat"),lon:+n.getAttribute("lon")};points.set(p.id,p);address(tags(n),p);}
 for(const w of doc.querySelectorAll("way")){
  const t=tags(w),refs=[...w.querySelectorAll("nd")].map(n=>points.get(n.getAttribute("ref")));
  const first=refs.find(Boolean);if(!first)continue;address(t,first);
  if(!t.highway)continue;
  const walk=!["motorway","motorway_link","trunk","trunk_link","construction","proposed","raceway"].includes(t.highway)&&!["no","private"].includes(t.foot||t.access);
  for(let i=1;i<refs.length;i++){const a=refs[i-1],b=refs[i];if(!a||!b)continue;ways.push([a,b]);if(walk){for(const [s,d] of [[a,b],[b,a]]){if(!edges.has(s))edges.set(s,[]);edges.get(s).push(d);}}}
 }
 if(!ways.length||!edges.size)throw Error("Region needs mapped streets and walking paths");
 nodes=points;roads=ways;places=entries;graph=edges;origin=null;path=[];
 const bounds=doc.querySelector("bounds");
 center=bounds?{lat:(+bounds.getAttribute("minlat")+ +bounds.getAttribute("maxlat"))/2,lon:(+bounds.getAttribute("minlon")+ +bounds.getAttribute("maxlon"))/2}:ways[0][0];
 scale=bounds?Math.min(120000,0.9*map.clientWidth/Math.max(.001,+bounds.getAttribute("maxlon")- +bounds.getAttribute("minlon"))):80000;
 el("directions").textContent="Choose a starting address.";search();draw();
}
function search(){let q=el("query").value.toLowerCase().trim();el("places").replaceChildren();for(let i=0;i<places.length&&el("places").length<80;i++)if(places[i].label.toLowerCase().includes(q))el("places").add(new Option(places[i].label,i));}
function selected(){return places[el("places").value]?.p;}
function snap(p){let best=null,dist=.25;for(const n of graph.keys()){const d=km(n,p);if(d<dist){best=n;dist=d;}}return best;}
function route(a,b){let queue=[[0,a]],dist=new Map([[a,0]]),prev=new Map();while(queue.length){queue.sort((x,y)=>y[0]-x[0]);const [cost,n]=queue.pop();if(n===b){const out=[b];while(prev.has(out[0]))out.unshift(prev.get(out[0]));return [out,cost];}if(cost!==dist.get(n))continue;for(const next of graph.get(n)||[]){const d=cost+km(n,next);if(d<(dist.get(next)??Infinity)){dist.set(next,d);prev.set(next,n);queue.push([d,next]);}}}return [[],0];}
function xy(p){return [map.width/2+(p.lon-center.lon)*scale,map.height/2-(p.lat-center.lat)*scale/Math.cos(center.lat*Math.PI/180)];}
function draw(){if(!roads)return;map.width=map.clientWidth;map.height=map.clientHeight;function line(a,b){ctx.moveTo(...xy(a));ctx.lineTo(...xy(b));}ctx.beginPath();for(const [a,b] of roads)line(a,b);ctx.strokeStyle="#adb8a8";ctx.lineWidth=3;ctx.stroke();ctx.beginPath();for(let i=1;i<path.length;i++)line(path[i-1],path[i]);ctx.strokeStyle="#176ca5";ctx.lineWidth=5;ctx.stroke();for(const [name,p] of stations){const [x,y]=xy(p);ctx.fillStyle="#ab402a";ctx.fillRect(x-3,y-3,7,7);ctx.fillText(name,x+6,y);}}
function history(){const peer=el("peer").value.trim().toUpperCase();el("history").replaceChildren();for(const m of JSON.parse(localStorage.chats||"[]").filter(m=>m.peer===peer&&m.account===el("call").value.toUpperCase()).slice(-35)){const p=document.createElement("p");p.textContent=m.text;el("history").append(p);}for(const m of queueItems().filter(x=>x.account===el("call").value.toUpperCase()&&x.peer===peer).slice(-35)){const p=document.createElement("p");p.textContent="You · "+m.body+" ("+m.state+")";el("history").append(p);}}
let onlineAccount="";
function queueItems(){return JSON.parse(localStorage.outbox||"[]");}
function pump(){
 const items=queueItems();
 for(const m of items){if(m.account!==onlineAccount||m.next>Date.now()||m.state!=="queued")continue;
 if(m.attempts===5){m.state="unconfirmed";continue;}
 Radio.send(m.peer,m.body+"{"+m.id);m.attempts++;m.next=Date.now()+30000*m.attempts;}
 localStorage.outbox=JSON.stringify(items);history();
}
function queueMessage(){
 const peer=el("peer").value.trim().toUpperCase(),body=el("message").value,account=el("call").value.trim().toUpperCase();
 if(!/^[A-Z0-9]{1,6}(-[0-9]{1,2})?$/.test(peer)||!account||!/^[ -z|~]{1,61}$/.test(body)){el("status").textContent="Enter callsigns and a message of 1–61 ASCII characters";return;}
 const seq=((Number(localStorage.sequence)||0)+1)%100000;localStorage.sequence=seq;
 const items=queueItems();items.push({account,peer,body,id:String(seq),attempts:0,next:0,state:"queued"});localStorage.outbox=JSON.stringify(items);el("message").value="";pump();
}
setInterval(pump,10000);
function receive(type,data){
 if(type==="status"){el("status").textContent=data;onlineAccount=/^# logresp (\S+) verified,/.exec(data)?.[1]||"";if(onlineAccount)pump();return;}
 if(type==="region"){try{loadRegion(data);localStorage.region=data;}catch(e){el("status").textContent=e.message;}return;}
 const packet=/^([^>]+)>[^:]+:(.*)$/.exec(data);if(!packet)return;
 const [,sender,body]=packet,account=el("call").value.toUpperCase();
 const msg=/^:(.{9}):(.*)$/.exec(body);
 if(type==="packet"&&msg&&msg[1].trim()===account){
  const control=/^(ack|rej)([A-Za-z0-9]{1,5})$/.exec(msg[2]);
  if(control){const items=queueItems();for(const m of items)if(m.account===account&&m.peer===sender&&m.id===control[2])m.state=control[1]==="ack"?"acknowledged":"rejected";localStorage.outbox=JSON.stringify(items);}
  else{const numbered=/^(.*)\{([A-Za-z0-9]{1,5})$/.exec(msg[2]),chats=JSON.parse(localStorage.chats||"[]");
   if(!numbered||!chats.some(m=>m.account===account&&m.peer===sender&&m.id===numbered[2]&&Date.now()-m.time<86400000)){
    chats.push({peer:sender,account,id:numbered?.[2],time:Date.now(),text:sender+" · "+(numbered?.[1]||msg[2])});localStorage.chats=JSON.stringify(chats.slice(-2000));}
   if(numbered&&onlineAccount===account)Radio.send(sender,"ack"+numbered[2]);}
  history();} const pos=/^(?:[!=]|[@/].{7})(\d{2})(\d{2}\.\d{2})([NS]).(\d{3})(\d{2}\.\d{2})([EW])/.exec(body);
 if(pos){const lat=(+pos[1]+pos[2]/60)*(pos[3]==="S"?-1:1),lon=(+pos[4]+pos[5]/60)*(pos[6]==="W"?-1:1);if(!stations.has(sender))el("stations").add(new Option(sender));stations.set(sender,{lat,lon});draw();}
}
el("start").onclick=()=>{origin=selected()&&snap(selected());el("directions").textContent=origin?"Choose a destination.":"No nearby walking path";};
el("route").onclick=()=>{const dest=selected()&&snap(selected());if(!origin||!dest){el("directions").textContent="Choose endpoints near a walking path";return;}const result=route(origin,dest);path=result[0];el("directions").textContent=path.length?result[1].toFixed(2)+" km walking":"No connected walking route";draw();};
el("query").oninput=search;el("importer").onclick=()=>Radio.region();el("zin").onclick=()=>{scale*=1.5;draw();};el("zout").onclick=()=>{scale/=1.5;draw();};
map.onpointerdown=e=>{drag=[e.clientX,e.clientY];map.setPointerCapture(e.pointerId);};map.onpointermove=e=>{if(!drag)return;center={lon:center.lon-(e.clientX-drag[0])/scale,lat:center.lat+(e.clientY-drag[1])*Math.cos(center.lat*Math.PI/180)/scale};drag=[e.clientX,e.clientY];draw();};map.onpointerup=map.onpointercancel=()=>drag=null;
el("connect").onclick=()=>{localStorage.call=el("call").value.toUpperCase().trim();el("call").value=localStorage.call;Radio.connect(el("host").value,+el("port").value,localStorage.call,el("pass").value,center.lat,center.lon);};el("stop").onclick=()=>{onlineAccount="";Radio.disconnect();el("status").textContent="Disconnected";};
el("locate").onclick=()=>{const id=el("stations").value;if(!stations.has(id))return;center=stations.get(id);el("peer").value=id;draw();history();};
el("send").onclick=queueMessage;el("peer").oninput=history;el("call").value=localStorage.call||"";window.onresize=draw;loadRegion(localStorage.region||Radio.sample());

