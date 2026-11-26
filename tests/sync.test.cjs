const assert=require('node:assert/strict');
const {Engine,state,publicConfig,CloudTransport}=require('../shared/sync.js');
let counter=0; const uuid=()=>`00000000-0000-4000-8000-${String(++counter).padStart(12,'0')}`;
const record=(id,title)=>({Id:id,Title:title,Kind:'event',Start:'2026-11-26',End:'2026-11-26',Time:'',Category:'生活',Notes:'',Repeat:'none',Until:'',Reminder:-1,DoneDates:[]});
const clone=x=>JSON.parse(JSON.stringify(x));
class Server {
 constructor(){this.rows={};}
 transport(){return {pull:async()=>Object.values(clone(this.rows)),push:async op=>this.apply(op)};}
 apply(op){const old=this.rows[op.id];if(old&&old.last_op===op.op)return{conflict:false,row:clone(old)};if(old&&old.revision!==op.base)return{conflict:true,row:clone(old)};if(!old&&op.base!==0)throw Error('unknown base');const row={id:op.id,payload:clone(op.payload),deleted:op.deleted,last_op:op.op,revision:(old?.revision||0)+1};this.rows[op.id]=row;return{conflict:false,row:clone(row)};}
}
const device=()=>new Engine(state(),()=>{},()=>{},uuid);
(async()=>{
 const s=new Server(),a=device(),b=device(); a.put(record('a','电脑新增')); await a.cycle(s.transport()); await b.cycle(s.transport()); assert.equal(b.s.items.a.Title,'电脑新增');
 b.put({...b.s.items.a,Title:'手机修改'}); await b.cycle(s.transport()); await a.cycle(s.transport()); assert.equal(a.s.items.a.Title,'手机修改');
 a.put(record('left','离线电脑')); b.put(record('right','离线手机')); await a.cycle(s.transport()); await b.cycle(s.transport()); await a.cycle(s.transport()); assert.ok(a.s.items.right&&b.s.items.left);
 a.remove('a'); await a.cycle(s.transport()); await b.cycle(s.transport()); assert.equal(b.s.items.a,undefined); assert.equal(s.rows.a.deleted,true);
 b.put(record('a','手机离线修改已删除事项')); b.s.pending.a.base=1; await b.cycle(s.transport()); assert.ok(b.s.conflicts.a); assert.equal(b.s.items.a.Title,'手机离线修改已删除事项'); b.resolve('a','remote'); assert.equal(b.s.items.a,undefined);
 a.put(record('conflict','原始')); await a.cycle(s.transport()); await b.cycle(s.transport()); a.put({...a.s.items.conflict,Title:'电脑版本'}); b.put({...b.s.items.conflict,Title:'手机版本'}); await a.cycle(s.transport()); await b.cycle(s.transport()); assert.equal(b.s.conflicts.conflict.remote.payload.Title,'电脑版本'); assert.equal(b.s.items.conflict.Title,'手机版本'); b.resolve('conflict','local'); await b.cycle(s.transport()); await a.cycle(s.transport()); assert.equal(a.s.items.conflict.Title,'手机版本');
 const c=device(); c.put(record('retry','重试')); const sent=clone(c.s.pending.retry); const first=s.apply(sent),again=s.apply(sent); assert.equal(first.row.revision,again.row.revision); await c.cycle(s.transport()); assert.equal(c.s.pending.retry,undefined);
 c.put(record('flight','发送中')); let release; const delay=new Promise(r=>release=r); let started;const ready=new Promise(r=>started=r);const cycle=c.cycle({pull:async()=>[],push:async op=>{started();await delay;return s.apply(op);}});await ready;c.put(record('flight','发送期间再改一次'));release();await cycle;assert.equal(c.s.items.flight.Title,'发送期间再改一次');assert.ok(c.s.pending.flight);await c.cycle(s.transport());assert.equal(s.rows.flight.payload.Title,'发送期间再改一次');
 const offline=device();offline.put(record('local','断网保留'));await assert.rejects(()=>offline.cycle({pull:async()=>{throw Error('offline')},push:()=>{}}));assert.ok(offline.s.pending.local&&offline.s.items.local);assert.equal(offline.running,false);
 assert.throws(()=>publicConfig({url:'http://bad.local',key:'x'}));assert.throws(()=>publicConfig({url:'https://192.168.1.5:24867',key:'sb_secret_bad'}));
 const auth=device();auth.s.config={url:'https://192.168.1.6:24867',key:'old',email:'a'};const old=clone(auth.s.config);const api=new CloudTransport(auth,async()=>({ok:false,status:400,text:async()=>JSON.stringify({message:'bad login'})}));await assert.rejects(()=>api.signIn({url:'https://192.168.1.7:24867',key:'new',email:'b'},'bad'));assert.deepEqual(auth.s.config,old);
 let fail=false;const durable=new Engine(state(),()=>{if(fail)throw Error('disk full')},()=>{},uuid);durable.put(record('atomic','原始'));const snapshot=clone(durable.s);fail=true;assert.throws(()=>durable.put(record('atomic','不应留在内存')),/disk full/);assert.deepEqual(durable.s,snapshot);await assert.rejects(()=>durable.cycle({pull:async()=>[{id:'remote',payload:record('remote','远端'),revision:1,last_op:uuid(),deleted:false}],push:()=>{throw Error('unreachable')}}),/disk full/);assert.deepEqual(durable.s,snapshot);assert.equal(durable.running,false);
 console.log('PASS two-way create/edit/delete, tombstones, offline merge, conflicts, idempotent retries, in-flight edits, offline preservation, HTTPS/key validation, atomic login configuration');
})().catch(e=>{console.error(e);process.exitCode=1});
