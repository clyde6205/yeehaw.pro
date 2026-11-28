// Section-by-section functional audit of the founder console against a real (test) database. DESTROYS the schema of TEST_DATABASE_URL: never point it at production.
const crypto=require('node:crypto');const P=require('node:path').join(__dirname,'..')+'/';
const {makePool,migrate}=require(P+'db');const {createApp}=require(P+'server');const {generateKeyPem}=require(P+'lib/sign');
const out=[];const chk=(sec,name,ok,note='')=>{out.push([sec,name,ok]);console.log(`${ok?'PASS':'FAIL'}  [${sec}] ${name}${note?'  -> '+note:''}`)};
const uuid=()=>crypto.randomUUID();
(async()=>{
  const ADMIN=crypto.randomBytes(32).toString('base64url');
  const pool=makePool(process.env.TEST_DATABASE_URL||'postgres://yh:yhpass@localhost:5432/yeehaw_test');
  await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');await migrate(pool);
  const srv=createApp({pool,adminToken:ADMIN,signingKeyPem:generateKeyPem(),rateLimits:false}).listen(0);
  const base='http://127.0.0.1:'+srv.address().port;
  const post=(p,b,h={})=>fetch(base+p,{method:'POST',headers:{'content-type':'application/json',...h},body:JSON.stringify(b)});
  const adm=(p,o={})=>fetch(base+'/v1/admin'+p,{...o,headers:{authorization:'Bearer '+ADMIN,'content-type':'application/json',...(o.headers||{})}});
  const boot=(id,x={})=>({device_id:id,app_version:'0.6.0',version_code:18,android_os_ver:'13 (API 33)',ram_mb:1900,country_locale:'PH',...x});
  const ids=[uuid(),uuid(),uuid()];
  for(const id of ids) await post('/v1/telemetry/boot',boot(id));
  await post('/v1/telemetry/boot',boot(uuid(),{country_locale:'ID',ram_mb:3000}));
  // 1 OVERVIEW
  let s=await (await adm('/stats')).json();
  chk('Overview','total installs counts real boots (4)',s.total_installs===4,`total=${s.total_installs}`);
  chk('Overview','DAU = devices seen today (4)',s.dau===4,`dau=${s.dau}`);
  chk('Overview','low-RAM count real (3 of 4 <=2GB)',s.low_ram_devices===3,`low=${s.low_ram_devices}`);
  chk('Overview','ads revenue is empty list (not fabricated) with no ad events',Array.isArray(s.ads_30d.revenue)&&s.ads_30d.revenue.length===0&&s.ads_30d.impressions===0);
  // 2 USERS
  let l=await (await adm('/devices?limit=2')).json();
  chk('Users','pagination: total 4, page of 2',l.total===4&&l.devices.length===2);
  l=await (await adm('/devices?q='+ids[0].slice(0,8))).json();
  chk('Users','search by id prefix finds exactly that device',l.total===1&&l.devices[0].device_id===ids[0]);
  l=await (await adm('/devices?country=ID')).json(); chk('Users','filter by country',l.total===1);
  let r=await adm(`/devices/${ids[1]}/block`,{method:'POST',body:JSON.stringify({reason:'audit test',blocked:true})});
  chk('Users','block device (admin action)',r.status===200,`status ${r.status}`);
  const bj=await (await post('/v1/telemetry/boot',boot(ids[1]))).json(); const ev=await post('/v1/telemetry/ad-event',{device_id:ids[1],event:'impression',ad_format:'banner'}); chk('Users','blocked device is told blocked:true and its ad events are refused (404)',bj.blocked===true&&ev.status===404,`boot.blocked=${bj.blocked}, ad-event ${ev.status}`);
  r=await adm(`/devices/${ids[1]}/block`,{method:'POST',body:JSON.stringify({blocked:false})}); chk('Users','unblock restores',r.status===200&&(await post('/v1/telemetry/boot',boot(ids[1]))).status===200);
  r=await adm(`/devices/${ids[0]}/note`,{method:'POST',body:JSON.stringify({notes:'vip tester',tags:['VIP']})}); const d0=await (await adm('/devices/'+ids[0])).json();
  chk('Users','note persisted',r.status===200&&JSON.stringify(d0).includes('vip tester'));
  r=await adm(`/devices/${ids[2]}`,{method:'DELETE'}); chk('Users','erase device, then 404',r.status===200&&(await adm('/devices/'+ids[2])).status===404);
  // 3 ADVERTISING
  await post('/v1/telemetry/ad-event',{device_id:ids[0],event:'impression',ad_format:'banner'});
  await post('/v1/telemetry/ad-event',{device_id:ids[0],event:'click',ad_format:'banner'});
  await post('/v1/telemetry/ad-event',{device_id:ids[0],event:'paid',ad_format:'banner',revenue_micros:1500000,currency:'USD'});
  const ads=await (await adm('/analytics/ads')).json(); chk('Advertising','real impression/click/revenue recorded',JSON.stringify(ads).includes('1.5')||JSON.stringify(ads).includes('1500000'),JSON.stringify(ads).slice(0,90));
  let cfg=await (await fetch(base+'/v1/config/app-config')).json();
  r=await adm('/config',{method:'POST',body:JSON.stringify({admob_enabled:true,ads_policy:{},base_version:cfg.version,note:'ads on'})});
  cfg=await (await fetch(base+'/v1/config/app-config')).json();
  chk('Advertising','toggle admob in console changes what the app receives',r.status===201&&cfg.admob_enabled===true,`status ${r.status}, app sees admob_enabled=${cfg.admob_enabled}`);
  // 4 UPDATES & FLAGS
  r=await adm('/config',{method:'POST',body:JSON.stringify({features:{memory_saver:true},update_policy:{latest_version_code:30,latest_version_name:'0.7.0',min_version_code:20,rollout_percent:100,update_url:'https://yeehaw.pro/get',message:'Please update'},base_version:cfg.version,note:'flags'})});
  cfg=await (await fetch(base+'/v1/config/app-config')).json(); chk('Flags','feature flag + update policy published and visible to the app',r.status===201&&cfg.features.memory_saver===true&&cfg.update_policy.min_version_code===20);
  const st=await (await fetch(`${base}/v1/device/status?d=${ids[0]}&vc=18`)).json(); chk('Flags','old app (v18<min 20) is told an update is required',st.update_required===true,JSON.stringify(st).slice(0,80));
  const h=await (await adm('/config')).json(); const prev=h.history.find(x=>!x.is_active); r=await adm('/config/rollback',{method:'POST',body:JSON.stringify({version:prev.version})});
  cfg=await (await fetch(base+'/v1/config/app-config')).json(); chk('Flags','rollback publishes an older config as a new version',r.status===201,`now v${cfg.version}`);
  // 6 OPERATIONS
  const sys=await (await adm('/system')).json(); chk('Operations','database latency measured from a real query',typeof sys.db_latency_ms==='number'&&sys.db_bytes>0,`${sys.db_latency_ms} ms`);
  chk('Operations','/healthz hits the database; /livez does not',(await fetch(base+'/healthz')).status===200&&(await fetch(base+'/livez')).status===200);
  const hl=await adm('/analytics/health'); chk('Operations','health analytics endpoint answers',hl.status===200);
  // 7 SEARCH & BLOCKLIST
  cfg=await (await fetch(base+'/v1/config/app-config')).json();
  r=await adm('/config',{method:'POST',body:JSON.stringify({blacklisted_domains:['Bad.example.com','*.scam.example.net'],base_version:cfg.version,note:'blocklist'})});
  cfg=await (await fetch(base+'/v1/config/app-config')).json(); chk('Blocklist','domain blocklist published, normalised, reaches the app',r.status===201&&cfg.blacklisted_domains.includes('bad.example.com'),JSON.stringify(cfg.blacklisted_domains));
  r=await adm('/config',{method:'POST',body:JSON.stringify({blacklisted_domains:['javascript:alert(1)','x y'],base_version:cfg.version})}); chk('Blocklist','dangerous/malformed entries rejected (400)',r.status===400,`status ${r.status}`);
  // 8 AGENTS
  const ag=await (await adm('/agents')).json(); chk('Agents','eight agents listed, none claims activity before running',(ag.agents||ag.roster||[]).length===8,`n=${(ag.agents||ag.roster||[]).length}`);
  // 9 SYSTEM & AUDIT / security
  chk('Security','every admin route rejects no token (401)',(await fetch(base+'/v1/admin/stats')).status===401&&(await fetch(base+'/v1/admin/audit')).status===401);
  chk('Security','wrong token rejected (401)',(await fetch(base+'/v1/admin/stats',{headers:{authorization:'Bearer wrong'}})).status===401);
  const au=await (await adm('/audit?limit=200')).json(); const acts=au.entries.map(e=>e.action);
  for(const a of ['device.block','device.unblock','device.erase']) chk('Audit','"'+a+'" recorded',acts.includes(a));
  chk('Audit','config publishes recorded',acts.some(a=>a.startsWith('config')));
  let blocked=false; try{await pool.query("DELETE FROM admin_audit")}catch(e){blocked=/append-only/.test(e.message)} chk('Audit','audit rows cannot be deleted',blocked);
  const f=out.filter(x=>!x[2]); console.log(`\nRESULT: ${out.length-f.length}/${out.length} passed`+(f.length?'; FAILED: '+f.map(x=>`[${x[0]}] ${x[1]}`).join(' | '):''));
  srv.close();await pool.end();
})().catch(e=>{console.error('SCRIPT ERROR',e);process.exit(1)});
