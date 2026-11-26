(function (root, factory) {
  const api = factory(); if (typeof module === 'object' && module.exports) module.exports = api; else root.CalendarSync = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';
  const copy = x => JSON.parse(JSON.stringify(x));
  function stable(x) { if (Array.isArray(x)) return '[' + x.map(stable).join(',') + ']'; if (x && typeof x === 'object') return '{' + Object.keys(x).sort().map(k => JSON.stringify(k) + ':' + stable(x[k])).join(',') + '}'; return JSON.stringify(x); }
  function equal(a,b) { return stable(a) === stable(b); }
  function uuid() { const b=new Uint8Array(16); globalThis.crypto.getRandomValues(b); b[6]=(b[6]&15)|64; b[8]=(b[8]&63)|128; const s=Array.from(b,x=>x.toString(16).padStart(2,'0')).join(''); return s.slice(0,8)+'-'+s.slice(8,12)+'-'+s.slice(12,16)+'-'+s.slice(16,20)+'-'+s.slice(20); }
  function state() { return {version:1,owner:'',items:{},revisions:{},pending:{},conflicts:{},config:{url:'',key:'',email:''},auth:null}; }
  function normalize(s) { const d=state(); if(s && s.version===1) Object.assign(d,s); for(const k of ['items','revisions','pending','conflicts','config']) if(!d[k]||typeof d[k]!=='object'||Array.isArray(d[k])) d[k]=state()[k]; return d; }
  function publicConfig(config) {
    const url=new URL(config.url); if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash) throw Error('电脑连接地址须为 HTTPS 地址');
    const key=String(config.key||'').trim(); if(!key||key.startsWith('sb_secret_')) throw Error('请使用 publishable / anon 公钥，不要填写服务器密钥');
    if(key.split('.').length===3) { try { const claims=JSON.parse(atob(key.split('.')[1].replace(/-/g,'+').replace(/_/g,'/'))); if(claims.role==='service_role') throw Error('server-secret'); } catch(e) { if(e.message==='server-secret') throw Error('不能使用 service_role 服务器密钥'); } }
    const pin=String(config.pin||'');if(pin&&!/^[A-Fa-f0-9]{64}$/.test(pin))throw Error('电脑身份信息无效');return {url:url.origin+url.pathname.replace(/\/$/,''),key,email:String(config.email||'').trim(),pin};
  }
  class Engine {
    constructor(initial, persist, changed, makeId) { this.s=normalize(initial); this.persist=persist||(()=>{}); this.changed=changed||(()=>{}); this.makeId=makeId||uuid; this.running=false; }
    save() { const serialized=JSON.stringify(this.s); if(serialized!==this.lastSaved) { this.persist(copy(this.s)); this.lastSaved=serialized; } this.changed(); }
    transaction(action) { const before=copy(this.s),saved=this.lastSaved;try{action();this.save();}catch(e){this.s=before;this.lastSaved=saved;throw e;} }
    put(record) { const r=copy(record); if(!r.Id||!String(r.Title||'').trim()) throw Error('事项编号和标题不能为空');this.transaction(()=>{this.s.items[r.Id]=r; this.s.pending[r.Id]={id:r.Id,base:this.s.revisions[r.Id]||0,op:this.makeId(),deleted:false,payload:r}; delete this.s.conflicts[r.Id];}); }
    import(records) { let count=0;this.transaction(()=>{for(const r of records)if(!this.s.items[r.Id]){this.s.items[r.Id]=copy(r);this.s.pending[r.Id]={id:r.Id,base:this.s.revisions[r.Id]||0,op:this.makeId(),deleted:false,payload:copy(r)};count++;}});return count; }
    remove(id) { if(!this.s.items[id]&&!this.s.pending[id]) return;this.transaction(()=>{delete this.s.items[id]; this.s.pending[id]={id,base:this.s.revisions[id]||0,op:this.makeId(),deleted:true,payload:{}}; delete this.s.conflicts[id];}); }
    accept(row) { if(row.deleted) delete this.s.items[row.id]; else this.s.items[row.id]=copy(row.payload); this.s.revisions[row.id]=Number(row.revision); }
    receive(row) {
      const id=row.id, rev=Number(row.revision), pending=this.s.pending[id], known=Number(this.s.revisions[id]||0);
      if(rev<=known) return;
      if(pending) {
        if(pending.op===row.last_op || (pending.deleted===row.deleted && (row.deleted||equal(pending.payload,row.payload)))) { this.accept(row); delete this.s.pending[id]; delete this.s.conflicts[id]; }
        else if(Number(pending.base)!==rev) this.s.conflicts[id]={local:copy(pending),remote:copy(row)};
      } else { this.accept(row); delete this.s.conflicts[id]; }
    }
    acknowledge(sent,row,conflict) {
      const pending=this.s.pending[sent.id];
      if(conflict) { if(pending) this.s.conflicts[sent.id]={local:copy(pending),remote:copy(row)}; return; }
      this.s.revisions[sent.id]=Number(row.revision);
      if(pending && pending.op===sent.op) { this.accept(row); delete this.s.pending[sent.id]; delete this.s.conflicts[sent.id]; }
      else if(pending) { pending.base=Number(row.revision); delete this.s.conflicts[sent.id]; }
      else this.accept(row);
    }
    resolve(id,choice) { const c=this.s.conflicts[id]; if(!c) return;this.transaction(()=>{if(choice==='remote') { this.accept(c.remote); delete this.s.pending[id]; } else { const local=this.s.pending[id]||c.local; this.s.revisions[id]=Number(c.remote.revision); this.s.pending[id]={...copy(local),base:Number(c.remote.revision),op:this.makeId()}; } delete this.s.conflicts[id];}); }
    async cycle(transport) {
      if(this.running) return false; this.running=true;
      try {
        const rows=await transport.pull(); this.transaction(()=>{for(const row of rows) this.receive(row);});
        for(const id of Object.keys(this.s.pending)) { if(this.s.conflicts[id]) continue; const sent=copy(this.s.pending[id]); const result=await transport.push(sent); this.transaction(()=>this.acknowledge(sent,result.row,result.conflict)); }
        return true;
      } finally { this.running=false; }
    }
  }
  class CloudTransport {
    constructor(engine,fetcher) { this.engine=engine; this.fetch=fetcher||globalThis.fetch.bind(globalThis); }
    async request(path,body,authenticated=true,override) {
      const s=this.engine.s,c=publicConfig(override||s.config);
      if(authenticated) await this.ensureAuth();
      const headers={apikey:c.key,'Content-Type':'application/json'}; if(authenticated) headers.Authorization='Bearer '+s.auth.access;
      const response=await this.fetch(c.url+path,{method:body===undefined?'GET':'POST',headers,body:body===undefined?undefined:JSON.stringify(body),pin:c.pin,cache:'no-store'});
      const text=await response.text(); let data; try{data=text?JSON.parse(text):{};}catch{throw Error('电脑返回了无法识别的数据');}
      if(!response.ok) { const err=Error(data.msg||data.message||data.error_description||('同步失败：HTTP '+response.status)); err.status=response.status; throw err; } return data;
    }
    setSession(data,config) { if(!data.access_token) return false; const user=data.user&&data.user.id; if(!user) throw Error('登录响应缺少账号编号'); if(this.engine.s.owner&&this.engine.s.owner!==user) throw Error('本机资料已绑定另一台电脑；请先导出备份，再清除本机资料以更换电脑'); if(config) this.engine.s.config=config; this.engine.s.owner=user; this.engine.s.auth={access:data.access_token,refresh:data.refresh_token,expires:Date.now()+Number(data.expires_in||3600)*1000,uid:user}; this.engine.save(); return true; }
    async signIn(config,password) { const c=publicConfig(config); const data=await this.request('/auth/v1/token?grant_type=password',{email:c.email,password},false,c); this.setSession(data,c); }
    async signUp(config,password) { const c=publicConfig(config); const data=await this.request('/auth/v1/signup',{email:c.email,password},false,c); if(data.access_token) return this.setSession(data,c); this.engine.s.config=c; this.engine.save(); return false; }
    async ensureAuth() { const a=this.engine.s.auth; if(!a) throw Error('请先连接云同步账号'); if(a.expires>Date.now()+60000) return; const data=await this.request('/auth/v1/token?grant_type=refresh_token',{refresh_token:a.refresh},false); this.setSession(data); }
    async pull() { const all=[]; for(let offset=0;;offset+=1000) { const rows=await this.request('/rest/v1/calendar_items?select=id,payload,revision,deleted,last_op&order=id.asc&limit=1000&offset='+offset); if(!Array.isArray(rows)) throw Error('同步日程格式错误'); all.push(...rows); if(rows.length<1000) return all; if(all.length>=20000) throw Error('电脑日程超过本机支持的数量'); } }
    async push(op) { return await this.request('/rest/v1/rpc/calendar_apply',{p_id:op.id,p_payload:op.payload,p_base:op.base,p_deleted:op.deleted,p_op:op.op}); }
    async logout() { try { if(this.engine.s.auth) await this.request('/auth/v1/logout',{}); } finally { this.engine.s.auth=null; this.engine.save(); } }
  }
  return {Engine,CloudTransport,state,normalize,uuid,equal,publicConfig};
});
