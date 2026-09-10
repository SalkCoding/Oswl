const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const handlers={},notifications=[],opened=[];
const context={URL,clients:{openWindow:async url=>opened.push(url)},self:{location:{origin:'https://oswl.example'},addEventListener:(name,fn)=>handlers[name]=fn,registration:{showNotification:async(title,data)=>notifications.push({title,data})}}};
vm.runInNewContext(fs.readFileSync('src/main/resources/static/oswl-push-sw.js','utf8'),context);
(async()=>{
let pending;
handlers.push({data:{json:()=>({body:'Review required',url:'https://attacker.example/',tag:'security'})},waitUntil:p=>pending=p});await pending;
assert.equal(notifications[0].data.data.path,'/projects');
handlers.notificationclick({notification:{close(){},data:{path:'//attacker.example/'}},waitUntil:p=>pending=p});await pending;
assert.equal(opened[0],'https://oswl.example/projects');
handlers.notificationclick({notification:{close(){},data:{path:'/projects/12/security-center'}},waitUntil:p=>pending=p});await pending;
assert.equal(opened[1],'https://oswl.example/projects/12/security-center');
console.log('Service worker notification and same-origin click checks passed');
})().catch(error=>{console.error(error);process.exitCode=1;});
